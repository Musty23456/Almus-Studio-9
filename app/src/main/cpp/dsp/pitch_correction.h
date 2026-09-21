#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace almus::dsp {

// Vocal pitch correction: autocorrelation tracking + granular OLA pitch shifting.
// Phase 7.20 adds a source/filter (LPC) path for formant-aware processing. It
// keeps the vocal spectral envelope approximately fixed while the excitation
// is pitch-shifted, reducing the familiar "chipmunk/robot" coloration. This is
// intentionally a compact offline DSP implementation, not a clone of any
// commercial Auto-Tune algorithm.

enum class HarmonyMode : int { NONE = 0, THIRD = 1, FIFTH = 2, DUET = 3, BIG_HARMONY = 4, OCTAVE = 5 };

struct PitchCorrectionParams {
    int rootPitchClass = 0;   // 0 = C, 1 = C#, ... 11 = B
    uint16_t scaleMask = 0;   // bit i set => pitch class (root + i) % 12 is an allowed target
    float strength = 1.0f;    // 0 = no correction, 1 = fully snap to nearest allowed note
    float speedMs = 50.0f;    // retune glide time; smaller = snappier correction
    bool hardMode = false;    // true: snap almost instantly
    HarmonyMode harmonyMode = HarmonyMode::NONE;
    float harmonyMix = 0.35f;
    float harmonyPan = 0.55f;
    float formantCompensation = 0.5f;
};

struct PitchDetectionResult {
    bool voiced = false;
    float frequencyHz = 0.0f;
};

// Normalized autocorrelation over [samples, samples+frameCount). Searches lags
// corresponding to typical vocal fundamentals (70-1000 Hz.) `voiced` is only
// true when the strongest correlation peak is confident enough to trust --
// unpitched/noisy audio (breath, consonants, silence) is correctly reported
// as unvoiced so it passes through uncorrected, per the "clear handling of
// unpitched audio" requirement.
inline PitchDetectionResult detectPitch(const float* samples, int frameCount, int sampleRate,
                                         float minFreqHz = 70.0f, float maxFreqHz = 1000.0f) {
    if (!samples || frameCount < 64 || sampleRate <= 0) return {false, 0.0f};
    int minLag = std::max(1, static_cast<int>(sampleRate / maxFreqHz));
    int maxLag = std::min(frameCount - 2, static_cast<int>(sampleRate / minFreqHz));
    if (maxLag <= minLag) return {false, 0.0f};

    // Remove DC and apply a Hann window. This materially reduces octave errors
    // on close-mic vocals and makes the detector less sensitive to consonants.
    float mean = 0.0f;
    for (int i = 0; i < frameCount; ++i) mean += samples[i];
    mean /= static_cast<float>(frameCount);
    std::vector<float> x(frameCount);
    float energy = 0.0f;
    for (int i = 0; i < frameCount; ++i) {
        float w = 0.5f - 0.5f * cosf(2.0f * static_cast<float>(M_PI) * i / (frameCount - 1));
        x[i] = (samples[i] - mean) * w;
        energy += x[i] * x[i];
    }
    if (energy < 1e-7f) return {false, 0.0f};

    float best = -1.0f;
    int bestLag = -1;
    for (int lag = minLag; lag <= maxLag; ++lag) {
        float corr = 0.0f, a = 0.0f, b = 0.0f;
        int n = frameCount - lag;
        for (int i = 0; i < n; ++i) {
            corr += x[i] * x[i + lag];
            a += x[i] * x[i];
            b += x[i + lag] * x[i + lag];
        }
        float norm = sqrtf(a * b) + 1e-9f;
        float score = corr / norm;
        if (score > best) { best = score; bestLag = lag; }
    }
    if (bestLag <= 0 || best < 0.42f) return {false, 0.0f};

    // Parabolic interpolation around the correlation maximum gives smoother
    // pitch tracking than integer-lag detection alone.
    auto corrAt = [&](int lag) {
        float corr = 0.0f, a = 0.0f, b = 0.0f;
        int n = frameCount - lag;
        for (int i = 0; i < n; ++i) {
            corr += x[i] * x[i + lag];
            a += x[i] * x[i]; b += x[i + lag] * x[i + lag];
        }
        return corr / (sqrtf(a * b) + 1e-9f);
    };
    float delta = 0.0f;
    if (bestLag > minLag && bestLag < maxLag) {
        float ym = corrAt(bestLag - 1), y0 = best, yp = corrAt(bestLag + 1);
        float denom = ym - 2.0f * y0 + yp;
        if (fabsf(denom) > 1e-6f) delta = 0.5f * (ym - yp) / denom;
        delta = std::clamp(delta, -0.45f, 0.45f);
    }
    float lagF = static_cast<float>(bestLag) + delta;
    return {true, static_cast<float>(sampleRate) / lagF};
}

