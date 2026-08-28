package com.calisthenics.diagnostic.export

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidStorageRootsProbeLayoutTest {
    @Test
    fun probeLayoutIsBelowTheHarnessParentButOutsideEveryPurgeableRoot() {
        val production = productionRoots()

        val probe = AndroidStorageRoots.forProbe(production, "11111111-1111-4111-8111-111111111111")

        assertTrue(probe.diagnosticHarnessRoot.startsWith("${production.diagnosticHarnessRoot}/"))
        assertFalse(
            probe.diagnosticHarnessRoot.startsWith("${production.stagingRoot}/"),
            "a probe nested under staging would be traversed by a staging purge",
        )
        assertTrue(probe.registeredAndroidRoots.all { it.startsWith("${probe.diagnosticHarnessRoot}/") })
    }

    @Test
    fun storageRootsRejectADiagnosticHarnessParentNestedInAnyRegisteredPurgeRoot() {
        val production = productionRoots()
        val nestedBase = "${production.stagingRoot}/storage-probe/11111111-1111-4111-8111-111111111111"

        assertFailsWith<IllegalArgumentException> {
            StorageRoots(
                filesDir = production.filesDir,
                diagnosticHarnessRoot = nestedBase,
                stagingRoot = "$nestedBase/staging",
                exportsRoot = "$nestedBase/exports",
                transferStagingRoot = "$nestedBase/transfer-staging",
                tombstoneRoot = "$nestedBase/tombstones",
            )
        }
    }

    @Test
    fun storageRootsRejectADiagnosticHarnessParentNestedInTheTombstoneRoot() {
        val production = productionRoots()
        val nestedBase = "${production.tombstoneRoot}/storage-probe/11111111-1111-4111-8111-111111111111"

        assertFailsWith<IllegalArgumentException> {
            StorageRoots(
                filesDir = production.filesDir,
                diagnosticHarnessRoot = nestedBase,
                stagingRoot = "$nestedBase/staging",
                exportsRoot = "$nestedBase/exports",
                transferStagingRoot = "$nestedBase/transfer-staging",
                tombstoneRoot = "$nestedBase/tombstones",
            )
        }
    }

    private fun productionRoots() = StorageRoots(
        filesDir = "/files",
        diagnosticHarnessRoot = "/files/diagnostic-harness",
        stagingRoot = "/files/diagnostic-harness/staging",
        exportsRoot = "/files/diagnostic-harness/exports",
        transferStagingRoot = "/files/diagnostic-harness/transfer-staging",
        tombstoneRoot = "/files/diagnostic-harness/tombstones",
    )
}
