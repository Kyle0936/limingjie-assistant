package com.landosol.toolbox.gamedata

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameIconAndroidTest {
    @Test
    fun bundledPngAndWebpIconsDecodeOnAndroid() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val files = listOf(
            "resource-packs/cn-bilibili/icons/characters/icon_unit_100131.png",
            "resource-packs/cn-bilibili/icons/characters/icon_unit_100661.webp",
            "resource-packs/cn-bilibili/icons/relics/11001.png",
        )

        files.forEach { file ->
            val bitmap = assets.open(file).use(BitmapFactory::decodeStream)
            assertNotNull("failed to decode $file", bitmap)
            assertEquals("unexpected width for $file", 128, bitmap.width)
            assertEquals("unexpected height for $file", 128, bitmap.height)
            bitmap.recycle()
        }
    }
}