inline float nearestScaleFrequency(float inputFreqHz, int rootPitchClass, uint16_t scaleMask) {
    if (inputFreqHz <= 0.0f) return inputFreqHz;
    float midiFloat = 69.0f + 12.0f * log2f(inputFreqHz / 440.0f);
    int midiRounded = static_cast<int>(std::round(midiFloat));

    int bestMidi = midiRounded;
    float bestDist = 1e9f;
    for (int candidate = midiRounded - 12; candidate <= midiRounded + 12; candidate++) {
        int pitchClass = ((candidate - rootPitchClass) % 12 + 12) % 12;
        bool inScale = scaleMask == 0 || (scaleMask & (1 << pitchClass)) != 0;
        if (inScale) {
            float dist = std::fabs(static_cast<float>(candidate) - midiFloat);
            if (dist < bestDist) {
                bestDist = dist;
                bestMidi = candidate;
            }
        }
    }
    return 440.0f * powf(2.0f, (bestMidi - 69) / 12.0f);
}

// Computes one target pitch-shift ratio per analysis hop from a mono
// reference signal. Both channels of a stereo file are shifted using this
// same ratio timeline so the stereo image doesn't drift.
inline std::vector<float> computeRatioTimeline(const std::vector<float>& monoSamples, int sampleRate,
                                                int grainFrames, int hop, const PitchCorrectionParams& params) {
    int64_t totalFrames = static_cast<int64_t>(monoSamples.size());
    std::vector<float> ratios;
    float smoothSemitones = 0.0f;
    bool havePitch = false;
    int stableMidi = -1;
    float smoothingCoeff = params.hardMode ? 0.0f : std::clamp(params.speedMs / 180.0f, 0.02f, 0.96f);

    for (int64_t pos = 0; pos + grainFrames <= totalFrames; pos += hop) {
        auto detection = detectPitch(&monoSamples[pos], grainFrames, sampleRate);
        float targetShift = 0.0f;
        if (detection.voiced && params.strength > 0.0f) {
            float inputMidi = 69.0f + 12.0f * log2f(detection.frequencyHz / 440.0f);
            int nearestMidi = static_cast<int>(std::round(69.0f + 12.0f * log2f(
                nearestScaleFrequency(detection.frequencyHz, params.rootPitchClass, params.scaleMask) / 440.0f)));
            // Keep the target note stable while the detected pitch vibrates around
            // a semitone boundary. The next note must be clearly closer before it wins.
            if (stableMidi < 0 || std::fabs(inputMidi - nearestMidi) < 0.43f ||
                std::fabs(inputMidi - stableMidi) > 0.72f) stableMidi = nearestMidi;
            targetShift = (static_cast<float>(stableMidi) - inputMidi) * params.strength;
            if (!havePitch) smoothSemitones = targetShift;
            havePitch = true;
        } else {
            stableMidi = -1;
            targetShift = 0.0f;
        }
        smoothSemitones = params.hardMode ? targetShift
            : smoothingCoeff * smoothSemitones + (1.0f - smoothingCoeff) * targetShift;
        ratios.push_back(powf(2.0f, smoothSemitones / 12.0f));
    }
    return ratios;
}

// Applies the precomputed ratio timeline to one channel via granular
// resample + overlap-add (Hann-windowed). Output is the same length as the
// input -- pitch changes, duration and clip alignment do not.
inline std::vector<float> applyPitchShiftOLA(const std::vector<float>& channelSamples,
                                              const std::vector<float>& ratioPerHop,
                                              int grainFrames, int hop) {
    int64_t totalFrames = static_cast<int64_t>(channelSamples.size());
    std::vector<float> output(totalFrames, 0.0f);
    std::vector<float> windowSum(totalFrames, 0.0f);
    std::vector<float> hann(grainFrames);
    for (int i = 0; i < grainFrames; ++i)
        hann[i] = 0.5f - 0.5f * cosf(2.0f * static_cast<float>(M_PI) * i / (grainFrames - 1));

    int64_t writePos = 0;
    for (size_t h = 0; h < ratioPerHop.size() && writePos + grainFrames <= totalFrames; ++h, writePos += hop) {
        float ratio = std::clamp(ratioPerHop[h], 0.5f, 2.0f);
        // Continuous read progression prevents the periodic "grain reset" that
        // produced audible repeats in the earlier prototype.
        float readStart = static_cast<float>(writePos) * ratio;
        for (int i = 0; i < grainFrames; ++i) {
            float src = readStart + static_cast<float>(i) * ratio;
            if (src >= static_cast<float>(totalFrames - 1)) break;
            int64_t a = static_cast<int64_t>(src);
            float frac = src - static_cast<float>(a);
            float sample = channelSamples[a] + (channelSamples[a + 1] - channelSamples[a]) * frac;
            int64_t out = writePos + i;
            output[out] += sample * hann[i];
            windowSum[out] += hann[i];
        }
    }
    for (int64_t i = 0; i < totalFrames; ++i)
        output[i] = windowSum[i] > 1e-6f ? output[i] / windowSum[i] : channelSamples[i];
    return output;
}


