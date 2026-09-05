package com.calisthenics.diagnostic.camera

import android.graphics.Matrix

/** Immutable copy of this frame's sensor mapping; no ImageProxy or image buffer is retained. */
class AnalysisFrameTransform(sensorToBuffer: Matrix, val width: Int, val height: Int, val rotation: Int) {
    private val values = FloatArray(9).also(sensorToBuffer::getValues)
    init {
        require(width > 0 && height > 0)
        require(rotation in listOf(0, 90, 180, 270))
    }
    fun sensorToBuffer(): Matrix = Matrix().apply { setValues(values) }
}
