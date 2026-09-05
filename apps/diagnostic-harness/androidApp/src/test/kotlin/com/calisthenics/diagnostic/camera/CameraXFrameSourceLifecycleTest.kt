package com.calisthenics.diagnostic.camera

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraXFrameSourceLifecycleTest {
    @Test
    fun immediateStopFencesProviderCompletionAndShutsAnalyzerOnce() {
        val harness = SourceHarness()
        val generation = harness.lifecycle.begin()

        harness.lifecycle.stop()

        assertFalse(harness.lifecycle.complete(generation, "provider", "use-case") { harness.configured += 1 })
        assertEquals(0, harness.configured)
        assertEquals(listOf("provider/use-case"), harness.cleaned)
        assertEquals(1, harness.shutdowns)
    }

    @Test
    fun normalStopClearsOwnedAnalyzerBeforeExactlyOnceShutdown() {
        val harness = SourceHarness()
        val generation = harness.lifecycle.begin()
        assertTrue(harness.lifecycle.complete(generation, "provider", "use-case") { harness.configured += 1 })

        harness.lifecycle.stop()
        harness.lifecycle.stop()

        assertEquals(1, harness.configured)
        assertEquals(listOf("provider/use-case"), harness.cleaned)
        assertEquals(1, harness.shutdowns)
    }

    @Test
    fun analyzerQuiescenceWaitsForAnEnteredCallbackBeforeSignalling() {
        val fence = AnalyzerCallbackFence()
        var quiescenceSignals = 0
        val entered = CountDownLatch(1)
        val allowLeave = CountDownLatch(1)
        val callbackThread = Thread {
            assertTrue(fence.tryEnter())
            entered.countDown()
            check(allowLeave.await(1, TimeUnit.SECONDS))
            fence.leave()
        }
        callbackThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        fence.close()
        fence.onQuiescent { quiescenceSignals += 1 }

        assertEquals(0, quiescenceSignals)
        allowLeave.countDown()
        callbackThread.join(1_000)
        assertEquals(1, quiescenceSignals)
    }

    @Test
    fun throwingCleanupStillClearsActiveBindingAndShutsAnalyzerDownExactlyOnce() {
        val expected = IllegalStateException("cleanup failed")
        var cleanupAttempts = 0
        var shutdowns = 0
        val lifecycle = CameraXSourceLifecycle<String, String>(
            cleanupUseCase = { _, _ ->
                cleanupAttempts += 1
                throw expected
            },
            shutdownAnalyzer = { shutdowns += 1 },
        )
        val generation = lifecycle.begin()
        assertTrue(lifecycle.complete(generation, "provider", "use-case") {})

        assertEquals(expected, assertFailsWith<IllegalStateException> { lifecycle.stop() })
        lifecycle.stop()

        assertFalse(lifecycle.hasActiveBinding)
        assertEquals(1, cleanupAttempts)
        assertEquals(1, shutdowns)
    }

    @Test
    fun throwingStopDefersQuiescenceUntilEnteredCallbackLeavesAndFencesNewCallbacks() {
        val fence = AnalyzerCallbackFence()
        val expected = IllegalStateException("cleanup failed")
        var quiescenceSignals = 0
        val entered = CountDownLatch(1)
        val allowLeave = CountDownLatch(1)
        val callbackThread = Thread {
            check(fence.tryEnter())
            entered.countDown()
            check(allowLeave.await(1, TimeUnit.SECONDS))
            fence.leave()
        }
        callbackThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        assertEquals(
            expected,
            assertFailsWith<IllegalStateException> {
                fence.closeAndFinally(
                    stop = { throw expected },
                    onCallbacksQuiesced = { quiescenceSignals += 1 },
                )
            },
        )
        assertFalse(fence.tryEnter())
        assertEquals(0, quiescenceSignals)

        allowLeave.countDown()
        callbackThread.join(1_000)
        assertEquals(1, quiescenceSignals)
    }

    @Test
    fun callbackOwnedStopHandsOffQuiescenceWithoutDoubleRelease() {
        val fence = AnalyzerCallbackFence()
        var quiescenceSignals = 0

        assertTrue(fence.tryEnter())
        fence.close()
        fence.onQuiescent { quiescenceSignals += 1 }

        assertEquals(1, quiescenceSignals)
        fence.leave()

        assertEquals(1, quiescenceSignals)
    }

    @Test
    fun stopBeforeAsyncCompletionMakesOldGenerationInactiveAndCleansItsUseCase() {
        val harness = Harness()
        val generation = harness.gate.begin()

        harness.gate.stop()

        assertFalse(harness.gate.complete(generation, "provider-1", "use-case-1"))
        assertEquals(listOf("provider-1/use-case-1"), harness.unbound)
        assertFalse(harness.gate.hasActiveBinding)
    }

    @Test
    fun newerStartInvalidatesOlderCompletion() {
        val harness = Harness()
        val oldGeneration = harness.gate.begin()
        val newGeneration = harness.gate.begin()

        assertFalse(harness.gate.complete(oldGeneration, "provider-old", "use-case-old"))
        assertTrue(harness.gate.complete(newGeneration, "provider-new", "use-case-new"))
        assertEquals(listOf("provider-old/use-case-old"), harness.unbound)
        assertEquals("provider-new" to "use-case-new", harness.gate.activeBinding)
    }

    @Test
    fun staleCompletionCleansOnlyItsOwnUseCaseAndCannotOverwriteActiveBinding() {
        val harness = Harness()
        val oldGeneration = harness.gate.begin()
        val newGeneration = harness.gate.begin()
        assertTrue(harness.gate.complete(newGeneration, "provider-new", "use-case-new"))

        assertFalse(harness.gate.complete(oldGeneration, "provider-old", "use-case-old"))
        assertEquals(listOf("provider-old/use-case-old"), harness.unbound)
        assertEquals("provider-new" to "use-case-new", harness.gate.activeBinding)
    }

    @Test
    fun stopIsIdempotentAndUnbindsOnlyTheOwnedActiveUseCase() {
        val harness = Harness()
        val generation = harness.gate.begin()
        assertTrue(harness.gate.complete(generation, "provider-active", "use-case-active"))

        harness.gate.stop()
        harness.gate.stop()

        assertEquals(listOf("provider-active/use-case-active"), harness.unbound)
        assertFalse(harness.gate.hasActiveBinding)
    }

    private class Harness {
        val unbound = mutableListOf<String>()
        val gate = CameraXLifecycleGeneration<String, String> { provider, useCase ->
            unbound += "$provider/$useCase"
        }
    }

    private class SourceHarness {
        val cleaned = mutableListOf<String>()
        var configured = 0
        var shutdowns = 0
        val lifecycle = CameraXSourceLifecycle<String, String>(
            cleanupUseCase = { provider, useCase -> cleaned += "$provider/$useCase" },
            shutdownAnalyzer = { shutdowns += 1 },
        )
    }
}
