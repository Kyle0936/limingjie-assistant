package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.GradientTemplateMatcher
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeClassifierFixtureTest {
    @Test
    fun `stable color ROI rejects a same-shape background with the wrong platform colors`() {
        val width = 80
        val height = 100
        fun patterned(bright: Int, dark: Int) = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x / 8 + y / 8) % 2 == 0) bright else dark
            },
        )
        val template = patterned(bright = 0xff00d8ff.toInt(), dark = 0xff003060.toInt())
        val wrongColorFrame = patterned(bright = 0xffffa000.toInt(), dark = 0xff601000.toInt())
        val rect = EntryPixelRect(0, 0, width, height)

        val gradientScore = GradientTemplateMatcher().score(wrongColorFrame, rect, template)
        val colorScore = requireNotNull(stableNodeColorRoiScore(wrongColorFrame, rect, template))
        val classification = LabyrinthNodeClassifier().classifyNode(
            frame = wrongColorFrame,
            iconRect = rect,
            templates = NodeTemplateSet(mapOf("node.link.active.map" to template)),
        )

        assertTrue("gradient=$gradientScore", gradientScore >= LabyrinthNodeClassifier.MIN_CONFIDENCE)
        assertTrue("color=$colorScore", colorScore < 0.75)
        assertEquals(null, classification)
    }

    @Test
    fun `stable color ROI keeps a matching node platform`() {
        val width = 80
        val height = 100
        val template = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                if ((x / 8 + y / 8) % 2 == 0) 0xff00d8ff.toInt() else 0xff003060.toInt()
            },
        )
        val rect = EntryPixelRect(0, 0, width, height)

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = template,
                iconRect = rect,
                // Cyan/blue represents a relic; an active link also needs its pink icon.
                templates = NodeTemplateSet(mapOf("node.relic.active" to template)),
            ),
        )

        assertEquals(1.0, classification.colorRoiScore, 0.0001)
        assertEquals(null, LabyrinthNodeClassifier().classifyNode(
            frame = template,
            iconRect = rect,
            templates = NodeTemplateSet(mapOf("node.link.active.map" to template)),
        ))
    }

    @Test
    fun `matching bottom HUD cannot validate a wrong node body`() {
        val width = 80
        val height = 100
        fun image(nodeColor: Int) = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                when {
                    y >= 78 -> if ((x / 5 + y / 5) % 2 == 0) {
                        0xfff8f8f8.toInt()
                    } else {
                        0xff305080.toInt()
                    }
                    y >= 30 -> nodeColor
                    else -> 0xff101020.toInt()
                }
            },
        )
        val template = image(0xff00d8ff.toInt())
        val wrongNodeWithSameHud = image(0xffffa000.toInt())

        val colorScore = requireNotNull(
            stableNodeColorRoiScore(
                wrongNodeWithSameHud,
                EntryPixelRect(0, 0, width, height),
                template,
            ),
        )

        assertTrue("color=$colorScore", colorScore < 0.75)
    }

    @Test
    fun `bottom node template masks the fixed retreat and return controls`() {
        val width = 80
        val height = 100
        val template = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                val y = index / width
                if (y >= 78) 0xfff8f8f8.toInt() else 0xff203050.toInt()
            },
        )

        val gradientTemplate = NodeTemplateSet(
            mapOf("node.event.inactive.bottom" to template),
        ).gradientTemplates.getValue("node.event.inactive.bottom")

        assertEquals(0xff, gradientTemplate[20, 70] ushr 24 and 0xff)
        assertEquals(0, gradientTemplate[20, 78] ushr 24 and 0xff)
        assertEquals(0, gradientTemplate[20, 99] ushr 24 and 0xff)
        assertEquals(0xff, template[20, 99] ushr 24 and 0xff)
    }

    @Test
    fun `explicit active template without activation glow is rejected`() {
        val width = 80
        val height = 100
        val darkMatchingFrame = PixelImage(
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                if ((index % width / 8 + index / width / 8) % 2 == 0) {
                    0xff403040.toInt()
                } else {
                    0xff181520.toInt()
                }
            },
        )

        val classification = LabyrinthNodeClassifier().classifyNode(
            frame = darkMatchingFrame,
            iconRect = EntryPixelRect(0, 0, width, height),
            templates = NodeTemplateSet(mapOf("node.link.active.map" to darkMatchingFrame)),
        )

        assertEquals(null, classification)
    }

    @Test
    fun `purple EX activation is clickable even when visual template is inactive`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { 0xff18152a.toInt() }
        val sideBand = 50
        val lowerBandStart = 238
        repeat(height) { y ->
            repeat(width) { x ->
                if (x < sideBand || x >= width - sideBand || y >= lowerBandStart) {
                    pixels[y * width + x] = 0xffff22d8.toInt()
                }
            }
        }
        val frame = PixelImage(width, height, pixels)
        val templates = NodeTemplateSet(
            mapOf("node.ex_battle.inactive" to frame),
        )

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = templates,
            ),
        )

        assertTrue(classification.purpleGlowScore >= 0.08)
        assertTrue(classification.purpleGlowRowCoverage >= 0.30)
        assertTrue(classification.purpleGlowSideScore >= 0.06)
        assertTrue(classification.purpleGlowLowerScore >= 0.05)
        assertTrue(classification.isClickable)
        assertFalse(classification.cyanGlowScore >= 0.08)
    }

    @Test
    fun `purple scenery in the center does not count as EX activation`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { 0xff18152a.toInt() }
        repeat(160) { y ->
            repeat(120) { x ->
                pixels[(y + 95) * width + x + 80] = 0xffff22d8.toInt()
            }
        }
        val frame = PixelImage(width, height, pixels)
        val templates = NodeTemplateSet(
            mapOf("node.ex_battle.inactive" to frame),
        )

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = templates,
            ),
        )

        assertTrue(classification.purpleGlowScore < 0.08)
        assertFalse(classification.isClickable)
    }

    @Test
    fun `dark purple map background does not make an inactive EX clickable`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { 0xff18152a.toInt() }
        val sideBand = 50
        val lowerBandStart = 238
        repeat(height) { y ->
            repeat(width) { x ->
                if (x < sideBand || x >= width - sideBand || y >= lowerBandStart) {
                    // This passes the former broad-purple thresholds but is much darker than the
                    // neon magenta frame of a genuinely reachable EX node.
                    pixels[y * width + x] = 0xffba309f.toInt()
                }
            }
        }
        val frame = PixelImage(width, height, pixels)

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = NodeTemplateSet(mapOf("node.ex_battle.inactive" to frame)),
            ),
        )

        assertTrue(classification.purpleGlowScore < 0.06)
        assertFalse(classification.isClickable)
        assertEquals(LabyrinthNodeTypes.EX_BATTLE, classification.blockType)
    }

    @Test
    fun `bright magenta bottom control alone is not an EX activation frame`() {
        val width = 280
        val height = 350
        val pixels = IntArray(width * height) { index ->
            if (index / width >= 238) 0xffff22d8.toInt() else 0xff18152a.toInt()
        }
        val frame = PixelImage(width, height, pixels)

        val classification = requireNotNull(
            LabyrinthNodeClassifier().classifyNode(
                frame = frame,
                iconRect = EntryPixelRect(0, 0, width, height),
                templates = NodeTemplateSet(mapOf("node.ex_battle.inactive" to frame)),
            ),
        )

        assertTrue(classification.purpleGlowLowerScore >= 0.05)
        assertTrue(classification.purpleGlowSideScore < 0.06)
        assertFalse(classification.isClickable)
    }

    @Test
    fun `initial map fixture maps the two active battles to logical column two`() {
        val root = locateProjectRoot()
        val visionRoot = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili/vision")
        val templates = NodeTemplateSet(
            NODE_TEMPLATE_FILES.mapValues { (_, fileName) -> readImage(File(visionRoot, fileName)) },
        )
        val frame = readImage(File(root, "素材/ui/黎明界进入/当前版本_节点选择.png"))

        val detections = LabyrinthNodeClassifier().classifyMapNodes(frame, templates)
        val activeBattles = detections.filter(NodeClassification::isClickable)
        assertEquals(
            "detections=$detections diagnostics=${diagnose(frame, templates)}",
            2,
            activeBattles.size,
        )

        val areaNodes = listOf(
            node(10101, LabyrinthNodeTypes.START),
            node(10201, LabyrinthNodeTypes.NORMAL_BATTLE),
            node(10202, LabyrinthNodeTypes.NORMAL_BATTLE),
        )
        val mappings = NodePositionMapper().mapNodes(
            classifications = detections,
            areaNodes = areaNodes,
            currentColumn = 1,
        )

        assertEquals("detections=$detections mappings=$mappings", setOf(10201L, 10202L), mappings.mapTo(mutableSetOf()) { it.blockId })
        assertTrue(mappings.all { it.screenRect != null })

        val session = LabyrinthNodeSession().apply {
            initialize(
                allNodes = areaNodes,
                route = listOf(areaNodes[0], areaNodes[1]),
                nodeTemplates = templates,
                currentNodeId = 10101,
            )
        }
        val action = session.processClassifications(detections) as NodeAction.ClickNode
        assertEquals(10201L, action.blockId)
        assertEquals(mappings.single { it.blockId == 10201L }.screenRect, action.screenRect)
    }

    private fun node(blockId: Long, blockType: Int) = LabyrinthMapNode(
        area = 1,
        column = (blockId / 100 % 100).toInt(),
        row = (blockId % 100).toInt(),
        blockId = blockId,
        blockType = blockType,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = false,
    )

    private fun diagnose(frame: PixelImage, templates: NodeTemplateSet): List<String> {
        val matcher = GradientTemplateMatcher(maxSamples = 2_400)
        return NodeAnchorDefinitions.visibleNodeRects(frame.width, frame.height)
            .flatMap { candidate ->
                NodeAnchorDefinitions.searchOffsets(frame.width, frame.height).mapNotNull { (dx, dy) ->
                    val rect = candidate.rect.offsetInside(dx, dy, frame.width, frame.height)
                        ?: return@mapNotNull null
                    templates.templates.map { (id, template) ->
                        val raw = matcher.score(frame, rect, template)
                        val gated = matcher.score(frame, rect, template, minimumChannelScore = 0.35)
                        Triple(raw, gated, "${candidate.column}/${candidate.row}+$dx/$dy:$id@$rect")
                    }
                }.flatten()
            }
            .sortedByDescending { it.first }
            .filter { (_, _, label) -> "node.normal_battle.active" in label }
            .take(30)
            .map { (raw, gated, label) -> "raw=${"%.3f".format(raw)},g35=${"%.3f".format(gated)}:$label" }
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
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var current = File(workingDirectory).absoluteFile
        repeat(8) {
            if (File(current, "素材/ui/黎明界进入/当前版本_节点选择.png").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
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

private fun EntryPixelRect.offsetInside(
    dx: Int,
    dy: Int,
    frameWidth: Int,
    frameHeight: Int,
): EntryPixelRect? {
    val movedLeft = left + dx
    val movedTop = top + dy
    if (movedLeft < 0 || movedTop < 0 || movedLeft + width > frameWidth || movedTop + height > frameHeight) {
        return null
    }
    return EntryPixelRect(movedLeft, movedTop, width, height)
}