// --- Formant-aware source/filter pitch shifting -----------------------------
// Estimate a compact LPC spectral envelope for one grain. The excitation is
// inverse-filtered, pitch-shifted, then passed through the original vocal
// filter. Keeping the filter fixed is the important part: pitch changes, while
// broad formant locations remain anchored to the singer's original vocal tract.
inline bool estimateLpc(const float* x, int n, int order, std::vector<float>& a) {
    if (!x || n < order * 8 || order < 1) return false;
    std::vector<float> r(order + 1, 0.0f);
    float mean = 0.0f;
    for (int i = 0; i < n; ++i) mean += x[i];
    mean /= static_cast<float>(n);
    for (int k = 0; k <= order; ++k) {
        double acc = 0.0;
        for (int i = k; i < n; ++i) {
            float xi = x[i] - mean;
            float xj = x[i - k] - mean;
            acc += static_cast<double>(xi) * xj;
        }
        r[k] = static_cast<float>(acc / static_cast<double>(n));
    }
    if (r[0] < 1e-7f) return false;
    a.assign(order + 1, 0.0f);
    a[0] = 1.0f;
    float err = r[0];
    for (int i = 1; i <= order; ++i) {
        float acc = r[i];
        for (int j = 1; j < i; ++j) acc += a[j] * r[i - j];
        float k = -acc / std::max(err, 1e-8f);
        k = std::clamp(k, -0.97f, 0.97f);
        std::vector<float> next = a;
        next[i] = k;
        for (int j = 1; j < i; ++j) next[j] = a[j] + k * a[i - j];
        a.swap(next);
        err *= (1.0f - k * k);
        if (err < r[0] * 1e-5f) break;
    }
    return true;
}

inline std::vector<float> lpcInverseFilter(const std::vector<float>& x, const std::vector<float>& a) {
    std::vector<float> e(x.size(), 0.0f);
    int order = static_cast<int>(a.size()) - 1;
    for (size_t n = 0; n < x.size(); ++n) {
        float y = x[n];
        for (int k = 1; k <= order && static_cast<size_t>(k) <= n; ++k) y += a[k] * x[n - k];
        e[n] = y;
    }
    return e;
}

inline std::vector<float> lpcSynthesize(const std::vector<float>& e, const std::vector<float>& a) {
    std::vector<float> y(e.size(), 0.0f);
    int order = static_cast<int>(a.size()) - 1;
    for (size_t n = 0; n < e.size(); ++n) {
        float v = e[n];
        for (int k = 1; k <= order && static_cast<size_t>(k) <= n; ++k) v -= a[k] * y[n - k];
        y[n] = std::clamp(v, -2.0f, 2.0f);
    }
    return y;
}

