package com.landosol.toolbox.clanbattle.recognition

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedControlTemplateLoaderTest {
    @Test
    fun `owned control templates are complete and pass integrity checks`() {
        assertEquals(
            setOf("auto_on", "auto_off", "set_on", "set_off", "role_set_on", "menu"),
            EmbeddedControlTemplateLoader.encodedTemplates.keys,
        )
        EmbeddedControlTemplateLoader.encodedTemplates.values.forEach { source ->
            val bytes = Base64.getDecoder().decode(source.base64)
            assertTrue(bytes.size > 54)
            assertEquals('B'.code.toByte(), bytes[0])
            assertEquals('M'.code.toByte(), bytes[1])
            assertEquals(source.sha256, bytes.sha256())
        }
    }

    @Test
    fun `owned battle stage templates are complete and pass integrity checks`() {
        assertEquals(setOf("start_battle", "loading"), EmbeddedBattleTemplateLoader.encodedTemplates.keys)
        EmbeddedBattleTemplateLoader.encodedTemplates.values.forEach { source ->
            val bytes = Base64.getDecoder().decode(source.base64)
            assertTrue(bytes.size > 54)
            assertEquals('B'.code.toByte(), bytes[0])
            assertEquals('M'.code.toByte(), bytes[1])
            assertEquals(source.sha256, bytes.sha256())
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
