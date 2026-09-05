package com.calisthenics.diagnostic.preview

import com.calisthenics.diagnostic.camera.OwnedYuvFrame
import com.calisthenics.diagnostic.preprocessing.*
import org.junit.Test
import kotlin.test.*

class LiveViewerPreparationTest {
    @Test fun nativePreviewDoesNotRetainAnUnusedFullResolutionRgbImage() {
        val source = OwnedYuvFrame(byteArrayOf(0,0,0,0),2,1,
            byteArrayOf(128.toByte()),1,1,byteArrayOf(128.toByte()),1,1,
            2,2,FrameRotation.R0,false,OwnedYuvFrame.ADMITTED_RAW_DATASPACE,
            YuvColorStandard.BT601,YuvColorRange.FULL,0)
        val frame = prepareViewerFrame(source)
        assertTrue(frame.rgb.isEmpty(), "native preview must not allocate/retain a full RGB preview image")
        assertEquals(2,frame.width)
        assertEquals(2,frame.height)
        assertEquals(256*256*3,frame.modelRgb.size)
        assertTrue(frame.modelRgb.all { it == 0.toByte() })
    }
}