inline std::vector<float> applyPitchShiftOLAFormantAware(const std::vector<float>& channelSamples,
                                                          const std::vector<float>& ratioPerHop,
                                                          int grainFrames, int hop,
                                                          float compensation) {
    if (channelSamples.empty() || ratioPerHop.empty() || compensation <= 0.001f)
        return applyPitchShiftOLA(channelSamples, ratioPerHop, grainFrames, hop);
    const int order = 12;
    const int64_t totalFrames = static_cast<int64_t>(channelSamples.size());
    std::vector<float> output(totalFrames, 0.0f), windowSum(totalFrames, 0.0f);
    std::vector<float> hann(grainFrames);
    for (int i = 0; i < grainFrames; ++i)
        hann[i] = 0.5f - 0.5f * cosf(2.0f * static_cast<float>(M_PI) * i / (grainFrames - 1));

    int64_t writePos = 0;
    for (size_t h = 0; h < ratioPerHop.size() && writePos + grainFrames <= totalFrames; ++h, writePos += hop) {
        std::vector<float> grain(channelSamples.begin() + writePos, channelSamples.begin() + writePos + grainFrames);
        std::vector<float> coeff;
        bool ok = estimateLpc(grain.data(), grainFrames, order, coeff);
        float ratio = std::clamp(ratioPerHop[h], 0.5f, 2.0f);
        std::vector<float> shifted(grainFrames, 0.0f);
        if (ok) {
            auto excitation = lpcInverseFilter(grain, coeff);
            for (int i = 0; i < grainFrames; ++i) {
                float src = static_cast<float>(i) * ratio;
                if (src >= grainFrames - 1) src = static_cast<float>(grainFrames - 1);
                int a = static_cast<int>(src);
                float frac = src - static_cast<float>(a);
                int b = std::min(a + 1, grainFrames - 1);
                shifted[i] = excitation[a] + (excitation[b] - excitation[a]) * frac;
            }
            auto synthesized = lpcSynthesize(shifted, coeff);
            float dry = std::clamp(1.0f - compensation, 0.0f, 1.0f);
            for (int i = 0; i < grainFrames; ++i) shifted[i] = dry * grain[i] + compensation * synthesized[i];
        } else {
            for (int i = 0; i < grainFrames; ++i) {
                float src = static_cast<float>(i) * ratio;
                if (src >= grainFrames - 1) src = static_cast<float>(grainFrames - 1);
                int a = static_cast<int>(src);
                float frac = src - static_cast<float>(a);
                int b = std::min(a + 1, grainFrames - 1);
                shifted[i] = grain[a] + (grain[b] - grain[a]) * frac;
            }
        }
        for (int i = 0; i < grainFrames; ++i) {
            int64_t out = writePos + i;
            output[out] += shifted[i] * hann[i];
            windowSum[out] += hann[i];
        }
    }
    for (int64_t i = 0; i < totalFrames; ++i)
        output[i] = windowSum[i] > 1e-6f ? output[i] / windowSum[i] : channelSamples[i];
    return output;
}

// Top-level entry point: corrects an interleaved stereo (or mono) buffer.
inline std::vector<float> correctPitch(const std::vector<float>& interleaved, int channelCount,
                                        int64_t frameCount, int sampleRate,
                                        const PitchCorrectionParams& params) {
    if (frameCount <= 0 || channelCount <= 0) return interleaved;

    int grainFrames = std::max(64, static_cast<int>(sampleRate * 0.046)); // ~46ms
    int hop = std::max(1, grainFrames / 4);

    // Downmix to mono for pitch detection only -- correction is applied to
    // each channel independently using the same ratio timeline.
    std::vector<float> mono(frameCount);
    for (int64_t i = 0; i < frameCount; i++) {
        float sum = 0.0f;
        for (int ch = 0; ch < channelCount; ch++) sum += interleaved[i * channelCount + ch];
        mono[i] = sum / channelCount;
    }

    auto ratios = computeRatioTimeline(mono, sampleRate, grainFrames, hop, params);

    std::vector<std::vector<float>> channels(channelCount, std::vector<float>(frameCount));
    for (int64_t i = 0; i < frameCount; i++)
        for (int ch = 0; ch < channelCount; ch++)
            channels[ch][i] = interleaved[i * channelCount + ch];

    std::vector<float> result(static_cast<size_t>(frameCount) * channelCount);
    for (int ch = 0; ch < channelCount; ch++) {
        auto shifted = params.formantCompensation > 0.001f
            ? applyPitchShiftOLAFormantAware(channels[ch], ratios, grainFrames, hop, params.formantCompensation)
            : applyPitchShiftOLA(channels[ch], ratios, grainFrames, hop);
        for (int64_t i = 0; i < frameCount; i++) result[i * channelCount + ch] = shifted[i];
    }
    return result;
}


inline std::vector<int> harmonyOffsets(HarmonyMode mode, int inputMidi, uint16_t scaleMask = 0) {
    (void)inputMidi;
    int third = ((scaleMask & (1 << 3)) != 0 && (scaleMask & (1 << 4)) == 0) ? 3 : 4;
    switch (mode) {
        case HarmonyMode::THIRD: return {third};
        case HarmonyMode::FIFTH: return {7};
        case HarmonyMode::DUET: return {third, 7};
        case HarmonyMode::BIG_HARMONY: return {third, 7, 12};
        case HarmonyMode::OCTAVE: return {12};
        default: return {};
    }
}

