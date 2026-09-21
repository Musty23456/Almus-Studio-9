#pragma once

#include <cmath>
#include <cstdint>

namespace almus::dsp {

// Purely a function of absolute timeline position (playheadStart + i) modulo
// the beat length -- no persisted "did we already trigger this beat" state
// needed, which also makes it correct immediately after a seek. Downbeats
// (beat 1 of the bar) get a higher pitch and slightly louder click, matching
// what every hardware metronome does.
inline void renderMetronomeClick(float* interleavedStereo, int32_t numFrames,
                                  int64_t playheadStart, int sampleRate,
                                  float bpm, int beatsPerBar) {
    if (bpm <= 0.0f || sampleRate <= 0) return;
    int64_t beatFrames = static_cast<int64_t>(sampleRate * 60.0f / bpm);
    if (beatFrames <= 0) return;
    int64_t clickDurationFrames = static_cast<int64_t>(sampleRate * 0.02f); // 20ms

    for (int32_t i = 0; i < numFrames; i++) {
        int64_t absoluteFrame = playheadStart + i;
        int64_t framesSinceBeat = absoluteFrame % beatFrames;
        if (framesSinceBeat >= clickDurationFrames) continue;

        int64_t beatIndex = absoluteFrame / beatFrames;
        bool isDownbeat = beatsPerBar > 0 && (beatIndex % beatsPerBar) == 0;
        float freqHz = isDownbeat ? 1500.0f : 1000.0f;
        float clickGain = isDownbeat ? 0.5f : 0.35f;

        float t = static_cast<float>(framesSinceBeat) / sampleRate;
        float envelope = expf(-t * 60.0f); // fast decay, ~20ms tick
        float sample = sinf(2.0f * static_cast<float>(M_PI) * freqHz * t) * envelope * clickGain;

        interleavedStereo[i * 2] += sample;
        interleavedStereo[i * 2 + 1] += sample;
    }
}

} // namespace almus::dsp
