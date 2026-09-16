package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.LabyrinthEventOcrDocument
import com.landosol.toolbox.labyrinth.labyrinthPreservesPendingNodeTransitionAcrossPage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LabyrinthEventFlowRegressionTest {
    private val root = generateSequence(File(".").canonicalFile, File::getParentFile)
        .first { File(it, "android/app/src/main/assets/resource-packs/cn-bilibili/vision.json").isFile }
    private val assets = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `event move screenshot yields confirm target at native and scaled resolutions`() {
        val original = fixture("event-move-confirmation-20260913.png")
        for (frame in listOf(original, scale(original, 1280, 720), scale(original, 1920, 1080))) {
            val result = requireNotNull(LabyrinthNodeMoveConfirmationDetector().detect(frame))
            val rect = result.confirmButtonRect
            assertTrue(result.confidence >= 0.75)
            assertTrue((rect.left + rect.width / 2.0) / frame.width in 0.52..0.71)
            assertTrue((rect.top + rect.height / 2.0) / frame.height in 0.65..0.73)
        }
    }

    @Test
    fun `guild event page is not a movement modal`() {
        assertNull(LabyrinthNodeMoveConfirmationDetector().detect(fixture("event-guild-tank-healer-20260913.png")))
    }

    @Test
    fun `movement modal remains compatible with map ownership with bundled templates`() {
        val paths = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS +
            AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
        val templates = paths.mapNotNull { (id, path) ->
            val file = File(root, "android/app/src/main/assets/$path")
            if (file.isFile) id to readImage(file) else null
        }.toMap()
        val processor = LabyrinthEntryFrameProcessor(LabyrinthEntryTemplateSet(templates))
        val result = processor.process(fixture("event-move-confirmation-20260913.png"))
        assertNotNull(result.nodeMoveConfirmation)
        assertTrue("actual=${result.observation.state}", result.observation.state in setOf(
            LabyrinthEntryPageState.UNKNOWN, LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
        ))
        val event = processor.process(fixture("event-guild-tank-healer-20260913.png"))
        assertEquals("event anchors=${event.observation.anchorScores.values.filterKeys { it.contains("event") }}",
            LabyrinthEntryPageState.EVENT_CHOICE, event.observation.state)
    }

    @Test
    fun `event overlay requires both strong anchors before overriding a clearer background map`() {
        val map = mapOf(EntryAnchorId.NODE_HEADER to 0.95, EntryAnchorId.NODE_RELICS to 0.95,
            EntryAnchorId.NODE_RETURN to 0.95)
        fun scores(title: Double, button: Double) = LabyrinthAnchorScores(map + mapOf(
            EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to title,
            EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to button))
        val classifier = LabyrinthEntryPageClassifier()
        val event = classifier.classify(scores(0.8, 0.85))
        assertEquals(LabyrinthEntryPageState.EVENT_CHOICE, event.state)
        assertEquals(0.8, event.confidence, 0.001)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, classifier.classify(scores(0.95, 0.0)).state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, classifier.classify(scores(0.0, 0.95)).state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION, classifier.classify(scores(0.60, 0.95)).state)
        assertEquals(LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageClassifier(minScore = 0.90).classify(scores(0.70, 0.75)).state)
    }

    @Test
    fun `node tap survives unknown frames before confirmation but expires and rejects unrelated pages`() {
        for (page in listOf(LabyrinthEntryPageState.UNKNOWN, LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS, LabyrinthEntryPageState.PRE_HOME_DATA_LOADING)) {
            assertTrue(labyrinthPreservesPendingNodeTransitionAcrossPage(page, null, 5_999, 6_000, 0))
            assertFalse(labyrinthPreservesPendingNodeTransitionAcrossPage(page, null, 6_000, 6_000, 0))
        }
        assertFalse(labyrinthPreservesPendingNodeTransitionAcrossPage(
            LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION, null, 100, 6_000, 0))
        assertFalse(labyrinthPreservesPendingNodeTransitionAcrossPage(
            LabyrinthEntryPageState.UNKNOWN, null, 100, 6_000))
    }

    @Test
    fun `guild OCR crop contains complete reward line and button centers land on choice buttons`() {
        val frame = fixture("event-guild-tank-healer-20260913.png")
        val document = json.decodeFromString<LabyrinthEventOcrDocument>(File(assets, "labyrinth-event-ocr.json").readText())
        val mapper = LabyrinthEventLayoutMapper(document.layouts)
        val confidences = (1..3).associateWith { count ->
            mapper.buttonRects(count, frame.width, frame.height).map { rect ->
                labyrinthEventBlueButtonConfidence(rect) { x, y -> frame[x, y] }
            }
        }
        assertEquals("button evidence=$confidences", 2, labyrinthCompleteEventLayoutCount(confidences, 0.12))
        assertTrue(confidences.getValue(2).all { it >= 0.18 })
        val crop = requireNotNull(mapper.ocrBounds(2, frame.width, frame.height))
        // Supplied 1056x594 screenshot: final reward line reaches y=449; preserve its descenders.
        assertTrue("crop=$crop", crop.top + crop.height >= 452)
        val rects = mapper.buttonRects(2, frame.width, frame.height)
        assertEquals(2, rects.size)
        for ((index, rect) in rects.withIndex()) {
            val centerX = rect.left + rect.width / 2
            val centerY = rect.top + rect.height / 2
            assertTrue(centerX in (if (index == 0) 315..435 else 620..740))
            assertTrue(centerY in 470..510)
        }
        val events = json.parseToJsonElement(File(assets, "events.placeholder.json").readText())
            .jsonObject.getValue("events").jsonArray.map { json.decodeFromJsonElement<EventResource>(it) }
        val match = LabyrinthEventTextRecognizer(events).recognize(
            "这里是公会管理协会！请问要找什么样的伙伴？ 想要坦克型成为伙伴！想要治疗型成为伙伴！" +
                "坦克型职能的随机印记×2 +阿尔法金币×200 治疗型职能的随机印记×2 +阿尔法金币×200")
        assertEquals("1101", match.event?.id)
        assertTrue("score=${match.score}, margin=${match.margin}", match.trusted)
    }

    @Test
    fun `old OCR callback cannot replace or cancel the new request even for identical images`() {
        val cache = LabyrinthEventOcrCache()
        val key = LabyrinthEventOcrCache.Key(10, EntryPixelRect(10, 10, 100, 100))
        val old = requireNotNull(cache.begin(key, 0))
        cache.clear()
        val fresh = requireNotNull(cache.begin(key, 10))
        cache.complete(old, "old event", 20)
        assertNull(cache.read(key, 20))
        assertNull(cache.begin(key, 21)) // stale callback did not cancel the active request
        cache.complete(fresh, "fresh event", 30)
        assertEquals("fresh event", cache.read(key, 31))
    }

    @Test
    fun `OCR cache expires on static pages and is scoped to crop geometry`() {
        val cache = LabyrinthEventOcrCache()
        val key = LabyrinthEventOcrCache.Key(10, EntryPixelRect(10, 10, 100, 100))
        cache.complete(requireNotNull(cache.begin(key, 0)), "event", 2_000)
        assertEquals("event", cache.read(key, 2_001)) // slow first read is still usable
        assertNull(cache.read(key.copy(rect = EntryPixelRect(20, 10, 100, 100)), 2_001))
        assertEquals("event", cache.read(key, 4_000)) // allow repeated observations on slower frames
        assertNull(cache.read(key, 7_000))
    }

    @Test
    fun `timed out OCR cannot overwrite retry result`() {
        val cache = LabyrinthEventOcrCache()
        val key = LabyrinthEventOcrCache.Key(10, EntryPixelRect(10, 10, 100, 100))
        val old = requireNotNull(cache.begin(key, 0))
        assertNull(cache.begin(key, 4_999))
        val retry = requireNotNull(cache.begin(key, 5_000))
        cache.complete(retry, "retry", 5_100)
        cache.complete(old, "old", 5_200)
        assertEquals("retry", cache.read(key, 5_201))
    }

    private fun fixture(name: String) = readImage(File(root, "android/app/src/test/resources/labyrinth/$name"))

    private fun readImage(file: File): PixelImage {
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun scale(frame: PixelImage, width: Int, height: Int) = PixelImage(width, height,
        IntArray(width * height) { i -> frame[(i % width) * frame.width / width, (i / width) * frame.height / height] })
}
