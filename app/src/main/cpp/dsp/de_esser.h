#pragma once
#include <algorithm>
#include <cmath>

namespace almus::dsp {
class DeEsser {
public:
    void process(float left, float right, float thresholdDb, float reductionDb,
                 int sampleRate, float* outLeft, float* outRight) {
        // Lightweight vocal sibilance detector: a smoothed high-frequency proxy
        // from sample-to-sample change. It is intentionally conservative and
        // mono-linked so the stereo image stays stable.
        const float hfL = std::fabs(left - prevL_);
        const float hfR = std::fabs(right - prevR_);
        prevL_ = left; prevR_ = right;
        const float hf = std::max(hfL, hfR);
        const float db = hf <= 0.0f ? -96.0f : 20.0f * std::log10(hf);
        const float target = db > thresholdDb ? -std::min(reductionDb, 18.0f) : 0.0f;
        const float coeff = std::exp(-1.0f / (0.008f * std::max(8000, sampleRate)));
        reductionDb_ = coeff * reductionDb_ + (1.0f - coeff) * target;
        const float gain = std::pow(10.0f, reductionDb_ / 20.0f);
        *outLeft = left * gain;
        *outRight = right * gain;
    }
private:
    float prevL_ = 0.0f, prevR_ = 0.0f, reductionDb_ = 0.0f;
};
}
