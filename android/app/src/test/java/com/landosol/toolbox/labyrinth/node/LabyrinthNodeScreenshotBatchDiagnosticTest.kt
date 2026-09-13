package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeScreenshotBatchDiagnosticTest {
    @Test
    fun `area two dashboard relic and shop retain identity and stable tracking`() {
        val root = locateProjectRoot()
        val screenshot = readImage(File(root, "android/app/src/test/resources/labyrinth/node-shop-relic-dashboard.png"))
        // Manual dashboard capture, not a native game frame. Remove the dashboard chrome and
        // resize just the game viewport back to its declared 1920x1080 coordinate system.
        val frame = PixelImage(1920, 1080, IntArray(1920 * 1080) { index ->
            screenshot[(index % 1920) * 1484 / 1920, 23 + (index / 1920) * 835 / 1080]
        })
        val visionRoot = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili/vision")
        val templates = NodeTemplateSet(NODE_TEMPLATE_FILES.mapValues { (_, name) -> readImage(File(visionRoot, name)) })
        val classifier = LabyrinthNodeClassifier()
        val started = System.nanoTime()
        val first = classifier.classifyMapNodes(frame, templates)
        val fullMillis = (System.nanoTime() - started) / 1_000_000
        println("dashboard detections=$first fullMs=$fullMillis")
        assertEquals(listOf(LabyrinthNodeTypes.RELIC, LabyrinthNodeTypes.SHOP),
            first.filter { it.isClickable }.map { it.blockType })
        val active = first.filter { it.isClickable }
        assertTrue(active.all { requireNotNull(it.screenRect).left in 1300..1350 })
        assertTrue(requireNotNull(active[0].screenRect).top in 185..220)
        assertTrue(requireNotNull(active[1].screenRect).top in 485..520)
        val trackedStarted = System.nanoTime()
        val tracked = classifier.classifyMapNodes(frame, templates)
        println("dashboard trackedMs=${(System.nanoTime() - trackedStarted) / 1_000_000} mode=${classifier.lastSearchMode}")
        assertEquals("tracked", classifier.lastSearchMode)
        assertEquals(first.map { it.blockType }, tracked.map { it.blockType })
        val changed = PixelImage(frame.width, frame.height, IntArray(frame.width * frame.height) { 0xff000000.toInt() })
        assertTrue(classifier.classifyMapNodes(changed, templates).isEmpty())
        assertEquals("full", classifier.lastSearchMode)
        classifier.resetTracking()
        classifier.classifyMapNodes(frame, templates)
        assertEquals("full", classifier.lastSearchMode)
    }

    @Test
    fun `classify every Test Screenshots frame`() {
        val root = locateProjectRoot()
        val screenshotRoot = File(root, "Test_Screenshots")
        assumeTrue("local Test_Screenshots directory is unavailable", screenshotRoot.isDirectory)
        val screenshots = screenshotRoot.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            .sortedBy(File::getName)
        assertEquals(17, screenshots.size)

        val visionRoot = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili/vision")
        val templates = NodeTemplateSet(
            NODE_TEMPLATE_FILES.mapValues { (_, fileName) -> readImage(File(visionRoot, fileName)) },
        )
        val classifier = LabyrinthNodeClassifier()
        System.getenv("LABYRINTH_DIAGNOSTIC_SCREENSHOT")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { screenshot ->
                val detections = classifier.classifyMapNodes(readImage(screenshot), templates)
                println("diagnostic-screenshot ${screenshot.name}: $detections")
            }
        val detectionsByFile = screenshots.associate { screenshot ->
            val frame = readImage(screenshot)
            val detections = classifier.classifyMapNodes(frame, templates)
            assertTrue("Invalid dimensions: $screenshot", frame.width > 0 && frame.height > 0)
            detections.forEach { detection ->
                assertTrue("Invalid confidence in ${screenshot.name}: $detection", detection.confidence.isFinite())
                assertTrue("Missing rect in ${screenshot.name}: $detection", detection.screenRect != null)
            }
            screenshot.name to detections
        }

        assertClickableTypes(
            detectionsByFile,
            "MuMu-20260820-023258-938.png",
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.NORMAL_BATTLE,
        )
        assertClickableTypes(
            detectionsByFile,
            "MuMu-20260821-144152-092.png",
            LabyrinthNodeTypes.LINK,
            LabyrinthNodeTypes.EVENT,
        )
        assertClickableTypes(
            detectionsByFile,
            "MuMu-20260822-150505-982.png",
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE,
        )
        assertClickableTypes(
            detectionsByFile,
            "MuMu-20260822-153701-157.png",
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE,
        )
        assertClickableTypes(
            detectionsByFile,
            "MuMu-20260823-001443-771.png",
            LabyrinthNodeTypes.RELIC,
        )
        // The refined search also recovers the two real inactive events at the left. They
        // contribute topology, but must never become extra active targets over the scenery.
        val relicMap = detectionsByFile.getValue("MuMu-20260823-001443-771.png")
        assertEquals(listOf(LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.EVENT),
            relicMap.filterNot { it.isClickable }.map { it.blockType })
        assertTrue(relicMap.filterNot { it.isClickable }.all { requireNotNull(it.screenRect).left < 350 })
        assertClickableTypes(
            detectionsByFile,
            "MuMu-20260823-022330-010.png",
            LabyrinthNodeTypes.EVENT,
        )
    }

    private fun assertClickableTypes(
        detectionsByFile: Map<String, List<NodeClassification>>,
        fileName: String,
        vararg expected: Int,
    ) {
        val detections = detectionsByFile.getValue(fileName)
        assertEquals(
            "$fileName detections=$detections",
            expected.toList(),
            detections.filter(NodeClassification::isClickable).map(NodeClassification::blockType),
        )
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(
            imageIo.getMethod("read", File::class.java).invoke(null, file),
        ) { "Cannot read $file" }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(current, "android/app/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root")
    }

    private companion object {
        val NODE_TEMPLATE_FILES = mapOf(
            "node.normal_battle.active" to "node_normal_battle_active.png",
            "node.normal_battle.active.bottom" to "node_normal_battle_active_bottom.png",
            "node.normal_battle.inactive" to "node_normal_battle_inactive.png",
            "node.normal_battle.inactive.bottom" to "node_normal_battle_inactive_bottom.png",
            "node.normal_battle.inactive.video" to "node_normal_battle_inactive_video.png",
            "node.ex_battle.inactive" to "node_ex_battle_inactive.png",
            "node.boss.yellow" to "node_boss_yellow.png",
            "node.boss.dragon" to "node_boss_dragon.png",
            "node.boss.platform" to "node_boss_platform.png",
            "node.clear" to "node_clear.png",
            "node.relic.active" to "node_relic_active.png",
            "node.relic.inactive" to "node_relic_inactive.png",
            "node.relic.map" to "node_relic_map.png",
            "node.event.active" to "node_event_active.png",
            "node.event.active.bottom" to "node_event_active_bottom.png",
            "node.event.inactive" to "node_event_inactive.png",
            "node.event.inactive.bottom" to "node_event_inactive_bottom.png",
            "node.link.active" to "node_link_active.png",
            "node.link.active.map" to "node_link_map.png",
            "node.link.inactive" to "node_link_inactive.png",
            "node.shop.active" to "node_shop_active.png",
        )
    }
}
