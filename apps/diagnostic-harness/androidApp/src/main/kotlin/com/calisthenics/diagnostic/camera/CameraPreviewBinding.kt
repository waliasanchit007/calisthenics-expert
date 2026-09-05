package com.calisthenics.diagnostic.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ViewPort

/** Viewer-only binding. Null at the source preserves the formal analysis-only path. */
class CameraPreviewBinding(
    val preview: Preview,
    val viewPort: ViewPort,
    val onFrame: (OwnedYuvFrame, AnalysisFrameTransform) -> Unit,
)

internal class CameraBoundUseCases(val analysis: ImageAnalysis, val preview: Preview?) {
    fun release(unbind: (Array<UseCase>) -> Unit) {
        analysis.clearAnalyzer()
        try { preview?.setSurfaceProvider(null) }
        finally { unbind(listOfNotNull(analysis, preview).toTypedArray()) }
    }
}
