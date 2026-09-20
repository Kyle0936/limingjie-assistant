package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import kotlin.math.roundToInt

/**
 * 节点操作类型
 */
sealed interface NodeAction {
    data class ClickNode(
        val blockId: Long,
        val blockType: Int,
        val screenRect: EntryPixelRect?,
    ) : NodeAction

    data object WaitForLoad : NodeAction
    data class TypeConflict(
        val blockId: Long,
        val expectedBlockType: Int,
        val detectedBlockType: Int,
        val screenRect: EntryPixelRect?,
        val confidence: Double,
        val topologyConfidence: Double? = null,
        val topologyBindingKind: NodeTopologyBindingKind? = null,
    ) : NodeAction
    data object Complete : NodeAction
    data class Error(val message: String) : NodeAction
}

/**
 * 黎明界节点操作规划器
 * 根据匹配结果决定下一步操作
 */
class LabyrinthNodeActionPlanner {

    /**
     * 规划下一步操作
     * @param matchResult 节点匹配结果
     * @return 操作建议
     */
    fun planAction(matchResult: NodeMatchResult): NodeAction {
        // 已完成当前区域
        if (matchResult.isComplete) {
            return NodeAction.Complete
        }

        // 没有下一个节点
        if (matchResult.nextNode == null) {
            return NodeAction.Error("无法找到下一个节点")
        }

        val nextNode = matchResult.nextNode
        val targetMappings = matchResult.visibleNodes
            .filter { it.blockId == nextNode.blockId }
        val activeTargetMappings = targetMappings.filter { mapping ->
            mapping.isClickable && mapping.screenRect != null
        }
        val inactiveRouteTopologyFallback = matchResult.topologyMappings
            .asSequence()
            .filter { topology ->
                topology.blockId == nextNode.blockId &&
                    !topology.isClickable &&
                    topology.expectedBlockType == nextNode.blockType &&
                    topology.detectedBlockType == nextNode.blockType &&
                    topology.visualConfidence >= INACTIVE_ROUTE_TARGET_MIN_VISUAL_CONFIDENCE &&
                    topology.topologyConfidence >= INACTIVE_ROUTE_TARGET_MIN_TOPOLOGY_CONFIDENCE &&
                    topology.bindingKind != NodeTopologyBindingKind.SINGLE_TARGET
            }
            .filter { target ->
                when (target.bindingKind) {
                    NodeTopologyBindingKind.FULL_COLUMN ->
                        target.topologyConfidence >= TOPOLOGY_ORDERED_OVERRIDE_MIN_CONFIDENCE

                    NodeTopologyBindingKind.PARTIAL_COLUMN,
                    NodeTopologyBindingKind.REACHABLE_SUBSET,
                    -> matchResult.topologyMappings.any { sibling ->
                        sibling.blockId != target.blockId &&
                            sibling.logicalColumn == target.logicalColumn &&
                            sibling.visualColumn == target.visualColumn &&
                            sibling.visualRow != target.visualRow &&
                            sibling.detectedBlockType == sibling.expectedBlockType &&
                            sibling.visualConfidence >= INACTIVE_ROUTE_SIBLING_MIN_VISUAL_CONFIDENCE &&
                            sibling.topologyConfidence >= INACTIVE_ROUTE_SIBLING_MIN_TOPOLOGY_CONFIDENCE
                    }

                    NodeTopologyBindingKind.SINGLE_TARGET -> false
                }
            }
            .maxWithOrNull(
                compareBy<NodeTopologyMapping> { it.topologyConfidence }
                    .thenBy { it.visualConfidence },
            )
        fun hasStrongOrderedTopology(mapping: NodePositionMapping): Boolean {
            if ((mapping.topologyConfidence ?: 0.0) < TOPOLOGY_ORDERED_OVERRIDE_MIN_CONFIDENCE) {
                return false
            }
            return when (mapping.topologyBindingKind) {
                NodeTopologyBindingKind.FULL_COLUMN -> true
                NodeTopologyBindingKind.PARTIAL_COLUMN,
                NodeTopologyBindingKind.REACHABLE_SUBSET,
                -> matchResult.visibleNodes.any { sibling ->
                        sibling.column == mapping.column &&
                            sibling.blockId != mapping.blockId &&
                            sibling.isClickable &&
                            sibling.detectedBlockType == sibling.blockType &&
                            (sibling.topologyConfidence ?: 0.0) >= TOPOLOGY_ORDERED_OVERRIDE_MIN_CONFIDENCE
                    }
                NodeTopologyBindingKind.SINGLE_TARGET,
                null,
                -> false
            }
        }
        val strongOrderedTopology = targetMappings
            .filter { mapping ->
                mapping.isClickable &&
                    hasStrongOrderedTopology(mapping)
            }
            .maxByOrNull { it.topologyConfidence ?: 0.0 }

        // A route blockId tells us which logical node should be selected; it must not be allowed
        // to rewrite a contradictory visual crop by itself. The exception is a high-confidence
        // FULL_COLUMN topology binding: exact column cardinality + vertical order is stronger than
        // the currently noisy semantic template classifier. Reachable-subset/single-target
        // topology remains conservative and still stops on TYPE_CONFLICT.
        matchResult.conflicts
            .filter { it.blockId == nextNode.blockId }
            .maxByOrNull(NodePositionConflict::confidence)
            ?.let { conflict ->
                // A contradictory inactive crop is only positional evidence. It must not stop
                // execution as a semantic conflict because the real reachable node may simply
                // not have lit up yet. Only an actually active/clickable crop can establish a
                // TYPE_CONFLICT; otherwise wait for a fresh frame.
                if (!conflict.sourceClickable) {
                    return NodeAction.WaitForLoad
                }
                val conflictMapping = targetMappings
                    .filter { mapping ->
                        mapping.isClickable &&
                            mapping.screenRect != null &&
                            mapping.screenRect == conflict.screenRect &&
                            mapping.detectedBlockType == conflict.detectedBlockType
                    }
                    .maxByOrNull { it.topologyConfidence ?: 0.0 }
                val purpleExTopology = targetMappings.any { mapping ->
                    mapping.isClickable &&
                        mapping.screenRect != null &&
                        mapping.screenRect == conflict.screenRect &&
                        mapping.topologyBindingKind != NodeTopologyBindingKind.SINGLE_TARGET &&
                        (mapping.topologyConfidence ?: 0.0) >= TOPOLOGY_PURPLE_EX_OVERRIDE_MIN_CONFIDENCE
                }
                val purpleExConflict =
                    nextNode.blockType == LabyrinthNodeTypes.EX_BATTLE &&
                        conflict.expectedBlockType == LabyrinthNodeTypes.EX_BATTLE &&
                        conflict.detectedBlockType == LabyrinthNodeTypes.LINK &&
                        conflict.purpleGlowScore >= PURPLE_EX_OVERRIDE_MIN_GLOW_SCORE &&
                        purpleExTopology
                val linkRelicSemanticSwap = setOf(
                    conflict.expectedBlockType,
                    conflict.detectedBlockType,
                ) == setOf(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC)
                val linkRelicTopology = conflictMapping?.let { mapping ->
                    val topologyConfidence = mapping.topologyConfidence ?: 0.0
                    if (
                        topologyConfidence < LINK_RELIC_OVERRIDE_MIN_CONFIDENCE ||
                        mapping.topologyBindingKind == null ||
                        mapping.topologyBindingKind == NodeTopologyBindingKind.SINGLE_TARGET
                    ) {
                        false
                    } else {
                        when (mapping.topologyBindingKind) {
                            NodeTopologyBindingKind.FULL_COLUMN -> true
                            NodeTopologyBindingKind.PARTIAL_COLUMN,
                            NodeTopologyBindingKind.REACHABLE_SUBSET,
                            -> matchResult.visibleNodes.any { sibling ->
                                sibling.blockId != mapping.blockId &&
                                    sibling.column == mapping.column &&
                                    sibling.screenRect != null &&
                                    sibling.topologyBindingKind != NodeTopologyBindingKind.SINGLE_TARGET &&
                                    (sibling.topologyConfidence ?: 0.0) >= LINK_RELIC_SIBLING_MIN_CONFIDENCE
                            }
                            NodeTopologyBindingKind.SINGLE_TARGET,
                            null,
                            -> false
                        }
                    }
                } == true
                // LINK and RELIC are the one known semantic pair whose active map assets are
                // historically named opposite each other and whose cyan platform geometry is
                // extremely similar. Route metadata still cannot override an isolated crop: the
                // same screen rect must be independently bound by ordered topology, and partial
                // columns require a second mapped sibling to prove the vertical ordering.
                if (
                    !labyrinthBossSemanticConflict(conflict.expectedBlockType, conflict.detectedBlockType) &&
                    (
                        strongOrderedTopology != null ||
                            purpleExConflict ||
                            (linkRelicSemanticSwap && linkRelicTopology)
                        )
                ) return@let
                return NodeAction.TypeConflict(
                    blockId = conflict.blockId,
                    expectedBlockType = conflict.expectedBlockType,
                    detectedBlockType = conflict.detectedBlockType,
                    screenRect = conflict.screenRect,
                    confidence = conflict.confidence,
                    topologyConfidence = conflictMapping?.topologyConfidence,
                    topologyBindingKind = conflictMapping?.topologyBindingKind,
                )
            }

        // Normally an inactive crop remains diagnostic-only. One bounded exception handles the
        // post-battle/reward map state seen in production: ordered topology can identify the saved
        // route target exactly while the cyan activation detector temporarily misses the node.
        // The fallback requires route ID + semantic type + strong topology + strong visual match;
        // partial/reachable columns additionally need a second correctly typed sibling proving the
        // vertical ordering. The session still requires consecutive stable click frames afterwards.
        if (
            targetMappings.isNotEmpty() &&
            activeTargetMappings.isEmpty() &&
            inactiveRouteTopologyFallback == null
        ) {
            return NodeAction.WaitForLoad
        }

        // 地图平移后逻辑列与屏幕列并不相等；没有视觉匹配时绝不合成坐标盲点。
        val screenRect = activeTargetMappings
            .asSequence()
            // A route target can be represented more than once while overlapping search
            // windows settle. Prefer the active-template mapping and then its confidence; the
            // first mapping is not a safe choice when one crop is horizontally shifted.
            .maxWithOrNull(
                compareBy<NodePositionMapping> {
                    hasStrongOrderedTopology(it)
                }
                    .thenBy { it.screenRect != null }
                    .thenBy { it.isClickable }
                    .thenBy { it.confidence },
            )
            ?.screenRect
            ?: inactiveRouteTopologyFallback?.screenRect

        return NodeAction.ClickNode(
            blockId = nextNode.blockId,
            blockType = nextNode.blockType,
            screenRect = screenRect,
        )
    }

