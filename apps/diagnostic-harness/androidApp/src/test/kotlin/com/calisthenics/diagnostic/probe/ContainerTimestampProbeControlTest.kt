package com.calisthenics.diagnostic.probe

import com.calisthenics.diagnostic.video.TrackTimescales
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class ContainerTimestampProbeControlTest {

    @Test
    fun ninetyKilohertzProducesAlignedOnGridAndDeliberatelyOffGridSequences() {
        val controls = deriveControlledSubmittedPts(TrackTimescales(90_000L, 90_000L))

        assertEquals(3, controls.onGrid.size)
        assertEquals(controls.onGrid.size, controls.offGrid.size)
        assertEquals(listOf(1_000_000L, 1_033_300L, 1_066_600L), controls.onGrid)
        assertEquals(listOf(1_000_000L, 1_033_301L, 1_066_602L), controls.offGrid)
        assertTrue(controls.onGrid.zipWithNext().all { (before, after) -> before < after })
        assertTrue(controls.offGrid.zipWithNext().all { (before, after) -> before < after })
        assertTrue(controls.onGrid.all { it * 90_000L % 1_000_000L == 0L })
        assertTrue(controls.offGrid.any { it * 90_000L % 1_000_000L != 0L })
    }

    @Test
    fun invalidOrMicrosecondExactTimebasesFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            deriveControlledSubmittedPts(TrackTimescales(90_000L, 1_000L))
        }
        assertFailsWith<IllegalArgumentException> {
            deriveControlledSubmittedPts(TrackTimescales(0L, 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            deriveControlledSubmittedPts(TrackTimescales(1_000_000L, 1_000_000L))
        }
    }
}
