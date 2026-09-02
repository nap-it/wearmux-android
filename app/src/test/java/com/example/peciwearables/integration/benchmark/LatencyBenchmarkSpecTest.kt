package com.example.peciwearables.integration.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyBenchmarkSpecTest {

    @Test
    fun parseMatrix_acceptsValidLinesAndComments() {
        val input = """
            # res,qf,rate,frames
            480,30,20,50

            640,50,20,10
        """.trimIndent()

        val result = parseLatencyBenchmarkMatrix(input)

        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.scenarios.size)
        assertEquals(480, result.scenarios[0].resolution)
        assertEquals(30, result.scenarios[0].qualityFactor)
        assertEquals(20, result.scenarios[0].cameraRateMs)
        assertEquals(50, result.scenarios[0].frames)
    }

    @Test
    fun parseMatrix_reportsValidationErrors() {
        val input = """
            abc,20,20,10
            320,999,20,10
            640,50,22,0
            960,40
        """.trimIndent()

        val result = parseLatencyBenchmarkMatrix(input)

        assertTrue(result.scenarios.isEmpty())
        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors.any { it.contains("Linha 1") })
        assertTrue(result.errors.any { it.contains("Linha 2") })
        assertTrue(result.errors.any { it.contains("Linha 3") })
        assertTrue(result.errors.any { it.contains("Linha 4") })
    }

    @Test
    fun summarizeValues_calculatesStats() {
        val summary = summarizeValues(listOf(10.0, 20.0, 30.0, 40.0, 50.0))

        assertNotNull(summary)
        summary!!
        assertEquals(5, summary.count)
        assertEquals(10.0, summary.min, 0.0001)
        assertEquals(50.0, summary.max, 0.0001)
        assertEquals(30.0, summary.mean, 0.0001)
        assertEquals(30.0, summary.median, 0.0001)
        assertEquals(46.0, summary.p90, 0.0001)
    }

    @Test
    fun summarizeValues_returnsNullForEmptyInput() {
        assertNull(summarizeValues(emptyList()))
    }

    @Test
    fun buildAutoMatrix_generatesExpectedGrid() {
        val result = buildAutoLatencyBenchmarkMatrix(
            startResolution = 480,
            cameraRateMs = 20,
            frames = 15,
            maxResolution = 1600,
            resolutionStep = 480,
            qualityFactors = listOf(10, 25)
        )

        assertTrue(result.errors.isEmpty())
        val lines = result.matrixText.lines()
        assertEquals("# res,qf,rate,frames", lines.first())
        assertEquals(8, result.scenarioCount)
        assertTrue(lines.contains("480,10,20,15"))
        assertTrue(lines.contains("960,25,20,15"))
        assertTrue(lines.contains("1600,10,20,15"))
    }

    @Test
    fun buildAutoMatrix_returnsErrorsForInvalidInput() {
        val result = buildAutoLatencyBenchmarkMatrix(
            startResolution = 2000,
            cameraRateMs = 22,
            frames = 0,
            resolutionStep = 0
        )

        assertTrue(result.matrixText.isEmpty())
        assertEquals(0, result.scenarioCount)
        assertFalse(result.errors.isEmpty())
    }

    @Test
    fun buildAutoMatrix_respectsConfiguredFinalResolution() {
        val result = buildAutoLatencyBenchmarkMatrix(
            startResolution = 480,
            cameraRateMs = 20,
            frames = 10,
            maxResolution = 960,
            resolutionStep = 240,
            qualityFactors = listOf(10),
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(3, result.scenarioCount)

        val lines = result.matrixText.lines()
        assertTrue(lines.contains("480,10,20,10"))
        assertTrue(lines.contains("720,10,20,10"))
        assertTrue(lines.contains("960,10,20,10"))
        assertFalse(lines.any { it.startsWith("1200,") || it.startsWith("1440,") })
    }

    @Test
    fun buildAutoMatrix_supportsCustomQualityAndCameraRateLists() {
        val result = buildAutoLatencyBenchmarkMatrix(
            startResolution = 480,
            frames = 12,
            maxResolution = 960,
            resolutionStep = 240,
            qualityFactors = listOf(10, 24, 30, 50),
            cameraRatesMs = listOf(10, 20),
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(24, result.scenarioCount)

        val lines = result.matrixText.lines()
        assertTrue(lines.contains("480,10,20,12"))
        assertTrue(lines.contains("480,24,10,12"))
        assertTrue(lines.contains("960,50,20,12"))
        assertTrue(lines.contains("960,30,10,12"))
    }
}
