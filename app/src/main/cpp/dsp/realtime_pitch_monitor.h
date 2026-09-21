#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <deque>
#include <mutex>
#include <vector>

#include "pitch_correction.h"

namespace almus::dsp {

// Real-time counterpart to the offline correctPitch() in pitch_correction.h:
// same autocorrelation pitch detection and resample-based shifting idea, but
// running continuously on a live mic stream instead of a whole pre-loaded
// file, for "hear yourself roughly in tune while you sing" monitoring.
//
// Honest simplifications, stated plainly rather than glossed over:
//  - Uses back-to-back grains with a short linear crossfade at each boundary,
//    not full Hann-windowed overlap-add. Simpler to get right in a streaming
//    context; the tradeoff is slightly more audible grain-boundary artifacts
//    than the offline version's smoother OLA.
//  - Cross-thread hand-off (mic input thread -> speaker output thread) uses
//    a mutex-guarded std::deque, not a lock-free ring buffer. This is not
//    strictly real-time-safe (a lock could, in principle, make the audio
//    thread wait) -- a fully lock-free version is a Phase 5 hardening item.
//    In practice, on a modern phone, contention here is rare and brief.
//  - Latency is roughly one grain length (~21ms) plus whatever buffering
//    Oboe itself adds -- noticeably higher than dry monitoring latency, and
//    a real property of any correct-as-you-go pitch shifter, not a bug.
//  - This must only be used with headphones. Monitoring through the phone's
//    speaker will feed back into the microphone.
class RealtimePitchMonitor {
public:
    void init(int sampleRate) {
        sampleRate_ = sampleRate;
        grainFrames_ = std::max(256, static_cast<int>(sampleRate * 0.021f)); // ~21ms
        crossfadeFrames_ = std::max(16, grainFrames_ / 8);
        hopFrames_ = std::max(64, grainFrames_ / 4);
        std::lock_guard<std::mutex> lock(fifoMutex_);
        inputFifo_.clear();
        outputFifo_.clear();
        prevGrainTail_.assign(crossfadeFrames_, 0.0f);
        hasPrevGrain_ = false;
        smoothedRatio_ = 1.0f;
        smoothedSemitones_ = 0.0f;
        stableMidi_ = -1;
        olaBuffer_.assign(grainFrames_, 0.0f);
        olaWindowSum_.assign(grainFrames_, 0.0f);
    }

    void setEnabled(bool enabled) {
        enabled_.store(enabled, std::memory_order_relaxed);
        if (!enabled) {
            std::lock_guard<std::mutex> lock(fifoMutex_);
            inputFifo_.clear();
            outputFifo_.clear();
            hasPrevGrain_ = false;
            smoothedSemitones_ = 0.0f;
            stableMidi_ = -1;
            std::fill(olaBuffer_.begin(), olaBuffer_.end(), 0.0f);
            std::fill(olaWindowSum_.begin(), olaWindowSum_.end(), 0.0f);
        }
    }
    bool isEnabled() const { return enabled_.load(std::memory_order_relaxed); }

    void setParams(const PitchCorrectionParams& params) {
        std::lock_guard<std::mutex> lock(paramsMutex_);
        params_ = params;
    }

    void setMonitorVolumeDb(float db) { monitorVolumeDb_.store(db, std::memory_order_relaxed); }
    float getMonitorVolumeLinear() const { return powf(10.0f, monitorVolumeDb_.load(std::memory_order_relaxed) / 20.0f); }

    // Called from the mic input callback thread with newly captured mono samples.
    void pushInput(const float* samples, int numFrames) {
        if (!isEnabled()) return;
        std::lock_guard<std::mutex> lock(fifoMutex_);
        inputFifo_.insert(inputFifo_.end(), samples, samples + numFrames);
        processAvailableGrains();
    }

