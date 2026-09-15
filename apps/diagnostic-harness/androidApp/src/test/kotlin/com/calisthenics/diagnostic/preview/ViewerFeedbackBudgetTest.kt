package com.calisthenics.diagnostic.preview

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

/**
 * The 2026-09-15 capture wrote nothing because 220 MiB of old sessions exceeded the 192 MiB budget,
 * and the refusal was reported as an ordinary stop. These pin the two halves of that fix: the
 * budget is answerable BEFORE a session, and a refusal says so in words an operator can act on.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ViewerFeedbackBudgetTest {
    @get:Rule val temp = TemporaryFolder()

    private fun fill(root: File, bytes: Int) {
        root.mkdirs()
        File(root, "session-${bytes}").writeBytes(ByteArray(bytes))
    }

    @Test fun anEmptyRootIsNeitherFullNorNearlyFull() {
        val budget = viewerFeedbackBudget(File(temp.root, "absent"), maxBytes = 100, totalBytes = 500)
        assertEquals(0L, budget.usedBytes)
        assertEquals(400L, budget.allowedBytes)
        assertFalse(budget.full)
        assertFalse(budget.nearlyFull)
    }

    @Test fun theOperatorIsWarnedBeforeTheCapRefuses() {
        val root = File(temp.root, "feedback")
        fill(root, 320)                 // 80% of the 400-byte allowance
        val budget = viewerFeedbackBudget(root, maxBytes = 100, totalBytes = 500)
        assertTrue(budget.nearlyFull, "80% should warn")
        assertFalse(budget.full, "80% must not yet be a refusal")
    }

    @Test fun overTheAllowanceReportsFull() {
        val root = File(temp.root, "feedback")
        fill(root, 401)
        val budget = viewerFeedbackBudget(root, maxBytes = 100, totalBytes = 500)
        assertTrue(budget.full)
    }

    /** The exact failure: a refused session must not be describable as a normal stop. */
    @Test fun aRefusedSessionSaysTheBudgetIsFullAndHowToFixIt() {
        val root = File(temp.root, "feedback")
        fill(root, 4_000)
        val bundle = ViewerFeedbackBundle(root, "test", 0L, "{}", maxBytes = 100, totalBytes = 500)
        try {
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && !bundle.status.contains("budget full")) Thread.sleep(20)
            assertTrue(bundle.status.contains("budget full"), "status was: ${bundle.status}")
            assertTrue(bundle.status.contains("delete recordings"), "must say what fixes it: ${bundle.status}")
            assertFalse(bundle.status.startsWith("Recording stopped"), "must not read as an ordinary stop")
        } finally { bundle.close() }
    }
}
