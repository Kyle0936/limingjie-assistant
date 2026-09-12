package com.landosol.toolbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun `product identity does not use legacy project name`() {
        val productName = "黎明界助手"

        assertFalse(productName.contains("AutoPCR", ignoreCase = true))
        assertNotEquals("com.autopcr.android", BuildConfig.APPLICATION_ID)
    }
}

