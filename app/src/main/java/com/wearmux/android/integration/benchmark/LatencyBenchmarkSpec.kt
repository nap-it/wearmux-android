package com.wearmux.android.integration.benchmark

import com.wearmux.android.integration.CAMERA_QUALITY_FACTOR_MAX
import com.wearmux.android.integration.CAMERA_QUALITY_FACTOR_MIN
import com.wearmux.android.integration.CAMERA_RATE_MAX_MS
import com.wearmux.android.integration.CAMERA_RESOLUTION_MAX
import com.wearmux.android.integration.CAMERA_RESOLUTION_MIN
import com.wearmux.android.integration.SENSOR_RATE_STEP_MS

data class LatencyBenchmarkScenario(
    val resolution: Int,
    val qualityFactor: Int,
    val cameraRateMs: Int,
    val frames: Int,
) {
    val label: String
        get() = "res=${resolution} qf=${qualityFactor} rate=${cameraRateMs}ms frames=${frames}"
}

data class LatencyBenchmarkMatrixParseResult(
    val scenarios: List<LatencyBenchmarkScenario>,
    val errors: List<String>,
)

data class LatencyBenchmarkAutoMatrixResult(
    val matrixText: String,
    val scenarioCount: Int,
    val errors: List<String>,
)

data class LatencyStatsSummary(
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val stdDev: Double,
    val median: Double,
    val p90: Double,
)

fun parseLatencyBenchmarkMatrix(rawText: String): LatencyBenchmarkMatrixParseResult {
    val scenarios = mutableListOf<LatencyBenchmarkScenario>()
    val errors = mutableListOf<String>()
    val lines = rawText.lines()

    lines.forEachIndexed { index, line ->
        val lineNo = index + 1
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed

        val parts = trimmed.split(",").map { it.trim() }
        if (parts.size != 4) {
            errors += "Line $lineNo: use the format 'res,qf,rate,frames'."
            return@forEachIndexed
        }

        val resolution = parts[0].toIntOrNull()
        val qualityFactor = parts[1].toIntOrNull()
        val cameraRateMs = parts[2].toIntOrNull()
        val frames = parts[3].toIntOrNull()
        if (resolution == null || qualityFactor == null || cameraRateMs == null || frames == null) {
            errors += "Line $lineNo: all values must be integers."
            return@forEachIndexed
        }

        if (resolution !in CAMERA_RESOLUTION_MIN..CAMERA_RESOLUTION_MAX) {
            errors += "Line $lineNo: resolution out of range $CAMERA_RESOLUTION_MIN-$CAMERA_RESOLUTION_MAX."
        }
        if (qualityFactor !in CAMERA_QUALITY_FACTOR_MIN..CAMERA_QUALITY_FACTOR_MAX) {
            errors += "Line $lineNo: quality factor out of range $CAMERA_QUALITY_FACTOR_MIN-$CAMERA_QUALITY_FACTOR_MAX."
        }
        if (cameraRateMs !in SENSOR_RATE_STEP_MS..CAMERA_RATE_MAX_MS) {
            errors += "Line $lineNo: camera rate out of range $SENSOR_RATE_STEP_MS-$CAMERA_RATE_MAX_MS ms."
        } else if (cameraRateMs % SENSOR_RATE_STEP_MS != 0) {
            errors += "Line $lineNo: camera rate must be a multiple of $SENSOR_RATE_STEP_MS ms."
        }
        if (frames <= 0) {
            errors += "Line $lineNo: frames must be > 0."
        }

        if (errors.none { it.startsWith("Line $lineNo:") }) {
            scenarios += LatencyBenchmarkScenario(
                resolution = resolution,
                qualityFactor = qualityFactor,
                cameraRateMs = cameraRateMs,
                frames = frames,
            )
        }
    }

    if (scenarios.isEmpty() && errors.isEmpty()) {
        errors += "The matrix is empty. Add at least one scenario."
    }

    return LatencyBenchmarkMatrixParseResult(
        scenarios = scenarios,
        errors = errors,
    )
}