    // Called from the speaker output callback thread. Fills [dest, dest+numFrames)
    // with corrected mono monitor signal, or silence for whatever portion
    // isn't ready yet -- never blocks waiting for more input.
    void pullOutput(float* dest, int numFrames) {
        std::lock_guard<std::mutex> lock(fifoMutex_);
        int available = std::min(numFrames, static_cast<int>(outputFifo_.size()));
        for (int i = 0; i < available; i++) {
            dest[i] = outputFifo_.front();
            outputFifo_.pop_front();
        }
        std::fill(dest + available, dest + numFrames, 0.0f);
    }

private:
    // Caller must hold fifoMutex_. Uses overlapping Hann-windowed grains and
    // advances the input by hop rather than an entire grain. This is the key
    // upgrade from the Phase-4 prototype: the read position is continuous,
    // so the pitch shifter no longer restarts the resample at every grain.
    void processAvailableGrains() {
        while (static_cast<int>(inputFifo_.size()) >= grainFrames_) {
            std::vector<float> grain(inputFifo_.begin(), inputFifo_.begin() + grainFrames_);
            inputFifo_.erase(inputFifo_.begin(), inputFifo_.begin() + hopFrames_);

            PitchCorrectionParams localParams;
            {
                std::lock_guard<std::mutex> lock(paramsMutex_);
                localParams = params_;
            }

            auto detection = detectPitch(grain.data(), grainFrames_, sampleRate_);
            float targetShift = 0.0f;
            if (detection.voiced && localParams.strength > 0.0f) {
                float inputMidi = 69.0f + 12.0f * log2f(detection.frequencyHz / 440.0f);
                float targetFreq = nearestScaleFrequency(detection.frequencyHz, localParams.rootPitchClass, localParams.scaleMask);
                int targetMidi = static_cast<int>(std::round(69.0f + 12.0f * log2f(targetFreq / 440.0f)));
                if (stableMidi_ < 0 || std::fabs(inputMidi - targetMidi) < 0.43f ||
                    std::fabs(inputMidi - stableMidi_) > 0.72f) stableMidi_ = targetMidi;
                targetShift = (static_cast<float>(stableMidi_) - inputMidi) * localParams.strength;
            } else {
                stableMidi_ = -1;
                targetShift = 0.0f;
            }
            float smoothing = localParams.hardMode ? 0.0f : std::clamp(localParams.speedMs / 180.0f, 0.02f, 0.96f);
            smoothedSemitones_ = localParams.hardMode ? targetShift
                : smoothing * smoothedSemitones_ + (1.0f - smoothing) * targetShift;
            float ratio = std::clamp(powf(2.0f, smoothedSemitones_ / 12.0f), 0.5f, 2.0f);

            auto renderShifted = [&](float shiftRatio) {
                std::vector<float> shifted(grainFrames_, 0.0f);
                std::vector<float> coeff;
                bool formantOk = localParams.formantCompensation > 0.001f &&
                                 estimateLpc(grain.data(), grainFrames_, 12, coeff);
                if (formantOk) {
                    auto excitation = lpcInverseFilter(grain, coeff);
                    for (int i = 0; i < grainFrames_; ++i) {
                        float src = static_cast<float>(i) * shiftRatio;
                        if (src >= grainFrames_ - 1) src = static_cast<float>(grainFrames_ - 1);
                        int a = static_cast<int>(src);
                        float frac = src - static_cast<float>(a);
                        int b = std::min(a + 1, grainFrames_ - 1);
                        shifted[i] = excitation[a] + (excitation[b] - excitation[a]) * frac;
                    }
                    auto synthesized = lpcSynthesize(shifted, coeff);
                    float dry = std::clamp(1.0f - localParams.formantCompensation, 0.0f, 1.0f);
                    for (int i = 0; i < grainFrames_; ++i)
                        shifted[i] = dry * grain[i] + localParams.formantCompensation * synthesized[i];
                } else {
                    for (int i = 0; i < grainFrames_; ++i) {
                        float src = static_cast<float>(i) * shiftRatio;
                        if (src >= grainFrames_ - 1) src = static_cast<float>(grainFrames_ - 1);
                        int a = static_cast<int>(src);
                        float frac = src - static_cast<float>(a);
                        int b = std::min(a + 1, grainFrames_ - 1);
                        shifted[i] = grain[a] + (grain[b] - grain[a]) * frac;
                    }
                }
                return shifted;
            };

            auto leadShifted = renderShifted(ratio);
            std::vector<std::vector<float>> harmonyVoices;
            for (int offset : harmonyOffsets(localParams.harmonyMode, stableMidi_)) {
                float harmonySemitones = smoothedSemitones_ + static_cast<float>(offset) * localParams.strength;
                float harmonyRatio = std::clamp(powf(2.0f, harmonySemitones / 12.0f), 0.5f, 2.0f);
                harmonyVoices.push_back(renderShifted(harmonyRatio));
            }

            std::vector<float> mixed = leadShifted;
            if (!harmonyVoices.empty() && localParams.harmonyMix > 0.0f) {
                float perVoice = std::clamp(localParams.harmonyMix, 0.0f, 1.0f) /
                                 static_cast<float>(harmonyVoices.size());
                for (const auto& voice : harmonyVoices)
                    for (int i = 0; i < grainFrames_; ++i)
                        mixed[i] += voice[i] * perVoice;
                for (auto& x : mixed) x = std::clamp(x, -1.0f, 1.0f);
            }

            std::vector<float> hann(grainFrames_);
            for (int i = 0; i < grainFrames_; ++i)
                hann[i] = 0.5f - 0.5f * cosf(2.0f * static_cast<float>(M_PI) * i / (grainFrames_ - 1));

            for (int i = 0; i < grainFrames_; ++i) {
                olaBuffer_[i] += mixed[i] * hann[i];
                olaWindowSum_[i] += hann[i];
            }
            for (int i = 0; i < hopFrames_; ++i) {
                float denom = olaWindowSum_[i];
                outputFifo_.push_back(denom > 1e-5f ? olaBuffer_[i] / denom : 0.0f);
            }
            std::move(olaBuffer_.begin() + hopFrames_, olaBuffer_.end(), olaBuffer_.begin());
            std::move(olaWindowSum_.begin() + hopFrames_, olaWindowSum_.end(), olaWindowSum_.begin());
            std::fill(olaBuffer_.end() - hopFrames_, olaBuffer_.end(), 0.0f);
            std::fill(olaWindowSum_.end() - hopFrames_, olaWindowSum_.end(), 0.0f);

            while (outputFifo_.size() > static_cast<size_t>(sampleRate_)) outputFifo_.pop_front();
        }
    }

    int sampleRate_ = 48000;
    int grainFrames_ = 1024;
    int crossfadeFrames_ = 128;
    int hopFrames_ = 256;

    std::deque<float> inputFifo_;
    std::deque<float> outputFifo_;
    std::vector<float> prevGrainTail_;
    bool hasPrevGrain_ = false;
    float smoothedRatio_ = 1.0f;
    float smoothedSemitones_ = 0.0f;
    int stableMidi_ = -1;
    std::vector<float> olaBuffer_;
    std::vector<float> olaWindowSum_;
    std::mutex fifoMutex_;

    std::atomic<bool> enabled_{false};
    std::atomic<float> monitorVolumeDb_{0.0f};
    PitchCorrectionParams params_{};
    std::mutex paramsMutex_;
};

} // namespace almus::dsp