inline std::vector<float> computeRatioTimelineForOffset(const std::vector<float>& monoSamples, int sampleRate,
                                                         int grainFrames, int hop,
                                                         const PitchCorrectionParams& params,
                                                         int semitoneOffset) {
    int64_t totalFrames = static_cast<int64_t>(monoSamples.size());
    std::vector<float> ratios;
    float smoothSemitones = 0.0f;
    int stableMidi = -1;
    float smoothingCoeff = params.hardMode ? 0.0f : std::clamp(params.speedMs / 180.0f, 0.02f, 0.96f);
    for (int64_t pos = 0; pos + grainFrames <= totalFrames; pos += hop) {
        auto detection = detectPitch(&monoSamples[pos], grainFrames, sampleRate);
        float targetShift = 0.0f;
        if (detection.voiced && params.strength > 0.0f) {
            float inputMidi = 69.0f + 12.0f * log2f(detection.frequencyHz / 440.0f);
            float targetFreq = nearestScaleFrequency(detection.frequencyHz, params.rootPitchClass, params.scaleMask);
            int targetMidi = static_cast<int>(std::round(69.0f + 12.0f * log2f(targetFreq / 440.0f)));
            if (stableMidi < 0 || std::fabs(inputMidi - targetMidi) < 0.43f ||
                std::fabs(inputMidi - stableMidi) > 0.72f) stableMidi = targetMidi;
            targetShift = (static_cast<float>(stableMidi + semitoneOffset) - inputMidi) * params.strength;
        } else {
            stableMidi = -1;
            targetShift = 0.0f;
        }
        smoothSemitones = params.hardMode ? targetShift
            : smoothingCoeff * smoothSemitones + (1.0f - smoothingCoeff) * targetShift;
        ratios.push_back(powf(2.0f, smoothSemitones / 12.0f));
    }
    return ratios;
}

inline std::vector<float> mixHarmonyVoices(const std::vector<float>& lead,
                                           const std::vector<std::vector<float>>& voices,
                                           const PitchCorrectionParams& params,
                                           int channelCount) {
    if (voices.empty() || params.harmonyMix <= 0.0f) return lead;
    std::vector<float> out = lead;
    float mix = std::clamp(params.harmonyMix, 0.0f, 1.0f);
    float perVoice = mix / static_cast<float>(voices.size());
    for (size_t v = 0; v < voices.size(); ++v) {
        float pan = voices.size() == 1 ? params.harmonyPan : (v % 2 == 0 ? -params.harmonyPan : params.harmonyPan);
        float l = std::sqrt(0.5f * (1.0f - pan));
        float r = std::sqrt(0.5f * (1.0f + pan));
        for (size_t i = 0; i < voices[v].size(); ++i) {
            if (channelCount == 1) out[i] += voices[v][i] * perVoice;
            else {
                int ch = static_cast<int>(i % static_cast<size_t>(channelCount));
                float g = ch == 0 ? l : (ch == 1 ? r : 1.0f / std::sqrt(static_cast<float>(channelCount)));
                out[i] += voices[v][i] * perVoice * g * 1.4142f;
            }
        }
    }
    for (auto& x : out) x = std::clamp(x, -1.0f, 1.0f);
    return out;
}

inline std::vector<float> processVocal(const std::vector<float>& interleaved, int channelCount,
                                       int64_t frameCount, int sampleRate,
                                       const PitchCorrectionParams& params) {
    auto lead = correctPitch(interleaved, channelCount, frameCount, sampleRate, params);
    if (params.harmonyMode == HarmonyMode::NONE || params.harmonyMix <= 0.0f) return lead;
    int grainFrames = std::max(64, static_cast<int>(sampleRate * 0.046));
    int hop = std::max(1, grainFrames / 4);
    std::vector<float> mono(frameCount);
    for (int64_t i = 0; i < frameCount; ++i) {
        float sum = 0.0f;
        for (int ch = 0; ch < channelCount; ++ch) sum += interleaved[i * channelCount + ch];
        mono[i] = sum / static_cast<float>(channelCount);
    }
    std::vector<std::vector<float>> voices;
    for (int offset : harmonyOffsets(params.harmonyMode, 0, params.scaleMask)) {
        auto ratios = computeRatioTimelineForOffset(mono, sampleRate, grainFrames, hop, params, offset);
        std::vector<float> voice(interleaved.size(), 0.0f);
        for (int ch = 0; ch < channelCount; ++ch) {
            std::vector<float> src(frameCount);
            for (int64_t i = 0; i < frameCount; ++i) src[i] = interleaved[i * channelCount + ch];
            auto shifted = params.formantCompensation > 0.001f
                ? applyPitchShiftOLAFormantAware(src, ratios, grainFrames, hop, params.formantCompensation)
                : applyPitchShiftOLA(src, ratios, grainFrames, hop);
            for (int64_t i = 0; i < frameCount; ++i) voice[i * channelCount + ch] = shifted[i];
        }
        voices.push_back(std::move(voice));
    }
    return mixHarmonyVoices(lead, voices, params, channelCount);
}

} // namespace almus::dsp
