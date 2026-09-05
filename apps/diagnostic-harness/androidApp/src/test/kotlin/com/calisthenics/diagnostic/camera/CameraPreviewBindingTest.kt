package com.calisthenics.diagnostic.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class CameraPreviewBindingTest {
    @Test fun shutdownUnbindsBothStreamsButStudyBindingOnlyUnbindsAnalysis() {
        val analysis = ImageAnalysis.Builder().build()
        val preview = Preview.Builder().build()
        val viewer = CameraBoundUseCases(analysis,preview)
        var released = emptyList<androidx.camera.core.UseCase>()
        viewer.release { released = it.toList() }
        assertEquals(listOf(analysis,preview),released)
        CameraBoundUseCases(analysis,null).release { released = it.toList() }
        assertEquals(listOf(analysis),released)
    }
}
