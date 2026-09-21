#pragma once
#include <algorithm>
#include <cmath>

namespace almus::dsp {
class NoiseGate {
public:
    void process(float left, float right, float thresholdDb, float attackMs, float releaseMs,
                 int sampleRate, float* outLeft, float* outRight) {
        const float peak = std::max(std::fabs(left), std::fabs(right));
        const float inputDb = peak <= 0.0f ? -96.0f : 20.0f * std::log10(peak);
        const float target = inputDb < thresholdDb ? -1.0f : 0.0f;
        const float ms = target < gainDb_ ? std::max(0.1f, attackMs) : std::max(1.0f, releaseMs);
        const float coeff = std::exp(-1.0f / ((ms * 0.001f) * sampleRate));
        gainDb_ = coeff * gainDb_ + (1.0f - coeff) * target;
        const float gain = std::pow(10.0f, gainDb_ / 20.0f);
        *outLeft = left * gain;
        *outRight = right * gain;
    }
private:
    float gainDb_ = 0.0f;
};
}
