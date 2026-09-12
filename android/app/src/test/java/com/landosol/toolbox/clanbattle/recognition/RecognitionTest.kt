package com.landosol.toolbox.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class RecognitionTest {
    @Test
    fun `embedded owned digit templates are complete and decodable`() {
        assertEquals((0..9).toSet(), EmbeddedDigitTemplateLoader.encodedDigits.keys)
        EmbeddedDigitTemplateLoader.encodedDigits.values.forEach { encoded ->
            val bytes = Base64.getDecoder().decode(encoded)
            assertTrue(bytes.copyOfRange(1, 4).contentEquals(byteArrayOf('P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
            val header = ByteBuffer.wrap(bytes, 16, 8)
            assertTrue(header.int > 0 && header.int > 0)
        }
    }

    @Test
    fun `normalizer and structural matcher choose identical synthetic digit`() {
        val image = digit("8")
        val templates = DigitTemplates((0..9).associateWith { digit(it.toString()) })

        val result = DigitMatcher(templates).match(image, 0..9)

        assertEquals(8, result.digit)
        assertTrue(result.score > 0.95)
        assertTrue(result.confidence >= 0.0)
    }

    @Test
    fun `clock recognizer reads synthetic 1 23 clock`() {
        val templates = DigitTemplates((0..9).associateWith { digit(it.toString()) })

        val result = ClockRecognizer(templates).recognize(clock(minute = 1, tens = 2, ones = 3), minConfidence = 0.8)

        assertTrue(result.ok)
        assertEquals(83, result.timeSeconds)
        assertEquals("1:23", result.rawText)
    }

    @Test
    fun `recognition filter rejects time going backwards too quickly and pauses after failures`() {
        val filter = RecognitionFilter(maxFailedReads = 3)
        val accepted = RecognitionResult(true, 80, "1:20", confidence = 1.0)
        val invalid = RecognitionResult(false, reason = "low-confidence")

        assertTrue(filter.update(accepted, 1_000).accepted)
        assertFalse(filter.update(RecognitionResult(true, 70, "1:10", confidence = 1.0), 1_100).accepted)
        assertFalse(filter.update(invalid, 1_200).shouldPause)
        assertTrue(filter.update(invalid, 1_300).shouldPause)
    }

    private fun digit(value: String): PixelImage {
        val patterns = mapOf(
            '0' to listOf("111", "101", "101", "101", "111"),
            '1' to listOf("010", "110", "010", "010", "111"),
            '2' to listOf("111", "001", "111", "100", "111"),
            '3' to listOf("111", "001", "111", "001", "111"),
            '4' to listOf("101", "101", "111", "001", "001"),
            '5' to listOf("111", "100", "111", "001", "111"),
            '6' to listOf("111", "100", "111", "101", "111"),
            '7' to listOf("111", "001", "001", "001", "001"),
            '8' to listOf("111", "101", "111", "101", "111"),
            '9' to listOf("111", "101", "111", "001", "111"),
        )
        val rows = patterns.getValue(value.single())
        val pixels = IntArray(3 * 5) { 0xffffffff.toInt() }
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, cell -> if (cell == '1') pixels[y * 3 + x] = 0xff000000.toInt() } }
        return PixelImage(3, 5, pixels)
    }

    private fun clock(minute: Int, tens: Int, ones: Int): PixelImage {
        val groups = listOf(digit(minute.toString()), colon(), digit(tens.toString()), digit(ones.toString()))
        val gap = 2
        val width = groups.sumOf(PixelImage::width) + gap * (groups.size - 1)
        val pixels = IntArray(width * 7) { 0xffffffff.toInt() }
        var offset = 0
        groups.forEachIndexed { index, group ->
            repeat(group.height) { y ->
                repeat(group.width) { x ->
                    pixels[(y + 1) * width + offset + x] = group[x, y]
                }
            }
            offset += group.width
            if (index != groups.lastIndex) offset += gap
        }
        return PixelImage(width, 7, pixels)
    }

    private fun colon() = PixelImage(1, 5, IntArray(5) { index -> if (index == 1 || index == 3) 0xff000000.toInt() else 0xffffffff.toInt() })
}