fun buildAutoLatencyBenchmarkMatrix(
    startResolution: Int,
    cameraRateMs: Int? = null,
    frames: Int,
    maxResolution: Int = CAMERA_RESOLUTION_MAX,
    resolutionStep: Int = 32,
    qualityFactors: List<Int> = listOf(10, 25, 50, 74),
    cameraRatesMs: List<Int> = emptyList(),
): LatencyBenchmarkAutoMatrixResult {
    val errors = mutableListOf<String>()
    val boundedMaxResolution = maxResolution.coerceIn(
        CAMERA_RESOLUTION_MIN,
        CAMERA_RESOLUTION_MAX
    )

    if (startResolution !in CAMERA_RESOLUTION_MIN..boundedMaxResolution) {
        errors += "The initial resolution must be between $CAMERA_RESOLUTION_MIN and $boundedMaxResolution."
    }
    if (frames <= 0) {
        errors += "Frames per configuration must be > 0."
    }
    if (resolutionStep <= 0) {
        errors += "The resolution step must be > 0."
    }
    if (resolutionStep > 0 && startResolution % resolutionStep != 0) {
        errors += "The initial resolution must be a multiple of $resolutionStep."
    }

    val normalizedQf = qualityFactors
        .distinct()
        .filter { it in CAMERA_QUALITY_FACTOR_MIN..CAMERA_QUALITY_FACTOR_MAX }
    if (normalizedQf.isEmpty()) {
        errors += "There are no valid quality factors to generate the matrix."
    }

    val requestedCameraRates = when {
        cameraRatesMs.isNotEmpty() -> cameraRatesMs
        cameraRateMs != null -> listOf(cameraRateMs)
        else -> listOf(20)
    }
    val normalizedCameraRates = requestedCameraRates
        .distinct()
        .filter { it in SENSOR_RATE_STEP_MS..CAMERA_RATE_MAX_MS && it % SENSOR_RATE_STEP_MS == 0 }
    if (normalizedCameraRates.isEmpty()) {
        errors += "There are no valid camera rates to generate the matrix."
    }

    if (errors.isNotEmpty()) {
        return LatencyBenchmarkAutoMatrixResult(
            matrixText = "",
            scenarioCount = 0,
            errors = errors,
        )
    }

    val resolutions = linkedSetOf<Int>()
    var current = startResolution
    while (current <= boundedMaxResolution) {
        resolutions += current
        current += resolutionStep
    }
    resolutions += boundedMaxResolution

    val lines = mutableListOf<String>()
    lines += "# res,qf,rate,frames"
    resolutions.forEach { resolution ->
        normalizedQf.forEach { qf ->
            normalizedCameraRates.forEach { rateMs ->
                lines += "$resolution,$qf,$rateMs,$frames"
            }
        }
    }

    return LatencyBenchmarkAutoMatrixResult(
        matrixText = lines.joinToString("\n"),
        scenarioCount = resolutions.size * normalizedQf.size * normalizedCameraRates.size,
        errors = emptyList(),
    )
}

fun summarizeValues(values: List<Double>): LatencyStatsSummary? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val count = sorted.size
    val mean = sorted.sum() / count.toDouble()
    val variance = sorted.sumOf { (it - mean) * (it - mean) } / count.toDouble()
    return LatencyStatsSummary(
        count = count,
        min = sorted.first(),
        max = sorted.last(),
        mean = mean,
        stdDev = kotlin.math.sqrt(variance),
        median = percentile(sorted, 0.5),
        p90 = percentile(sorted, 0.9),
    )
}

private fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val rank = (sorted.size - 1) * p
    val lower = rank.toInt()
    val upper = kotlin.math.ceil(rank).toInt()
    if (lower == upper) return sorted[lower]
    val weight = rank - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}
