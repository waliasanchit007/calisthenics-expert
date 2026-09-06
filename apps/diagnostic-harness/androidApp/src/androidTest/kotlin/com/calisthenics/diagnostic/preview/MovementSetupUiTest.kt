package com.calisthenics.diagnostic.preview

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.calisthenics.diagnostic.ui.MovementMeasurementsPanel
import com.calisthenics.diagnostic.ui.MovementPanelState
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
                    MovementMeasurementsPanel(MovementPanelState(experiment=state.value,cameraActive=true),
                        onCalibrate={ error("Wrong action") },onReset={ error("Wrong action") },
                        onConfirmRange={ confirmations.incrementAndGet(); e.confirmCalibration(); state.value=e.state })
                }
            }
            assertNotNull(node("Practice accepted — counting is OFF"))
            assertEquals(0,confirmations.get())
            var button=assertNotNull(node("Confirm full practice & start counting"))
            while(!button.isClickable && button.parent!=null) button=button.parent
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertNotNull(node("Counting is ON"))
            assertEquals(1,confirmations.get())
            assertEquals(ExperimentStage.COUNTING,e.state.stage)
            assertEquals(0,e.state.candidateCycles)
        }
    }
}
