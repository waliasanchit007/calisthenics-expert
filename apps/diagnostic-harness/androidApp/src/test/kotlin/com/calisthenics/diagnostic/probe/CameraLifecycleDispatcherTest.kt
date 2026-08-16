package com.calisthenics.diagnostic.probe

import kotlin.test.assertEquals
import org.junit.Test

class CameraLifecycleDispatcherTest {
    @Test
    fun lifecycleOperationIsExecutedThroughTheDispatcherSeam() {
        val dispatcher = RecordingDispatcher()

        val value = dispatcher.dispatch(100) { "completed" }

        assertEquals("completed", value)
        assertEquals(1, dispatcher.dispatchCount)
    }

    private class RecordingDispatcher : CameraLifecycleDispatcher {
        var dispatchCount = 0

        override fun <T> dispatch(timeoutMillis: Long, operation: () -> T): T {
            dispatchCount += 1
            return operation()
        }
    }
}
