package com.landosol.toolbox.automation.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `a pooled scratch buffer is reused and still converts every pixel`() {
        // 2026-09-20 live: capture stopped with "Failed to allocate a 8294416 byte allocation with
        // 7140992 free bytes ... growth limit 201326592" — a fresh 1920x1080 int[] per frame. The
        // buffer is written before it is read for every pixel and Bitmap.createBitmap copies it
        // out before the next frame, so pooling it cannot leak one frame's pixels into the next.
        val scratch = CaptureFrameScratch()
        fun convert(first: Int): IntArray {
            val buffer = ByteBuffer.allocate(16)
            buffer.put(byteArrayOf(
                (first ushr 16 and 0xff).toByte(), (first ushr 8 and 0xff).toByte(),
                (first and 0xff).toByte(), (first ushr 24 and 0xff).toByte(),
            ))
            buffer.put(byteArrayOf(0x11, 0x22, 0x33, 0xff.toByte()))
            buffer.put(byteArrayOf(0x44, 0x55, 0x66, 0xff.toByte()))
            buffer.put(byteArrayOf(0x77, 0x77.toByte(), 0x77, 0xff.toByte()))
            return rgba8888ToArgbPixels(2, 2, 4, 8, buffer, scratch)
        }

        val first = convert(0xffff0000.toInt())
        val firstCopy = first.copyOf()
        val second = convert(0xff00ff00.toInt())
        // Same array instance: the allocation really is pooled, not merely cached.
        assertSame(first, second)
        // And the reused array carries this frame's pixels, not the previous frame's.
        assertEquals(0xffff0000.toInt(), firstCopy[0])
        assertEquals(0xff00ff00.toInt(), second[0])
        assertEquals(0xff112233.toInt(), second[1])
        assertEquals(0xff445566.toInt(), second[2])
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
