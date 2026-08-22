package com.calisthenics.diagnostic.camera

import com.calisthenics.diagnostic.preprocessing.FrameRotation
import com.calisthenics.diagnostic.preprocessing.YuvColorRange
import com.calisthenics.diagnostic.preprocessing.YuvColorStandard

class OwnedYuvPlane(bytes: ByteArray, val rowStride: Int, val pixelStride: Int) {
    private val storage = bytes.copyOf()

    init {
        require(rowStride > 0) { "rowStride must be positive" }
        require(pixelStride > 0) { "pixelStride must be positive" }
        require(rowStride >= pixelStride) { "rowStride must be at least pixelStride" }
    }

    val bytes: ByteArray
        get() = storage.copyOf()
}

class OwnedYuvFrame(
    yBytes: ByteArray,
    yRowStride: Int,
    yPixelStride: Int,
    uBytes: ByteArray,
    uRowStride: Int,
    uPixelStride: Int,
    vBytes: ByteArray,
    vRowStride: Int,
    vPixelStride: Int,
    val width: Int,
    val height: Int,
    val rotation: FrameRotation,
    val mirrored: Boolean,
    val rawDataSpace: Int,
    val colorStandard: YuvColorStandard,
    val colorRange: YuvColorRange,
    val timestampNs: Long,
) {
    val y = OwnedYuvPlane(yBytes, yRowStride, yPixelStride)
    val u = OwnedYuvPlane(uBytes, uRowStride, uPixelStride)
    val v = OwnedYuvPlane(vBytes, vRowStride, vPixelStride)

    init {
        require(width > 0 && height > 0) { "dimensions must be positive" }
        require(timestampNs >= 0L) { "timestampNs must be non-negative" }
        require(rawDataSpace == ADMITTED_RAW_DATASPACE) { "unsupported DataSpace" }
        require(colorStandard == YuvColorStandard.BT601) { "only BT601 is admitted" }
        require(colorRange == YuvColorRange.FULL) { "only FULL range is admitted" }
    }

    companion object {
        const val CAMERA_ID = "0"
        const val ADMITTED_PIXEL_FORMAT = 35 // ImageFormat.YUV_420_888
        const val ADMITTED_RAW_DATASPACE = 146931712
        const val ADMITTED_TIMESTAMP_SOURCE = "REALTIME"
    }
}

internal interface SourcePlane {
    val bytes: ByteArray
    val rowStride: Int
    val pixelStride: Int
}

internal interface OwnedImage {
    val planes: List<SourcePlane>
    val width: Int
    val height: Int
    val rotation: FrameRotation
    val mirrored: Boolean
    val rawDataSpace: Int
    val colorStandard: YuvColorStandard
    val colorRange: YuvColorRange
    val timestampNs: Long
    fun close()
}

internal fun captureOwnedYuvFrame(source: OwnedImage, onFrame: (OwnedYuvFrame) -> Unit): OwnedYuvFrame {
    try {
        require(source.planes.size == 3) { "YUV_420_888 must provide three planes" }
        val frame = OwnedYuvFrame(
            yBytes = source.planes[0].bytes,
            yRowStride = source.planes[0].rowStride,
            yPixelStride = source.planes[0].pixelStride,
            uBytes = source.planes[1].bytes,
            uRowStride = source.planes[1].rowStride,
            uPixelStride = source.planes[1].pixelStride,
            vBytes = source.planes[2].bytes,
            vRowStride = source.planes[2].rowStride,
            vPixelStride = source.planes[2].pixelStride,
            width = source.width,
            height = source.height,
            rotation = source.rotation,
            mirrored = source.mirrored,
            rawDataSpace = source.rawDataSpace,
            colorStandard = source.colorStandard,
            colorRange = source.colorRange,
            timestampNs = source.timestampNs,
        )
        source.close()
        onFrame(frame)
        return frame
    } catch (failure: Throwable) {
        source.close()
        throw failure
    }
}
