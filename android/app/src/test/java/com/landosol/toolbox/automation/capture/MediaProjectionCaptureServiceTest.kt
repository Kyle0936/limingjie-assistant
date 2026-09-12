package com.landosol.toolbox.automation.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaProjectionCaptureServiceTest {
    @Test
    fun `initial capture waits for landscape metrics after returning from portrait picker`() {
        val gate = CaptureDisplayStabilityGate()
        val portrait = CaptureDisplayMetrics(width = 1080, height = 1920, densityDpi = 480)
        val landscape = CaptureDisplayMetrics(width = 1920, height = 1080, densityDpi = 480)

        assertNull(gate.observe(portrait))
        assertNull(gate.observe(landscape))
        assertNull(gate.observe(landscape))
        assertEquals(landscape, gate.observe(landscape))
    }

    @Test
    fun `initial capture starts after already stable display metrics`() {
        val gate = CaptureDisplayStabilityGate()
        val landscape = CaptureDisplayMetrics(width = 1920, height = 1080, densityDpi = 480)

        assertNull(gate.observe(landscape))
        assertNull(gate.observe(landscape))
        assertEquals(landscape, gate.observe(landscape))
    }

    @Test
    fun `rgba plane conversion removes row padding and preserves colors`() {
        val width = 2
        val height = 2
        val pixelStride = 4
        val rowStride = 12
        val buffer = ByteBuffer.allocate(rowStride * height)
        buffer.put(byteArrayOf(
            0xff.toByte(), 0, 0, 0xff.toByte(),
            0, 0xff.toByte(), 0, 0xff.toByte(),
            0x55, 0x55, 0x55, 0x55,
            0, 0, 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x80.toByte(),
            0x33, 0x33, 0x33, 0x33,
        )).flip()

        assertArrayEquals(
            intArrayOf(
                0xffff0000.toInt(),
                0xff00ff00.toInt(),
                0xff0000ff.toInt(),
                0x80ffffff.toInt(),
            ),
            rgba8888ToArgbPixels(width, height, pixelStride, rowStride, buffer),
        )
    }

    @Test
    fun `rgba plane conversion rejects a truncated native buffer before reading`() {
        assertThrows(IllegalArgumentException::class.java) {
            rgba8888ToArgbPixels(
                width = 2,
                height = 2,
                pixelStride = 4,
                rowStride = 12,
                buffer = ByteBuffer.allocate(8),
            )
        }
    }
}
