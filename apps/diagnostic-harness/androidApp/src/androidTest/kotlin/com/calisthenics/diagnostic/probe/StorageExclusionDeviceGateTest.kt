package com.calisthenics.diagnostic.probe

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.android.BuildConfig
import com.calisthenics.diagnostic.android.R
import com.calisthenics.diagnostic.export.AndroidStorageRoots
import com.calisthenics.diagnostic.export.StorageLocationRegistry
import com.calisthenics.diagnostic.export.StorageOwner
import com.calisthenics.diagnostic.export.StorageRootsResult
import com.calisthenics.diagnostic.replay.androidDeviceClassOf
import com.calisthenics.diagnostic.study.StorageExclusionEnvironment
import com.calisthenics.diagnostic.study.StorageExclusionEvidence
import com.calisthenics.diagnostic.study.StorageExclusionObservation
import com.calisthenics.diagnostic.study.renderStorageExclusionEvidence
import com.calisthenics.diagnostic.study.storageExclusionBlockingReasons
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class StorageExclusionDeviceGateTest {
    @Test
    fun recordsInstalledManifestRulesAndRegisteredAppPrivateRoots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val extractionRulesResource = R.xml.data_extraction_rules
        val exclusions = readExclusions(context.resources.getXml(extractionRulesResource))
        val roots = (AndroidStorageRoots.from(context) as StorageRootsResult.Ready).roots
        val registeredChildren = StorageLocationRegistry.entries
            .filter { it.owner == StorageOwner.ANDROID_APP }
            .map { it.relativeRoot }
        val observedChildren = File(roots.diagnosticHarnessRoot)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { it.name }

        val evidence = StorageExclusionEvidence(
            emittedBy = EMITTED_BY,
            harnessGitSha = BuildConfig.PROBE_GIT_SHA,
            environment = StorageExclusionEnvironment(
                deviceClass = androidDeviceClassOf(Build.FINGERPRINT, Build.HARDWARE),
                deviceIdentity = "${Build.MANUFACTURER}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}",
                deviceFingerprint = Build.FINGERPRINT,
                apiLevel = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE,
                abi = Build.SUPPORTED_ABIS.joinToString(","),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                marketingName = readMarketingName(),
            ),
            observation = StorageExclusionObservation(
                allowBackupDisabled = applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0,
                dataExtractionRulesResource = if (extractionRulesResource == 0) {
                    null
                } else {
                    "@xml/${context.resources.getResourceEntryName(extractionRulesResource)}"
                },
                cloudBackupExcludedParent = exclusions["cloud-backup"],
                deviceTransferExcludedParent = exclusions["device-transfer"],
                registeredAndroidChildren = registeredChildren,
                observedTopLevelChildren = observedChildren,
            ),
        )
        val record = renderStorageExclusionEvidence(evidence)
        instrumentation.sendStatus(
            STATUS_CODE,
            Bundle().apply { putString(STATUS_TAG, record) },
        )
        assertTrue(storageExclusionBlockingReasons(evidence).isEmpty(), record)
    }

    private fun readExclusions(parser: XmlPullParser): Map<String, String> {
        val exclusions = mutableMapOf<String, String>()
        var domain: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "cloud-backup", "device-transfer" -> domain = parser.name
                    "exclude" -> if (parser.getAttributeValue(null, "domain") == "file") {
                        domain?.let { exclusions[it] = parser.getAttributeValue(null, "path") }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == domain) domain = null
            }
            parser.next()
        }
        return exclusions
    }

    private companion object {
        const val EMITTED_BY = "com.calisthenics.diagnostic.probe.StorageExclusionDeviceGateTest"
        const val STATUS_TAG = "StorageExclusionEvidence"
        const val STATUS_CODE = 7
    }
}
