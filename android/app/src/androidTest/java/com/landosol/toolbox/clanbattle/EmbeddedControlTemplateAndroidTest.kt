package com.landosol.toolbox.clanbattle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.landosol.toolbox.clanbattle.recognition.EmbeddedControlTemplateLoader
import com.landosol.toolbox.clanbattle.recognition.EmbeddedBattleTemplateLoader
import com.landosol.toolbox.clanbattle.recognition.EmbeddedDigitTemplateLoader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedControlTemplateAndroidTest {
    @Test
    fun embeddedTemplatesDecodeOnAndroid() {
        val controls = EmbeddedControlTemplateLoader.load()
        val battle = EmbeddedBattleTemplateLoader.load()
        val digits = EmbeddedDigitTemplateLoader.load()
        assertTrue(controls.controls.autoOn.width > 0)
        assertTrue(controls.controls.roleSetOn.height > 0)
        assertTrue(controls.menu.width > 0)
        assertTrue(battle.startBattle.width > 0)
        assertTrue(battle.loading.height > 0)
        assertTrue(digits.digits.size == 10)
    }
}
