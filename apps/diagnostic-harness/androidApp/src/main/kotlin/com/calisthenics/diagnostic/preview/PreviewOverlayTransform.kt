package com.calisthenics.diagnostic.preview

import android.graphics.Matrix
import com.calisthenics.diagnostic.camera.AnalysisFrameTransform
import com.calisthenics.diagnostic.viewer.ViewerGeometry
import com.calisthenics.diagnostic.viewer.ViewerPoint

/** Model pad -> upright full buffer -> raw buffer -> sensor -> current preview pixels. */
internal fun mapPreviewPoint(x: Double, y: Double, source: AnalysisFrameTransform, sensorToView: Matrix?): ViewerPoint? {
    if (sensorToView == null) return null
    val quarterTurn = source.rotation == 90 || source.rotation == 270
    val width = if (quarterTurn) source.height else source.width
    val height = if (quarterTurn) source.width else source.height
    val upright = ViewerGeometry(width, height).modelToSource(x, y) ?: return null
    val ux = upright.x * width
    val uy = upright.y * height
    val raw = when (source.rotation) {
        0 -> floatArrayOf(ux, uy)
        90 -> floatArrayOf(uy, source.height - ux)
        180 -> floatArrayOf(source.width - ux, source.height - uy)
        else -> floatArrayOf(source.width - uy, ux)
    }
    val bufferToSensor = Matrix()
    if (!source.sensorToBuffer().invert(bufferToSensor)) return null
    bufferToSensor.mapPoints(raw)
    sensorToView.mapPoints(raw)
    return if (raw.all(Float::isFinite)) ViewerPoint(raw[0], raw[1]) else null
}
