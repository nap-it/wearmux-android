package com.example.peciwearables.integration.protocol

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Acumula amostras de quaternion dos óculos: a primeira amostra fixa a baseline (identidade),
 * as seguintes ficam relativas à baseline. Encapsula ~10 linhas + 2 fields que viviam no service.
 */
class GlassesQuaternionTracker(
    private val latestQuaternion: MutableStateFlow<FloatArray?>,
) {
    @Volatile var baseline: FloatArray? = null; private set
    @Volatile var latestMs: Long = 0L; private set

    fun onSample(quat: FloatArray) {
        val normalized = QuaternionMath.normalize(quat) ?: return
        val b = baseline
        if (b == null) {
            baseline = normalized
            latestQuaternion.value = QuaternionMath.IDENTITY
        } else {
            latestQuaternion.value = QuaternionMath.relative(b, normalized)
        }
        latestMs = System.currentTimeMillis()
    }

    fun reset() {
        baseline = null; latestMs = 0L; latestQuaternion.value = null
    }
}
