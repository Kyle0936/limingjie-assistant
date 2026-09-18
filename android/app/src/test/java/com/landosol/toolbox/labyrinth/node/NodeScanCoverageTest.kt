package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.GradientTemplateMatcher
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan grid must be dense enough that a visible node is always reachable.
 *
 * 2026-09-16 live: a run sat on 连结#10503 for minutes reporting
 * "target-geometry-visible-but-classification-missing" while two EVENT nodes stood unobstructed in
 * the middle of the frame. They were not occluded, dim or off-screen; no search window ever landed
 * close enough to them to score.
 */
class NodeScanCoverageTest {
    @Test
    fun `node templates only score within a few pixels of the node`() {
        val frame = readImage(File(locateRoot(), FIXTURE))
        val templates = loadTemplates()
        val template = requireNotNull(templates.gradientTemplates["node.event.inactive.bottom"])
        val matcher = GradientTemplateMatcher()
        fun scoreAt(dx: Int) = matcher.score(
            frame,
            EntryPixelRect(EVENT_BOTTOM_LEFT + dx, EVENT_BOTTOM_TOP, 280, 350),
            template,
            0.0,
        )

        // The peak is sharp: acceptance sits at 0.60 and the score has already fallen below it
        // eight pixels away. This is why grid spacing, not template quality, decides whether a
        // node can be seen at all.
        assertTrue("peak=${scoreAt(0)}", scoreAt(0) >= 0.60)
        assertTrue("8px=${scoreAt(8)}", scoreAt(8) < 0.60)
        assertTrue("28px=${scoreAt(28)}", scoreAt(28) < 0.35)
    }

    @Test
    fun `horizontal scan offsets leave no gap wider than the template can tolerate`() {
        fun widestGap(dense: Boolean): Int = NodeAnchorDefinitions
            .searchOffsets(1920, 1080, dense = dense)
            .map { it.first }
            .distinct()
            .sorted()
            .zipWithNext()
            .maxOf { (left, right) -> right - left }

        // Local refinement reaches roughly +/-17 px from a proposal, so a gap of 34 is still
        // covered from one side or the other. The coarse list jumps 80 px between 160 and 320,
        // which is why an unobstructed node could be unreachable for the whole scan.
        assertTrue("dense gap=${widestGap(true)}", widestGap(true) <= 34)
        assertTrue("coarse gap=${widestGap(false)}", widestGap(false) > 34)
    }

    @Test
    fun `plainly visible event nodes are detected`() {
        val frame = readImage(File(locateRoot(), FIXTURE))
        val detections = LabyrinthNodeClassifier().classifyMapNodes(frame, loadTemplates())

        fun detectedNear(left: Int, top: Int) = detections.any { node ->
            val rect = node.screenRect ?: return@any false
            node.blockType == LabyrinthNodeTypes.EVENT &&
                kotlin.math.abs(rect.left - left) <= 24 && kotlin.math.abs(rect.top - top) <= 24
        }

        assertTrue("middle event missing: $detections", detectedNear(605, 350))
        assertTrue("bottom event missing: $detections", detectedNear(EVENT_BOTTOM_LEFT, EVENT_BOTTOM_TOP))
        // The glowing relic that was already found must not be lost to the denser grid.
        assertTrue(
            "active relic missing: $detections",
            detections.any { it.isClickable && it.blockType == LabyrinthNodeTypes.RELIC },
        )
    }

    private fun loadTemplates(): NodeTemplateSet {
        val visionRoot = File(locateRoot(), "android/app/src/main/assets/resource-packs/cn-bilibili/vision")
        return NodeTemplateSet(TEMPLATE_FILES.mapValues { (_, name) -> readImage(File(visionRoot, name)) })
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(imageIo.getMethod("read", File::class.java).invoke(null, file)) { "Cannot read $file" }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(current, "android/app/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root")
    }

    private companion object {
        const val FIXTURE = "android/app/src/test/resources/labyrinth/node-target-missed-20260916.jpg"
        const val EVENT_BOTTOM_LEFT = 605
        const val EVENT_BOTTOM_TOP = 650

        val TEMPLATE_FILES = mapOf(
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
