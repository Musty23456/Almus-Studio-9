package com.almus.studio.audio

import com.almus.studio.data.EffectSettings
import com.almus.studio.data.EffectType

/**
 * Mirrors the native `EffectChain` struct (effect_chain.h) as a Kotlin value
 * type so the ViewModel/UI can work with named fields instead of a 20-argument
 * function call. [pushTo] is the only place that argument list appears.
 */
data class EffectChainParams(
    val eqEnabled: Boolean = false,
    val eqLowGainDb: Float = 0f,
    val eqMidGainDb: Float = 0f,
    val eqHighGainDb: Float = 0f,

    val highPassEnabled: Boolean = false,
    val highPassCutoffHz: Float = 80f,

    val lowPassEnabled: Boolean = false,
    val lowPassCutoffHz: Float = 12000f,

    val compressorEnabled: Boolean = false,
    val compressorThresholdDb: Float = -18f,
    val compressorRatio: Float = 4f,
    val compressorAttackMs: Float = 10f,
    val compressorReleaseMs: Float = 100f,

    val noiseGateEnabled: Boolean = false,
    val noiseGateThresholdDb: Float = -45f,
    val noiseGateAttackMs: Float = 5f,
    val noiseGateReleaseMs: Float = 80f,

    val deEsserEnabled: Boolean = false,
    val deEsserThresholdDb: Float = -28f,
    val deEsserReductionDb: Float = 6f,

    val delayEnabled: Boolean = false,
    val delayTimeMs: Float = 300f,
    val delayFeedback: Float = 0.3f,
    val delayMix: Float = 0.25f,

    val reverbEnabled: Boolean = false,
    val reverbRoomSize: Float = 0.5f,
    val reverbMix: Float = 0.2f
) {
    fun pushTo(trackHandle: Int) {
        AudioEngine.setEffectChain(
            trackHandle,
            eqEnabled, eqLowGainDb, eqMidGainDb, eqHighGainDb,
            highPassEnabled, highPassCutoffHz,
            lowPassEnabled, lowPassCutoffHz,
            compressorEnabled, compressorThresholdDb, compressorRatio, compressorAttackMs, compressorReleaseMs,
            noiseGateEnabled, noiseGateThresholdDb, noiseGateAttackMs, noiseGateReleaseMs,
            deEsserEnabled, deEsserThresholdDb, deEsserReductionDb,
            delayEnabled, delayTimeMs, delayFeedback, delayMix,
            reverbEnabled, reverbRoomSize, reverbMix
        )
    }

    companion object {
        /** Builds engine parameters from the project's persisted per-track effect list. */
        fun fromEffectSettings(effects: List<EffectSettings>): EffectChainParams {
            fun find(type: EffectType) = effects.find { it.type == type }
            val eq = find(EffectType.EQ3)
            val hp = find(EffectType.HIGH_PASS)
            val lp = find(EffectType.LOW_PASS)
            val comp = find(EffectType.COMPRESSOR)
            val gate = find(EffectType.NOISE_GATE)
            val deEsser = find(EffectType.DE_ESSER)
            val delay = find(EffectType.DELAY)
            val reverb = find(EffectType.REVERB)
            val defaults = EffectChainParams()

            return EffectChainParams(
                eqEnabled = eq?.enabled ?: false,
                eqLowGainDb = eq?.params?.get("lowGainDb") ?: defaults.eqLowGainDb,
                eqMidGainDb = eq?.params?.get("midGainDb") ?: defaults.eqMidGainDb,
                eqHighGainDb = eq?.params?.get("highGainDb") ?: defaults.eqHighGainDb,

                highPassEnabled = hp?.enabled ?: false,
                highPassCutoffHz = hp?.params?.get("cutoffHz") ?: defaults.highPassCutoffHz,

                lowPassEnabled = lp?.enabled ?: false,
                lowPassCutoffHz = lp?.params?.get("cutoffHz") ?: defaults.lowPassCutoffHz,

                compressorEnabled = comp?.enabled ?: false,
                compressorThresholdDb = comp?.params?.get("thresholdDb") ?: defaults.compressorThresholdDb,
                compressorRatio = comp?.params?.get("ratio") ?: defaults.compressorRatio,
                compressorAttackMs = comp?.params?.get("attackMs") ?: defaults.compressorAttackMs,
                compressorReleaseMs = comp?.params?.get("releaseMs") ?: defaults.compressorReleaseMs,

                noiseGateEnabled = gate?.enabled ?: false,
                noiseGateThresholdDb = gate?.params?.get("thresholdDb") ?: defaults.noiseGateThresholdDb,
                noiseGateAttackMs = gate?.params?.get("attackMs") ?: defaults.noiseGateAttackMs,
                noiseGateReleaseMs = gate?.params?.get("releaseMs") ?: defaults.noiseGateReleaseMs,

                deEsserEnabled = deEsser?.enabled ?: false,
                deEsserThresholdDb = deEsser?.params?.get("thresholdDb") ?: defaults.deEsserThresholdDb,
                deEsserReductionDb = deEsser?.params?.get("reductionDb") ?: defaults.deEsserReductionDb,

                delayEnabled = delay?.enabled ?: false,
                delayTimeMs = delay?.params?.get("timeMs") ?: defaults.delayTimeMs,
                delayFeedback = delay?.params?.get("feedback") ?: defaults.delayFeedback,
                delayMix = delay?.params?.get("mix") ?: defaults.delayMix,

                reverbEnabled = reverb?.enabled ?: false,
                reverbRoomSize = reverb?.params?.get("roomSize") ?: defaults.reverbRoomSize,
                reverbMix = reverb?.params?.get("mix") ?: defaults.reverbMix
            )
        }

        /** Converts back to the persisted list, preserving only the six Phase 2 effect types. */
        fun EffectChainParams.toEffectSettings(): List<EffectSettings> = listOf(
            EffectSettings(EffectType.EQ3, eqEnabled, mapOf("lowGainDb" to eqLowGainDb, "midGainDb" to eqMidGainDb, "highGainDb" to eqHighGainDb)),
            EffectSettings(EffectType.HIGH_PASS, highPassEnabled, mapOf("cutoffHz" to highPassCutoffHz)),
            EffectSettings(EffectType.LOW_PASS, lowPassEnabled, mapOf("cutoffHz" to lowPassCutoffHz)),
            EffectSettings(EffectType.COMPRESSOR, compressorEnabled, mapOf(
                "thresholdDb" to compressorThresholdDb, "ratio" to compressorRatio,
                "attackMs" to compressorAttackMs, "releaseMs" to compressorReleaseMs
            )),
            EffectSettings(EffectType.NOISE_GATE, noiseGateEnabled, mapOf("thresholdDb" to noiseGateThresholdDb, "attackMs" to noiseGateAttackMs, "releaseMs" to noiseGateReleaseMs)),
            EffectSettings(EffectType.DE_ESSER, deEsserEnabled, mapOf("thresholdDb" to deEsserThresholdDb, "reductionDb" to deEsserReductionDb)),
            EffectSettings(EffectType.DELAY, delayEnabled, mapOf("timeMs" to delayTimeMs, "feedback" to delayFeedback, "mix" to delayMix)),
            EffectSettings(EffectType.REVERB, reverbEnabled, mapOf("roomSize" to reverbRoomSize, "mix" to reverbMix))
        )
    }
}