    /**
     * 计算节点点击位置。
     *
     * 节点检测框包含主体、基座以及较多下方背景。实机上可点击的图标/台座集中
     * 在框的上半段；取矩形中心以下会落到节点下方，底部节点还可能接近撤退/返回
     * 按钮。因此保持水平中心，垂直方向取主体与台座重叠的约 32%。
     */
    fun getClickPosition(rect: EntryPixelRect): Pair<Int, Int> {
        return getBaseClickPosition(rect)
    }

    fun getBaseClickPosition(rect: EntryPixelRect): Pair<Int, Int> {
        val baseOffset = (rect.height * BASE_CLICK_Y_RATIO)
            .roundToInt()
            .coerceIn(0, rect.height - 1)
        return Pair(
            rect.left + rect.width / 2,
            rect.top + baseOffset,
        )
    }

    private companion object {
        /** Stable platform/icon overlap band, kept clear of the bottom retreat/return controls. */
        const val BASE_CLICK_Y_RATIO = 0.32
        const val TOPOLOGY_ORDERED_OVERRIDE_MIN_CONFIDENCE = 0.90
        const val TOPOLOGY_PURPLE_EX_OVERRIDE_MIN_CONFIDENCE = 0.80
        const val PURPLE_EX_OVERRIDE_MIN_GLOW_SCORE = 0.08
        const val LINK_RELIC_OVERRIDE_MIN_CONFIDENCE = 0.75
        const val LINK_RELIC_SIBLING_MIN_CONFIDENCE = 0.70
        const val INACTIVE_ROUTE_TARGET_MIN_VISUAL_CONFIDENCE = 0.80
        const val INACTIVE_ROUTE_TARGET_MIN_TOPOLOGY_CONFIDENCE = 0.82
        const val INACTIVE_ROUTE_SIBLING_MIN_VISUAL_CONFIDENCE = 0.60
        const val INACTIVE_ROUTE_SIBLING_MIN_TOPOLOGY_CONFIDENCE = 0.80
    }
}

/**
 * Whether a route/vision disagreement involves the Boss platform, which no override may waive.
 *
 * The ordered-topology override exists because the semantic template classifier confuses visually
 * similar *regular* node icons; column cardinality and row order are better evidence than the icon
 * in those cases. The Boss platform is not one of those cases. It is a separate, much larger map
 * asset detected through its own special-node anchor, so reading it as a 商店 or a 普通战斗 is not
 * template noise but a real disagreement about where the camera is.
 *
 * 2026-09-19 live: the route wanted 商店#30601 while the only crop on screen was the area's Boss
 * platform, bound FULL_COLUMN at 0.99 because a one-node logical column trivially matches a single
 * detection. The override waived the conflict and the run tapped the Boss platform repeatedly,
 * waiting for a movement dialog that never came; the shop was only reached after a manual swipe.
 */
internal fun labyrinthBossSemanticConflict(expected: Int, detected: Int): Boolean =
    expected != detected &&
        (expected == LabyrinthNodeTypes.BOSS || detected == LabyrinthNodeTypes.BOSS)
