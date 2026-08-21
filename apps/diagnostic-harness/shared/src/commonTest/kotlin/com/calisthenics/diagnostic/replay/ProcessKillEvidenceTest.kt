package com.calisthenics.diagnostic.replay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun environment(
    deviceClass: String = "physical_device",
    manufacturer: String = "samsung",
    model: String = "SM-S938B",
    marketingName: String = "",
) = ProcessKillEnvironment(
    deviceClass = deviceClass,
    deviceIdentity = "samsung/SM-S938B/pa3q/pa3qxins",
    deviceFingerprint = "samsung/pa3qxins/pa3q:16/BP4A.251205.006/x:user/release-keys",
    apiLevel = 36,
    androidRelease = "16",
    abi = "arm64-v8a",
    manufacturer = manufacturer,
    model = model,
    marketingName = marketingName,
)

private fun observation(
    processCrashConfirmed: Boolean = true,
    exportRootCorruptions: List<String> = emptyList(),
    stagingResidueCount: Int = 1,
    stagingResidueClassification: String = "CrashResidue",
) = ProcessKillObservation(
    killedAtOperation = "destinationExists",
    processCrashConfirmed = processCrashConfirmed,
    exportRootCorruptions = exportRootCorruptions,
    stagingResidueCount = stagingResidueCount,
    stagingResidueClassification = stagingResidueClassification,
)

private fun render(
    env: ProcessKillEnvironment = environment(),
    obs: ProcessKillObservation = observation(),
) = renderProcessKillEvidence(
    ProcessKillEvidence(
        emittedBy = "com.calisthenics.diagnostic.export.ProcessKillDuringExportTest",
        environment = env,
        sessionId = "00000000-0000-4000-8000-000000000001",
        observation = obs,
    ),
)

private const val GATE_TRUE = """"satisfies_s25_process_kill_gate":true"""
private const val GATE_FALSE = """"satisfies_s25_process_kill_gate":false"""

class ProcessKillEvidenceTest {
    @Test
    fun aCrashWithNoPartialEntryAndCrashResidueSatisfiesTheGate() {
        assertTrue(render().contains(GATE_TRUE))
    }

    @Test
    fun authorizesTheS25UltraByModelWhenMarketingNameIsAbsent() {
        assertTrue(render(env = environment(marketingName = "", model = "SM-S938B")).contains(GATE_TRUE))
    }

    @Test
    fun anUnconfirmedCrashNeverSatisfiesTheGate() {
        // Without a real process death every other condition is trivially true, and the gate
        // would certify crash safety it never tested.
        assertTrue(render(obs = observation(processCrashConfirmed = false)).contains(GATE_FALSE))
    }

    @Test
    fun aPartialEntryUnderTheExportsRootNeverSatisfiesTheGate() {
        assertTrue(
            render(obs = observation(exportRootCorruptions = listOf("exports/abcd"))).contains(GATE_FALSE),
        )
    }

    @Test
    fun abortMarkerResidueNeverSatisfiesTheGate() {
        // A killed process cannot have written a deliberate abort marker; classifying its residue
        // as Abandoned would mean the kill did not happen where the test believes it did.
        assertTrue(
            render(obs = observation(stagingResidueClassification = "Abandoned")).contains(GATE_FALSE),
        )
    }

    @Test
    fun anUnexpectedResidueCountNeverSatisfiesTheGate() {
        assertTrue(render(obs = observation(stagingResidueCount = 0)).contains(GATE_FALSE))
        assertTrue(render(obs = observation(stagingResidueCount = 2)).contains(GATE_FALSE))
    }

    @Test
    fun anEmulatorNeverSatisfiesTheGate() {
        assertTrue(render(env = environment(deviceClass = "emulator")).contains(GATE_FALSE))
    }

    @Test
    fun anotherSamsungPhysicalDeviceNeverSatisfiesTheGate() {
        assertTrue(render(env = environment(model = "SM-S931B")).contains(GATE_FALSE))
    }

    @Test
    fun theRecordIsSanitizedAndCarriesNoLandmarkCoordinates() {
        val rendered = render()
        assertTrue(rendered.contains(""""sanitized":true"""))
        assertTrue(rendered.contains(""""contains_landmark_coordinates":false"""))
        assertTrue(rendered.contains(PROCESS_KILL_RECORD_KIND))
        assertFalse(rendered.contains("landmark_x"))
    }
}
