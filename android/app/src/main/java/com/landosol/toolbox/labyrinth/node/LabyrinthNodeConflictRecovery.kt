package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import kotlin.math.abs

/**
 * Recovers a stable route-bound node when visual semantics disagree with the saved route.
 *
 * This is deliberately node-type agnostic. The route graph already says which block is next;
 * this layer only decides whether the conflicting on-screen crop is stable enough to trust as the
 * physical target. Ordered topology is preferred. SINGLE_TARGET/no-order evidence is accepted
 * only when the route graph has exactly one reachable successor and that conflict crop is the sole
 * active node in the current viewport.
 */
internal class LabyrinthNodeConflictRecovery {
    private var sessionKey: String? = null
    private var blockId: Long? = null
    private var expectedType: Int? = null
    private var lastRect: EntryPixelRect? = null
    private var stableFrames = 0

    fun reset() {
        sessionKey = null
        blockId = null
        expectedType = null
        lastRect = null
        stableFrames = 0
    }

    fun resolve(
        session: String,
        action: NodeAction,
        visible: List<NodeClassification>,
        uniqueReachableRouteTarget: Boolean = false,
    ): NodeAction {
        val conflict = action as? NodeAction.TypeConflict ?: run {
            reset()
            return action
        }
        val rect = conflict.screenRect ?: run {
            reset()
            return action
        }
        val active = visible
            .filter { it.isClickable && it.screenRect != null }
            .distinctBy { it.screenRect }
        val matchingActive = active.filter { candidate ->
            candidate.screenRect?.let { rectsAreStable(it, rect) } == true
        }
        val topologyConfidence = conflict.topologyConfidence ?: 0.0
        val binding = conflict.topologyBindingKind
        val orderedTopology = binding != null &&
            binding != NodeTopologyBindingKind.SINGLE_TARGET &&
            topologyConfidence >= MIN_ORDERED_TOPOLOGY_CONFIDENCE
        val isolatedTarget = uniqueReachableRouteTarget &&
            active.size == 1 && matchingActive.size == 1 &&
            conflict.confidence >= MIN_ISOLATED_VISUAL_CONFIDENCE
        val supported = conflict.expectedBlockType != conflict.detectedBlockType &&
            conflict.confidence >= MIN_VISUAL_CONFIDENCE &&
            matchingActive.isNotEmpty() &&
            (orderedTopology || isolatedTarget)
        if (!supported) {
            reset()
            return action
        }

        val sameEvidence = sessionKey == session &&
            blockId == conflict.blockId &&
            expectedType == conflict.expectedBlockType &&
            rectsAreStable(lastRect, rect)
        stableFrames = if (sameEvidence) stableFrames + 1 else 1
        sessionKey = session
        blockId = conflict.blockId
        expectedType = conflict.expectedBlockType
        lastRect = rect

        return if (stableFrames >= REQUIRED_STABLE_FRAMES) {
            NodeAction.ClickNode(conflict.blockId, conflict.expectedBlockType, rect)
        } else {
            action
        }
    }

    private fun rectsAreStable(first: EntryPixelRect?, second: EntryPixelRect?): Boolean {
        if (first == null || second == null) return first == second
        val firstCenterX = first.left + first.width / 2
        val firstCenterY = first.top + first.height / 2
        val secondCenterX = second.left + second.width / 2
        val secondCenterY = second.top + second.height / 2
        val xTolerance = maxOf(18, minOf(first.width, second.width) / 5)
        val yTolerance = maxOf(18, minOf(first.height, second.height) / 5)
        return abs(firstCenterX - secondCenterX) <= xTolerance &&
            abs(firstCenterY - secondCenterY) <= yTolerance
    }

    private companion object {
        const val REQUIRED_STABLE_FRAMES = 3
        const val MIN_VISUAL_CONFIDENCE = 0.80
        const val MIN_ISOLATED_VISUAL_CONFIDENCE = 0.85
        const val MIN_ORDERED_TOPOLOGY_CONFIDENCE = 0.72
    }
}
