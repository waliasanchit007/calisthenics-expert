package com.calisthenics.diagnostic.contract

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against a future locale-dependent regression in canonical number
 * formatting. `Double.toString()` is JLS-specified to be locale-invariant, so
 * this test is expected to stay green without any production change; if it
 * ever goes red, it has found the actual locale-dependent call site.
 */
class LocaleIndependentCanonicalNumberTest {
    private val originalDefaultLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale)
    }

    @Test
    fun canonicalNumberFormatIsLocaleIndependentAcrossRfc8785Vectors() {
        val inputs = loadNumberVectorInputs()

        val ambient = inputs.map { CanonicalNumber.format(it).toByteArray(Charsets.UTF_8) }

        Locale.setDefault(Locale.GERMANY)
        val germany = inputs.map { CanonicalNumber.format(it).toByteArray(Charsets.UTF_8) }
        assertByteArraysEqual(ambient, germany)

        Locale.setDefault(Locale.forLanguageTag("ar-SA"))
        val arabic = inputs.map { CanonicalNumber.format(it).toByteArray(Charsets.UTF_8) }
        assertByteArraysEqual(ambient, arabic)
    }

    @Test
    fun manifestAndFrameEncodingAreLocaleIndependent() {
        val manifest = localeVectorManifest()
        val frame = localeVectorFrame()

        val ambientManifest = encodeManifest(manifest).toByteArray(Charsets.UTF_8)
        val ambientFrame = encodeFrameLine(frame).toByteArray(Charsets.UTF_8)

        Locale.setDefault(Locale.GERMANY)
        assertTrue(ambientManifest.contentEquals(encodeManifest(manifest).toByteArray(Charsets.UTF_8)))
        assertTrue(ambientFrame.contentEquals(encodeFrameLine(frame).toByteArray(Charsets.UTF_8)))

        Locale.setDefault(Locale.forLanguageTag("ar-SA"))
        assertTrue(ambientManifest.contentEquals(encodeManifest(manifest).toByteArray(Charsets.UTF_8)))
        assertTrue(ambientFrame.contentEquals(encodeFrameLine(frame).toByteArray(Charsets.UTF_8)))
    }

    private fun assertByteArraysEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertTrue(expected.size == actual.size, "vector count changed between locale states")
        expected.indices.forEach { index ->
            assertTrue(expected[index].contentEquals(actual[index]), "vector $index diverged across locales")
        }
    }

    private fun loadNumberVectorInputs(): List<Double> {
        val stream = requireNotNull(
            javaClass.classLoader.getResourceAsStream("contract/number-vectors.json"),
        ) { "contract/number-vectors.json fixture not found on the test classpath" }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Json.parseToJsonElement(text).jsonArray.map { element ->
            element.jsonObject.getValue("input").jsonPrimitive.content.toDouble()
        }
    }

    private fun localeVectorManifest(): SessionManifest = SessionManifest(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("00000000-0000-4000-8000-000000000042"),
        sessionKind = "fixture_replay",
        participantPseudonymId = "locale-vector-pseudonym",
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "b".repeat(40),
        device = Device("jvm", "test", "locale-vector-device", null, false),
        posePipeline = PosePipeline(
            "fake",
            "1",
            "model",
            Sha256("0".repeat(64)),
            "cpu",
            "video",
            1,
            0.5,
            0.5,
            0.5,
            "fake",
        ),
        camera = Camera("back", 640, 480, 0, false, 29.970029970029972, null, null, null, null, "monotonic", "fake"),
        modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), null),
        inferenceDrainTimeoutMs = 1000,
        cadence = Cadence(15.333333333333334, emptyList(), null),
        clock = Clock("monotonic", 0, "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z"),
        availableObservations = AvailableObservations(true, false),
        mediaReferences = listOf(MediaReference("fixture", "fixture.rgb24", Sha256("0".repeat(64)), null, null)),
        counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "known", false, null),
        studyContext = emptyMap(),
    )

    private fun localeVectorFrame(): RawPoseFrame = RawPoseFrame(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid("00000000-0000-4000-8000-000000000042"),
        frameIndex = 0,
        captureTimestampNs = 1_000_000,
        backendTimestampMs = 1,
        inferenceStartNs = 2,
        inferenceEndNs = 3,
        posePresent = true,
        normalizedImageLandmarks = listOf(
            Landmark(0, "nose", 987654321.123456789, 333333333.33333329, 0.000001, 0.912345, 0.812345),
        ),
        worldLandmarks = null,
        imageWidth = 640,
        imageHeight = 480,
        framesSkippedSinceLastEmittedRecord = 0,
    )
}
