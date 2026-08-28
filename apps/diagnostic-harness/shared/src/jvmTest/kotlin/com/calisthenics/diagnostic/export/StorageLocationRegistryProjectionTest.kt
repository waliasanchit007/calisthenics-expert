package com.calisthenics.diagnostic.export

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageLocationRegistryProjectionTest {
    @Test
    fun kotlinProjectionEqualsEveryFieldInCanonicalRegistry() {
        val document = Json.parseToJsonElement(
            Files.readString(repositoryRoot().resolve("docs/protocols/storage-location-registry.m05b.v1.json")),
        ).jsonObject
        val canonical = document.getValue("locations").jsonArray

        assertEquals(document.getValue("registry_version").jsonPrimitive.content, StorageLocationRegistry.VERSION)
        assertEquals(canonical.size, StorageLocationRegistry.entries.size)
        canonical.zip(StorageLocationRegistry.entries).forEach { (encoded, projected) ->
            val location = encoded.jsonObject
            assertEquals(location.string("location_id"), projected.id.wireName())
            assertEquals(location.string("owner"), projected.owner.wireName())
            assertEquals(location.string("relative_root"), projected.relativeRoot)
            assertEquals(
                location.getValue("artifact_classes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                projected.artifactClasses.mapTo(mutableSetOf()) { it.wireName() },
            )
            assertEquals(location.boolean("publishable"), projected.publishable)
            assertEquals(location.boolean("device_purge"), projected.devicePurge)
            assertEquals(location.boolean("host_rehearsal_purge"), projected.hostRehearsalPurge)
            assertEquals(location.string("cloud_backup"), projected.cloudBackup.wireName())
            assertEquals(location.string("device_transfer"), projected.deviceTransfer.wireName())
            assertEquals(location.boolean("tombstone_eligible"), projected.tombstoneEligible)
            assertEquals(location.boolean("post_purge_scan"), projected.postPurgeScan)
        }
    }

    @Test
    fun canonicalRegistryDigestBindsTheExactCommittedBytes() {
        val bytes = Files.readAllBytes(repositoryRoot().resolve("docs/protocols/storage-location-registry.m05b.v1.json"))
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertEquals(actual, StorageLocationRegistry.CANONICAL_REGISTRY_SHA256)
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun Map<String, kotlinx.serialization.json.JsonElement>.boolean(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean

    private fun Enum<*>.wireName(): String = name.lowercase()

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }
        .first { Files.isRegularFile(it.resolve("docs/protocols/storage-location-registry.m05b.v1.json")) }
}
