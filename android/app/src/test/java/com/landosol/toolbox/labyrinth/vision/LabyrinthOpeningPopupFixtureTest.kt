package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 2026-09-17 user screenshots (scaled to 1080p): the 拉比林斯 opening grant popup (角色加入
 * 菈比莉斯塔) and the 迷宫大师 opening relic popup (迷宫遗物效果). Both must be recognised as
 * closeable pages so the opening chain keeps advancing.
 */
class LabyrinthOpeningPopupFixtureTest {
    private val projectRoot = generateSequence(File(".").canonicalFile, File::getParentFile).take(6)
        .firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
    private val assetRoot = projectRoot?.let { File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/vision") }
    private val fixtureRoot = projectRoot?.let { File(it, "android/app/src/test/resources/labyrinth") }

    @Test
    fun `relic effect popup classifies as item reward with the shared close button`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "relic-effect-popup-20260917.png")))
        assertEquals(result.observation.stateScores.toString(), LabyrinthEntryPageState.ITEM_REWARD, result.observation.state)
        val close = requireNotNull(result.anchorMatches[EntryAnchorId.ITEM_REWARD_CLOSE])
        assertTrue("close ${close.score}", close.score >= 0.90)
        assertTrue(result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE] >= 0.90)
        assertTrue(result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_INSTRUCTION] >= 0.90)
    }

    @Test
    fun `guild grant popup classifies as character joined`() {
        assumeTrue(fixtureRoot?.isDirectory == true)
        val processor = LabyrinthEntryFrameProcessor(templates = loadShippedTemplates())
        val result = processor.process(readImage(File(fixtureRoot, "guild-bonus-joined-20260917.png")))
        assertEquals(LabyrinthEntryPageState.CHARACTER_JOINED, result.observation.state)
        // The new relic-effect anchors must not fire on the joined popup.
        assertTrue(result.observation.anchorScores[EntryAnchorId.RELIC_EFFECT_TITLE] < 0.50)
    }

    private fun loadShippedTemplates(): LabyrinthEntryTemplateSet {
        val all = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS + AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val prefix = "resource-packs/cn-bilibili/vision/"
        return LabyrinthEntryTemplateSet(
            all.mapNotNull { (id, path) ->
                val file = File(assetRoot, path.removePrefix(prefix))
                if (file.isFile) id to readImage(file) else null
            }.toMap(),
        )
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
