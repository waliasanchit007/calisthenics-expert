package com.calisthenics.diagnostic.preview

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.ui.MovementMeasurementsPanel
import com.calisthenics.diagnostic.ui.MovementPanelState
import com.calisthenics.diagnostic.ui.MovementViewerScreen
import com.calisthenics.diagnostic.ui.MovementViewerUiState
import com.calisthenics.diagnostic.viewer.ArmMeasurements
import com.calisthenics.diagnostic.viewer.MovementMeasurements
import com.calisthenics.diagnostic.viewer.experimental.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic numbers and UI only. Never starts the camera or records a human. */
class MovementSetupUiTest {
    private fun saveSyntheticScreen(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.cacheDir,name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    @Test fun briefTrackingChangesDoNotFlashInstructionsButPersistentLossIsShown() {
        val ready=PullUpExperimentState(stage=ExperimentStage.CALIBRATING,calibrationEndsNs=10_000_000_000)
        val state=mutableStateOf(ready)
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MovementMeasurementsPanel(MovementPanelState(experiment=state.value,cameraActive=true),{}, {}, {})
            } }
            assertNotNull(node("Practice one pull-up"))
            repeat(3) {
                scenario.onActivity { state.value=ready.copy(calibrationEndsNs=null,phase=CyclePhase.UNCERTAIN) }
                Thread.sleep(150)
                assertTrue(!visibleText().contains("Bring your arm into view"),visibleText())
                scenario.onActivity { state.value=ready }
                Thread.sleep(150)
            }
            scenario.onActivity { state.value=ready.copy(calibrationEndsNs=null) }
            assertNotNull(node("Bring your arm into view"))
            scenario.onActivity { state.value=ready.copy(stage=ExperimentStage.READY) }
            assertNotNull(node("Practice accepted — counting is OFF"))
            scenario.onActivity { state.value=ready.copy(stage=ExperimentStage.COUNTING,
                phase=CyclePhase.UNCERTAIN,candidateCycles=2) }
            assertNotNull(node("Counting paused"))
            assertNotNull(node("2"))
        }
    }
    private fun visibleText(): String {
        fun walk(n: AccessibilityNodeInfo?): String = n?.let {
            "${it.text ?: ""}\n" + (0 until it.childCount).joinToString("") { i -> walk(it.getChild(i)) }
        } ?: ""
        return walk(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    private fun tap(text: String) {
        val deadline=android.os.SystemClock.elapsedRealtime()+5_000
        do {
            var button=assertNotNull(node(text))
            while (!button.isClickable && button.parent!=null) button=button.parent
            if(button.isClickable && button.isEnabled) {
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            Thread.sleep(50)
        } while(android.os.SystemClock.elapsedRealtime()<deadline)
        throw AssertionError("Button did not enable: $text\n${visibleText()}")
    }

    @Test fun activeWorkoutHidesChangingTelemetryAndKeepsStopAccessible() {
        val stopped=AtomicInteger()
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MovementViewerScreen(MovementViewerUiState(active=true,message="volatile frame 912",
                    performance="volatile latency 153ms",submitted=912,completed=830,skipped=82),null,
                    onSynthetic={},onCamera={},onStop={stopped.incrementAndGet()},onPermission={},onAttestation={},
                    diagnosticsStatus="Recording locally · model-input images + movement logs",
                    movementSummary="volatile duplicate status",
                    movementPanel={ MovementMeasurementsPanel(MovementPanelState(cameraActive=true),{}, {}, {}) })
            } }
            assertNotNull(node("Stop"))
            val text=visibleText()
            assertTrue(!text.contains("volatile"),text)
            assertTrue(!text.contains("submitted") && !text.contains("landmark observations"),text)
            saveSyntheticScreen("coach-active.png")
            assertEquals(0,stopped.get())
            tap("Stop")
            assertEquals(1,stopped.get())
        }
    }

    @Test fun cameraStartRequiresConsentButDoesNotKeepConsentOnWorkoutScreen() {
        val starts=AtomicInteger()
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state=mutableStateOf(MovementViewerUiState(cameraPermission=true))
                activity.setContent {
                    MovementViewerScreen(state.value,null,{}, {starts.incrementAndGet()}, {}, {},
                        { state.value=state.value.copy(attestedCameraScope=it) },feedbackRecordingEnabled=true)
                }
            }
            assertNotNull(node("Start camera"))
            assertTrue(!visibleText().contains("bystanders"))
            tap("Start camera")
            assertNotNull(node("Before you start"))
            assertEquals(0,starts.get())
            tap("I agree for this session")
            assertEquals(0,starts.get())
            tap("Start self-test")
            assertEquals(1,starts.get())
        }
    }
    private fun node(text: String): AccessibilityNodeInfo? {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline=android.os.SystemClock.elapsedRealtime()+5_000
        // Compose exposes virtual descendants to traversal, but platform text search may
        // return an empty list for those same nodes. Match the actual rendered text.
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.text?.toString() == text) return n
            for (index in 0 until n.childCount) find(n.getChild(index))?.let { return it }
            return null
        }
        do {
            val result=find(automation.rootInActiveWindow)
            if(result!=null) return result
            Thread.sleep(50)
        } while(android.os.SystemClock.elapsedRealtime()<deadline)
        fun describe(n: AccessibilityNodeInfo?, depth: Int = 0): String = n?.let {
            "${" ".repeat(depth)}text=${it.text} desc=${it.contentDescription} clickable=${it.isClickable}\n" +
                (0 until it.childCount).joinToString("") { index -> describe(it.getChild(index),depth+1) }
        } ?: ""
        throw AssertionError("Missing '$text' in active UI:\n${describe(automation.rootInActiveWindow)}")
    }

    @Test fun acceptedPracticeClearlyRequiresConfirmationAndButtonActuallyStartsCounting() {
        val e=PullUpExperiment()
        e.startCalibration(ArmSide.LEFT,0)
        for(ms in 5_000L..15_000L step 100) {
            val angle=when(ms) {
                in 6_000..6_400, in 7_100..7_500 -> 100.0
                in 6_500..7_000 -> 40.0
                else -> 160.0
            }
            e.onSample(MovementMeasurements(ms*1_000_000,ArmMeasurements(angle,.3,.9),ArmMeasurements(),3),ms*1_000_000)
        }
        assertEquals(ExperimentStage.READY,e.state.stage)
        val confirmations=AtomicInteger()
        ActivityScenario.launch(MovementViewerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state=mutableStateOf(e.state)
                activity.setContent {
                    MovementViewerScreen(MovementViewerUiState(active=true),null,{}, {}, {}, {}, {},
                        movementPanel={ MovementMeasurementsPanel(MovementPanelState(experiment=state.value,cameraActive=true),
                        onCalibrate={ error("Wrong action") },onReset={ error("Wrong action") },
                        onConfirmRange={ confirmations.incrementAndGet(); e.confirmCalibration(); state.value=e.state }) })
                }
            }
            assertNotNull(node("Practice accepted — counting is OFF"))
            assertEquals(0,confirmations.get())
            var button=assertNotNull(node("Confirm full practice & start counting"))
            while(!button.isClickable && button.parent!=null) button=button.parent
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertNotNull(node("Counting is ON"))
            saveSyntheticScreen("coach-counting.png")
            assertEquals(1,confirmations.get())
            assertEquals(ExperimentStage.COUNTING,e.state.stage)
            assertEquals(0,e.state.candidateCycles)
        }
    }
}
