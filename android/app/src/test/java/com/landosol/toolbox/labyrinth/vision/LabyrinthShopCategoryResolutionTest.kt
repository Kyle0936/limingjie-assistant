package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.LabyrinthShopDecision
import com.landosol.toolbox.labyrinth.LabyrinthShopPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlinx.serialization.json.*

class LabyrinthShopCategoryResolutionTest {
    @Test fun `logged screenshot and OCR readings classify all three imprints against packaged relic catalog`() {
        val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { File(it, "android/app/src/test/resources/shop/imprints-20260914.jpg").isFile }
        val resources = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
        val templates = File(resources, "icons/relics").listFiles()!!.filter { it.extension == "png" }.map {
            LabyrinthRelicTemplate(it.nameWithoutExtension, it.nameWithoutExtension, "弱体", "1", "", readImage(it))
        }
        val raw = LabyrinthShopRecognizer(readImage(File(resources, "vision/shop/shop_buy_button.png")), templates)
            .recognize(readImage(File(root, "android/app/src/test/resources/shop/imprints-20260914.jpg")))
        val log = Json.parseToJsonElement(File(root, "android/app/src/test/resources/shop/imprints-20260914.json").readText()).jsonObject
        val titles = log.getValue("items").jsonArray.map { it.jsonObject.getValue("titleText").jsonPrimitive.content }
        assertEquals(3, raw.items.size)
        assertTrue(raw.items.all { it.purchasable })
        val classified = raw.items.mapIndexed { i, item -> resolver.resolve(item, titles[i], i.toLong(), 0) }
        assertTrue(classified.all { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch == null })
        assertEquals("增益型职能印记", classified[2].roleImprintLabel)
        assertTrue(decide(classified) is LabyrinthShopDecision.BuyRoleImprint)
    }

    private fun readImage(file: File): PixelImage {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
    private val resolver = LabyrinthShopCategoryResolution()
    private val rect = EntryPixelRect(0, 0, 100, 100)
    private fun item(id: String = "3", recognized: Boolean = false) = LabyrinthShopItemMatch(
        id, rect, rect, 0.99, 1.0, LabyrinthShopItemStatus.AVAILABLE,
        relicMatch = LabyrinthRelicMatch(slotId = id, relicId = if (recognized) "40005" else null,
            displayName = "候选遗物", attribute = "弱体", attributeBonus = "1", effect = null,
            confidence = 0.715, screenRect = rect, iconRect = rect, rivalMargin = 0.000338),
    )
    private fun decide(items: List<LabyrinthShopItemMatch>, area: Int = 3, purchased: Int = 0) =
        LabyrinthShopPolicy().decide(area, items, emptyList(), refreshAvailable = true, relicPurchasesInCycle = purchased)

    @Test fun `title classification overrides even a recognized relic icon`() {
        val result = resolver.resolve(item(recognized = true), "增益型识能的随机印记", 22, 0)
        assertEquals(LabyrinthShopItemKind.ROLE_IMPRINT, result.kind)
        assertEquals("增益型职能印记", result.roleImprintLabel)
        assertNull(result.relicMatch)
    }

    @Test fun `relic cannot be bought until its title was checked`() {
        assertTrue(decide(listOf(resolver.resolve(item(recognized = true), null, null, 0))) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(resolver.resolve(item(recognized = true), "候选遗物", 1, 0))) is LabyrinthShopDecision.BuyRelic)
    }

    @Test fun `variant advances on completed OCR, not on elapsed time`() {
        // A pending read (completed = false) never advances the variant, however long it waits:
        // this is the bundle-134750 bug, where a 3 s clock retired titles whose OCR was still
        // in flight and closed the shop after one or two buys.
        assertEquals(0, resolver.attempt("3", 100, 0))
        resolver.noteVariantOutcome("3", 100, 0, completed = false, now = 3_000)
        assertEquals(0, resolver.attempt("3", 100, 3_000))
        resolver.noteVariantOutcome("3", 100, 0, completed = false, now = 8_000)
        assertEquals(0, resolver.attempt("3", 100, 8_000))
        // A completed-but-unresolved read retires the variant.
        resolver.noteVariantOutcome("3", 100, 0, completed = true, now = 8_500)
        assertEquals(1, resolver.attempt("3", 100, 8_600))
        resolver.noteVariantOutcome("3", 100, 1, completed = true, now = 9_000)
        assertEquals(2, resolver.attempt("3", 100, 9_100))
        resolver.noteVariantOutcome("3", 100, 2, completed = true, now = 9_500)
        assertEquals(3, resolver.attempt("3", 100, 9_600))
        // A stale variant index cannot double-advance, and EXHAUSTED never advances further.
        resolver.noteVariantOutcome("3", 100, 1, completed = true, now = 9_700)
        assertEquals(3, resolver.attempt("3", 100, 9_800))
        // A new fingerprint and clear() both reset the counter.
        assertEquals(0, resolver.attempt("3", 101, 10_000))
        resolver.clear()
        assertEquals(0, resolver.attempt("3", 101, 10_001))
    }

    @Test fun `a stalled OCR engine still advances after the backstop so the shop cannot hang`() {
        assertEquals(0, resolver.attempt("s", 7, 0))
        resolver.noteVariantOutcome("s", 7, 0, completed = false, now = 11_999)
        assertEquals(0, resolver.attempt("s", 7, 11_999))
        resolver.noteVariantOutcome("s", 7, 0, completed = false, now = 12_000)
        assertEquals(1, resolver.attempt("s", 7, 12_000))
    }

    @Test fun `three completed unresolved reads skip item without assuming exhausted relic stock`() {
        val imprint = resolver.resolve(item("1"), "攻击型职能的随机印记", 1, 0)
        assertTrue(decide(listOf(imprint, resolver.resolve(item(), "看不清", 2, 0))) is LabyrinthShopDecision.Wait)
        val skipped = resolver.resolve(item(), "看不清", 4, 3)
        assertTrue(decide(listOf(imprint, skipped), area = 5) is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue(decide(listOf(skipped), area = 5) is LabyrinthShopDecision.Close)
    }

    @Test fun `remaining titles finish bounded reading after third relic purchase`() {
        assertTrue(decide(listOf(resolver.resolve(item(), null, null, 0)), purchased = 3) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(resolver.resolve(item(), null, null, 3)), purchased = 3) is LabyrinthShopDecision.Close)
    }

    @Test fun `three imprints recover stock and follow area refresh rules`() {
        val items = listOf("攻击型职能的随机印记", "治疗型职能的随机印记", "增益型识能的随机印记")
            .mapIndexed { i, title -> resolver.resolve(item(i.toString()), title, i.toLong(), 0) }
        assertTrue(decide(items, 3) is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue(decide(items, 5) is LabyrinthShopDecision.Refresh)
    }
}
