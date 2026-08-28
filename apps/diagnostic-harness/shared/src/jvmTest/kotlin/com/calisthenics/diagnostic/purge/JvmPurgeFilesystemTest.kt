package com.calisthenics.diagnostic.purge

import com.calisthenics.diagnostic.contract.AvailableObservations
import com.calisthenics.diagnostic.contract.Cadence
import com.calisthenics.diagnostic.contract.Camera
import com.calisthenics.diagnostic.contract.CaptureQualityObserved
import com.calisthenics.diagnostic.contract.Clock
import com.calisthenics.diagnostic.contract.Counts
import com.calisthenics.diagnostic.contract.Device
import com.calisthenics.diagnostic.contract.ModelInput
import com.calisthenics.diagnostic.contract.PosePipeline
import com.calisthenics.diagnostic.contract.SemanticVersion
import com.calisthenics.diagnostic.contract.SessionManifest
import com.calisthenics.diagnostic.contract.Sha256
import com.calisthenics.diagnostic.contract.Uuid
import com.calisthenics.diagnostic.contract.encodeManifest
import com.calisthenics.diagnostic.export.StorageLocationRegistry
import com.calisthenics.diagnostic.export.StorageRoots
import com.calisthenics.diagnostic.hash.Sha256Digest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmPurgeFilesystemTest {
    private val sessionId = requireNotNull(PurgeSessionId.parseOrNull("00000000-0000-4000-8000-000000000001"))
    private val temporaryRoots = mutableListOf<Path>()

    @AfterTest
    fun removeTemporaryRoots() {
        temporaryRoots.forEach(::deleteTree)
    }

    @Test
    fun purgesOnlyExactOwnedExportStagingAndTransferBundlesAndPersistsReplayableJournal() {
        val roots = roots()
        val export = Path.of(roots.exportsRoot).resolve(sessionId.value)
        val staging = Path.of(roots.stagingRoot).resolve("${sessionId.value}.42.staging")
        val transfer = Path.of(roots.transferStagingRoot).resolve("${sessionId.value}.transfer.bundle")
        listOf(export, staging, transfer).forEach { target ->
            Files.createDirectories(target)
            Files.writeString(target.resolve("owned.txt"), "synthetic")
        }
        Files.writeString(export.resolve("manifest.json"), encodeManifest(committedManifest()))
        val exportPrefix = Path.of(roots.exportsRoot).resolve("${sessionId.value}-prefix")
        Files.createDirectories(exportPrefix)

        val filesystem = JvmPurgeFilesystem(roots)
        val result = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))
            .purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_COMPLETE, result.state, result.locationResults.toString())
        assertFalse(export.exists())
        assertFalse(staging.exists())
        assertFalse(transfer.exists())
        assertTrue(exportPrefix.exists(), "a prefix-sharing export is not owned")
        val journal = assertIs<PurgeJournalRead.Ready>(filesystem.readJournal(sessionId))
        assertEquals(
            listOf(PurgeState.INTENT_RECORDED, PurgeState.INTENT_RECORDED, PurgeState.PURGE_COMPLETE),
            journal.records.map(PurgeTombstone::state),
        )
        assertEquals(StorageLocationRegistry.CANONICAL_REGISTRY_SHA256, filesystem.registrySha256)
    }

    @Test
    fun symlinkedJournalTemporaryFileIsRejectedBeforeItCanBeWrittenOrAnyOwnedEntryDeleted() {
        val roots = roots()
        val export = Path.of(roots.exportsRoot).resolve(sessionId.value)
        Files.createDirectories(export)
        Files.writeString(export.resolve("owned.txt"), "synthetic")
        val external = Files.createTempFile("purge-journal-external-", ".txt")
        temporaryRoots.add(external)
        Files.writeString(external, "do-not-overwrite")
        Files.createSymbolicLink(
            Path.of(roots.tombstoneRoot).resolve("${sessionId.value}.purge-tombstone.tmp"),
            external,
        )

        val result = runCatching {
            SyntheticPurge(JvmPurgeFilesystem(roots), producerGitSha = "a".repeat(40))
                .purge(sessionId, "10000000-0000-4000-8000-000000000001")
        }

        assertTrue(result.isFailure, "a symlinked journal temporary file must fail closed")
        assertEquals("do-not-overwrite", Files.readString(external))
        assertTrue(export.exists(), "journal setup failure must precede the first owned delete")
    }

    @Test
    fun registryDigestMismatchInADurableJournalIsATypedBlockerBeforeAnyNewOwnedEntryIsDeleted() {
        val roots = roots()
        val filesystem = JvmPurgeFilesystem(roots)
        val purge = SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))
        val export = Path.of(roots.exportsRoot).resolve(sessionId.value)
        val staging = Path.of(roots.stagingRoot).resolve("${sessionId.value}.42.staging")
        val transfer = Path.of(roots.transferStagingRoot).resolve("${sessionId.value}.transfer.bundle")
        listOf(export, staging, transfer).forEach { target ->
            Files.createDirectories(target)
            Files.writeString(target.resolve("owned.txt"), "synthetic")
        }
        Files.writeString(export.resolve("manifest.json"), encodeManifest(committedManifest()))
        purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")
        val journal = Path.of(roots.tombstoneRoot).resolve("${sessionId.value}.purge-tombstone.json")
        val lines = Files.readString(journal).lineSequence().filter(String::isNotEmpty).toList()
        check(lines.size >= 2) { "test setup requires a chained intent and terminal journal" }
        val mismatchedLines = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            var mismatched = line.replace(StorageLocationRegistry.CANONICAL_REGISTRY_SHA256, "c".repeat(64))
            if (index > 0) {
                mismatched = PREVIOUS_RECORD_DIGEST.replace(
                    mismatched,
                    "\"previous_record_sha256\":\"${Sha256Digest.hex((mismatchedLines.last() + '\n').encodeToByteArray())}\"",
                )
            }
            mismatchedLines += mismatched
        }
        val mismatchedJournal = mismatchedLines.joinToString(separator = "\n", postfix = "\n")
        assertPythonJournalIssues(mismatchedJournal.encodeToByteArray(), expectedIssues = "")
        Files.writeString(journal, mismatchedJournal)
        Files.createDirectories(export)
        Files.writeString(export.resolve("owned.txt"), "synthetic")

        val refusal = assertFailsWith<PurgeRefusal> {
            purge.purge(sessionId, "10000000-0000-4000-8000-000000000001")
        }

        assertEquals("PURGE.REGISTRY_DIGEST_MISMATCH", refusal.code)
        assertTrue(export.exists(), "a registry-digest mismatch must block before deletion")
    }

    @Test
    fun committedExportsRequireAMatchingSyntheticLiveManifestBeforeTheyAreOwned() {
        val incorrectSessionId = "00000000-0000-4000-8000-000000000002"
        val cases = mapOf(
            "missing" to null,
            "malformed" to "{not-json",
            "mismatched_session" to encodeManifest(committedManifest().copy(sessionId = Uuid(incorrectSessionId))),
            "participant_like" to encodeManifest(committedManifest().copy(participantPseudonymId = "participant-01")),
            "non_live" to encodeManifest(committedManifest().copy(sessionKind = "fixture_replay")),
        )

        cases.forEach { (name, manifest) ->
            val roots = roots()
            val export = Path.of(roots.exportsRoot).resolve(sessionId.value)
            val staging = Path.of(roots.stagingRoot).resolve("${sessionId.value}.42.staging")
            val transfer = Path.of(roots.transferStagingRoot).resolve("${sessionId.value}.transfer.bundle")
            Files.createDirectories(export)
            manifest?.let { Files.writeString(export.resolve("manifest.json"), it) }
            listOf(staging, transfer).forEach { target ->
                Files.createDirectories(target)
                Files.writeString(target.resolve("owned.txt"), "synthetic")
            }

            val result = SyntheticPurge(JvmPurgeFilesystem(roots), producerGitSha = "a".repeat(40))
                .purge(sessionId, "10000000-0000-4000-8000-000000000001")

            assertEquals(PurgeState.PURGE_INCOMPLETE, result.state, name)
            assertTrue(export.exists(), "$name committed export must not be deleted")
            assertEquals(0, result.locationResults.count { it.terminalResult == PurgeTerminalResult.DELETED }, name)
        }
    }

    @Test
    fun journalBytesValidateInPythonAndAValidPriorLineMutationBreaksItsExactByteLink() {
        val roots = roots()
        val filesystem = JvmPurgeFilesystem(roots)
        val export = Path.of(roots.exportsRoot).resolve(sessionId.value)
        val staging = Path.of(roots.stagingRoot).resolve("${sessionId.value}.42.staging")
        val transfer = Path.of(roots.transferStagingRoot).resolve("${sessionId.value}.transfer.bundle")
        listOf(export, staging, transfer).forEach { target ->
            Files.createDirectories(target)
            Files.writeString(target.resolve("owned.txt"), "synthetic")
        }
        Files.writeString(export.resolve("manifest.json"), encodeManifest(committedManifest()))
        SyntheticPurge(filesystem, producerGitSha = "a".repeat(40))
            .purge(sessionId, "10000000-1234-4abc-8def-000000000001")
        val journal = Path.of(roots.tombstoneRoot).resolve("${sessionId.value}.purge-tombstone.json")
        val persisted = Files.readAllBytes(journal)

        assertPythonJournalIssues(persisted, expectedIssues = "")

        val mutated = persisted.copyOf()
        val producerSha = "\"producer_git_sha\":\"${"a".repeat(40)}\"".encodeToByteArray()
        val producerOffset = persisted.indexOfSlice(producerSha)
        check(producerOffset >= 0) { "first persisted line must contain the producer SHA" }
        mutated[producerOffset + producerSha.size - 2] = 'b'.code.toByte()

        assertPythonJournalIssues(mutated, expectedIssues = "PURGE.TOMBSTONE_INTEGRITY")
        Files.write(journal, mutated)
        assertIs<PurgeJournalRead.Unreadable>(filesystem.readJournal(sessionId))
    }

    @Test
    fun schemaInvalidParseableFinalLineIsRejectedByBothPythonAndKotlin() {
        val roots = roots()
        val filesystem = JvmPurgeFilesystem(roots)
        val export = Path.of(roots.exportsRoot).resolve(sessionId.value)
        val staging = Path.of(roots.stagingRoot).resolve("${sessionId.value}.42.staging")
        val transfer = Path.of(roots.transferStagingRoot).resolve("${sessionId.value}.transfer.bundle")
        listOf(export, staging, transfer).forEach { target ->
            Files.createDirectories(target)
            Files.writeString(target.resolve("owned.txt"), "synthetic")
        }
        Files.writeString(export.resolve("manifest.json"), encodeManifest(committedManifest()))
        val operationId = "10000000-1234-4abc-8def-000000000001"
        SyntheticPurge(filesystem, producerGitSha = "a".repeat(40)).purge(sessionId, operationId)
        val journal = Path.of(roots.tombstoneRoot).resolve("${sessionId.value}.purge-tombstone.json")
        val lines = Files.readString(journal).lineSequence().filter(String::isNotEmpty).toList()
        val finalLine = lines.last()
        val mutations = mapOf(
            "extra_property" to finalLine.dropLast(1) + ",\"unexpected\":true}",
            "malformed_uuid" to finalLine.replace(operationId, "not-a-uuid"),
        )

        mutations.forEach { (name, malformedFinalLine) ->
            val serialized = (lines.dropLast(1) + malformedFinalLine)
                .joinToString(separator = "\n", postfix = "\n")
                .encodeToByteArray()
            assertPythonJournalIssues(serialized, expectedIssues = "PURGE.SCHEMA")
            Files.write(journal, serialized)

            assertIs<PurgeJournalRead.Unreadable>(filesystem.readJournal(sessionId), name)
        }
    }

    @Test
    fun freshPurgeOfEmptyRegisteredRootsRemainsIncompleteInsteadOfCompletingVacuously() {
        val roots = roots()

        val result = SyntheticPurge(JvmPurgeFilesystem(roots), producerGitSha = "a".repeat(40))
            .purge(sessionId, "10000000-0000-4000-8000-000000000001")

        assertEquals(PurgeState.PURGE_INCOMPLETE, result.state)
        assertTrue(result.locationResults.all { it.terminalResult == PurgeTerminalResult.BLOCKED })
    }

    private fun roots(): StorageRoots {
        val temporary = Files.createTempDirectory("jvm-purge-filesystem-")
        temporaryRoots.add(temporary)
        val filesDir = temporary.resolve("files")
        val base = filesDir.resolve("diagnostic-harness")
        Files.createDirectories(base)
        listOf("staging", "exports", "transfer-staging", "tombstones").forEach { child ->
            Files.createDirectories(base.resolve(child))
        }
        return StorageRoots(
            filesDir = filesDir.toString(),
            diagnosticHarnessRoot = base.toString(),
            stagingRoot = base.resolve("staging").toString(),
            exportsRoot = base.resolve("exports").toString(),
            transferStagingRoot = base.resolve("transfer-staging").toString(),
            tombstoneRoot = base.resolve("tombstones").toString(),
        )
    }

    private fun committedManifest(): SessionManifest = SessionManifest(
        schemaVersion = SemanticVersion("1.1.0"),
        sessionId = Uuid(sessionId.value),
        sessionKind = "live_conformance",
        participantPseudonymId = "synthetic-m05-${sessionId.value}",
        captureProtocolVersion = SemanticVersion("1.0.0"),
        harnessGitSha = "a".repeat(40),
        device = Device("jvm", "test", "purge-test", null, false),
        posePipeline = PosePipeline("fake", "1", "model", Sha256("0".repeat(64)), "cpu", "live_stream", 1, 0.5, 0.5, 0.5, "fake"),
        camera = Camera("back", 640, 480, 0, false, 30.0, null, null, null, null, "monotonic", "fake"),
        modelInput = ModelInput(256, 256, SemanticVersion("1.0.0"), null),
        inferenceDrainTimeoutMs = 1000,
        cadence = Cadence(15.0, emptyList(), null),
        clock = Clock("monotonic", 0, "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z"),
        availableObservations = AvailableObservations(true, false),
        mediaReferences = emptyList(),
        counts = Counts(0, 0, 0, 0, 0, emptyMap(), 0, 0),
        machineInferenceFailures = emptyList(),
        captureQualityObserved = CaptureQualityObserved(true, true, true, "known", false, null),
        studyContext = emptyMap(),
    )

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun assertPythonJournalIssues(serialized: ByteArray, expectedIssues: String) {
        val directory = Files.createTempDirectory("jvm-purge-python-contract-")
        try {
            val journal = directory.resolve("journal.ndjson")
            Files.write(journal, serialized)
            val root = repositoryRoot()
            val process = ProcessBuilder(
                root.resolve(".venv/bin/python").toString(),
                "-c",
                PYTHON_JOURNAL_ASSERTION,
                journal.toString(),
                expectedIssues,
            )
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(0, process.waitFor(), output)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }
        .first { Files.isRegularFile(it.resolve("reference/src/observation_contracts/purge.py")) }

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int = indices.firstOrNull { start ->
        start + slice.size <= size && slice.indices.all { offset -> this[start + offset] == slice[offset] }
    } ?: -1

    private companion object {
        val PREVIOUS_RECORD_DIGEST = Regex("\"previous_record_sha256\":\"[0-9a-f]{64}\"")
        val PYTHON_JOURNAL_ASSERTION = """
            import sys
            from observation_contracts.purge import validate_tombstone_journal

            issues = validate_tombstone_journal(open(sys.argv[1], "rb").read())
            observed = ",".join(issue.code for issue in issues)
            if observed != sys.argv[2]:
                raise SystemExit(f"expected {sys.argv[2]!r}, got {observed!r}")
        """.trimIndent()
    }
}
