#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace almus::dsp {

// Lightweight true-peak-safe-ish master limiter foundation. It is intentionally
// allocation-free and suitable for the Oboe callback. It uses a smoothed gain
// envelope rather than a look-ahead buffer, keeping latency at zero frames.
class MasterLimiter {
public:
    void init(int sampleRate) { sampleRate_ = std::max(8000, sampleRate); gain_ = 1.0f; }

    void setCeilingDb(float db) { ceilingDb_ = std::clamp(db, -12.0f, -0.1f); }
    void setReleaseMs(float ms) { releaseMs_ = std::clamp(ms, 5.0f, 1000.0f); }
    float ceilingDb() const { return ceilingDb_; }

    void process(float* stereo, int32_t frames) {
        const float ceiling = std::pow(10.0f, ceilingDb_ / 20.0f);
        const float releaseCoeff = std::exp(-1.0f / (std::max(1.0f, releaseMs_ * 0.001f) * sampleRate_));
        for (int32_t i = 0; i < frames; ++i) {
            const float l = stereo[i * 2];
            const float r = stereo[i * 2 + 1];
            const float peak = std::max(std::fabs(l), std::fabs(r));
            const float target = peak > ceiling ? ceiling / peak : 1.0f;
            if (target < gain_) {
                // Fast attack prevents the limiter from allowing an over.
                gain_ = target;
            } else {
                gain_ = target + (gain_ - target) * releaseCoeff;
            }
            stereo[i * 2] = l * gain_;
            stereo[i * 2 + 1] = r * gain_;
        }
    }

private:
    int sampleRate_ = 48000;
    float ceilingDb_ = -1.0f;
    float releaseMs_ = 80.0f;
    float gain_ = 1.0f;
};

} // namespace almus::dsp
