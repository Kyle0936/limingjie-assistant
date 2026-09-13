package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState

enum class LabyrinthBattleRosterScrollDirection { TO_TOP, NEXT_PAGE }

internal enum class LabyrinthBattleRosterSearchDecision {
    TO_TOP,
    NEXT_PAGE,
    WAIT_FOR_SETTLE,
    EXHAUSTED,
    UNAVAILABLE,
}

/** Search progress belongs to one session, team, recommendation and attribute filter. */
internal class LabyrinthBattleRosterSearch {
    private data class Context(
        val sessionId: AutomationSessionId,
        val team: Int?,
        val filter: LabyrinthBattleElementFilter,
        val recommendedIds: List<String>,
    )

    private var context: Context? = null
    private var topObserved = false
    private var pendingScroll: PendingScroll? = null

    private data class PendingScroll(
        val direction: LabyrinthBattleRosterScrollDirection,
        val originPosition: Double,
        val observations: Int = 0,
        val samePositionStableObservations: Int = 0,
    )

    fun reset() {
        context = null
        topObserved = false
        pendingScroll = null
    }

    /**
     * A roster swipe can temporarily overscroll past the first/last row and expose a blank area.
     * Do not interpret those transient frames as another page. Wait until the list rebounds to a
     * stable viewport with cards, then compare the scrollbar against the pre-swipe position.
     */
    fun recordExecutedScroll(
        direction: LabyrinthBattleRosterScrollDirection,
        originPosition: Double,
    ) {
        if (!originPosition.isFinite() || originPosition !in 0.0..1.0) return
        pendingScroll = PendingScroll(direction, originPosition)
    }

    fun observe(
        sessionId: AutomationSessionId,
        observation: LabyrinthBattleTeamObservation,
        recommendedIds: List<String>,
    ): LabyrinthBattleRosterSearchDecision {
        val stableContext = if (
            observation.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE &&
            observation.currentFilter != LabyrinthBattleElementFilter.UNKNOWN
        ) {
            Context(sessionId, observation.bossTeamIndex, observation.currentFilter, recommendedIds.toList())
        } else {
            null
        }
        if (stableContext != null && context != stableContext) {
            context = stableContext
            topObserved = false
            pendingScroll = null
        }

        pendingScroll?.let { pending ->
            val nextPending = pending.copy(observations = pending.observations + 1)
            pendingScroll = nextPending
            if (
                observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
                observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN ||
                observation.visibleCharacters.isEmpty()
            ) {
                if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                    pendingScroll = null
                    return LabyrinthBattleRosterSearchDecision.UNAVAILABLE
                }
                return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            }
            val scrollbar = observation.scrollbar
            if (!scrollbar.visible || scrollbar.thumbRect == null ||
                !scrollbar.position.isFinite() || scrollbar.position !in 0.0..1.0
            ) {
                if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                    pendingScroll = null
                    return LabyrinthBattleRosterSearchDecision.UNAVAILABLE
                }
                return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            }

            val delta = scrollbar.position - pending.originPosition
            val movedInRequestedDirection = when (pending.direction) {
                LabyrinthBattleRosterScrollDirection.TO_TOP -> delta <= -MIN_CONFIRMED_SCROLL_DELTA
                LabyrinthBattleRosterScrollDirection.NEXT_PAGE -> delta >= MIN_CONFIRMED_SCROLL_DELTA
            }
            if (movedInRequestedDirection) {
                pendingScroll = null
                if (isAtVisualTopBoundary(scrollbar)) topObserved = true
                return normalDecision(scrollbar)
            }

            // The stable list returned to effectively the same position after the gesture. Near
            // an end this is the expected elastic overscroll -> rebound signal, so treat it as a
            // confirmed boundary instead of issuing another same-direction swipe.
            if (kotlin.math.abs(delta) <= SAME_POSITION_TOLERANCE) {
                // The first frame(s) after executor completion can still be the pre-swipe capture.
                // Never fail or confirm an elastic boundary from a single same-position frame.
                // Require repeated stable observations so the actual post-gesture/rebound frame
                // has time to arrive.
                val same = nextPending.samePositionStableObservations + 1
                pendingScroll = nextPending.copy(samePositionStableObservations = same)
                if (same >= MIN_REBOUND_STABLE_OBSERVATIONS) {
                    when (pending.direction) {
                        LabyrinthBattleRosterScrollDirection.TO_TOP -> {
                            if (isAtVisualTopBoundary(scrollbar, rebound = true)) {
                                pendingScroll = null
                                topObserved = true
                                return normalDecision(scrollbar)
                            }
                        }
                        LabyrinthBattleRosterScrollDirection.NEXT_PAGE -> {
                            if (isAtVisualBottomBoundary(scrollbar, rebound = true) ||
                                (topObserved && scrollbar.contentEndVisible)) {
                                pendingScroll = null
                                return LabyrinthBattleRosterSearchDecision.EXHAUSTED
                            }
                        }
                    }
                }
                if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                    pendingScroll = null
                    return LabyrinthBattleRosterSearchDecision.UNAVAILABLE
                }
                return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
            }

            if (nextPending.observations >= MAX_SCROLL_SETTLE_OBSERVATIONS) {
                pendingScroll = null
                return LabyrinthBattleRosterSearchDecision.UNAVAILABLE
            }
            return LabyrinthBattleRosterSearchDecision.WAIT_FOR_SETTLE
        }

        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
            observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN
        ) return LabyrinthBattleRosterSearchDecision.UNAVAILABLE
        val scrollbar = observation.scrollbar
        if (!scrollbar.visible || scrollbar.thumbRect == null ||
            !scrollbar.position.isFinite() || scrollbar.position !in 0.0..1.0
        ) return LabyrinthBattleRosterSearchDecision.UNAVAILABLE

        // Only visual evidence can finish rewinding. A dispatched gesture is not confirmation.
        if (scrollbar.position <= TOP_POSITION_THRESHOLD) topObserved = true
        return normalDecision(scrollbar)
    }

    private fun normalDecision(scrollbar: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation): LabyrinthBattleRosterSearchDecision = when {
        !scrollbar.canScroll -> LabyrinthBattleRosterSearchDecision.EXHAUSTED
        !topObserved -> LabyrinthBattleRosterSearchDecision.TO_TOP
        scrollbar.contentEndVisible -> LabyrinthBattleRosterSearchDecision.EXHAUSTED
        scrollbar.position >= BOTTOM_POSITION_THRESHOLD -> LabyrinthBattleRosterSearchDecision.EXHAUSTED
        else -> LabyrinthBattleRosterSearchDecision.NEXT_PAGE
    }

    /**
     * The blue scrollbar thumb does not always touch the calibrated track rectangle. In a short
     * filtered roster the thumb can be very tall, so a fixed ~13 px visual end-cap becomes a large
     * normalized position error (the current EFFECTIVE_EFFECT top stop measured as 0.111). Keep
     * the strict normalized thresholds for ordinary decisions, but after a real swipe/rebound also
     * accept a thumb physically hugging the corresponding track edge.
     */
    private fun isAtVisualTopBoundary(
        scrollbar: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation,
        rebound: Boolean = false,
    ): Boolean {
        val normalizedThreshold = if (rebound) TOP_REBOUND_BOUNDARY_THRESHOLD else TOP_POSITION_THRESHOLD
        if (scrollbar.position <= normalizedThreshold) return true
        val thumb = scrollbar.thumbRect ?: return false
        val gap = (thumb.top - scrollbar.trackRect.top).coerceAtLeast(0)
        return gap <= visualEdgeGapLimit(scrollbar.trackRect.height)
    }

    private fun isAtVisualBottomBoundary(
        scrollbar: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation,
        rebound: Boolean = false,
    ): Boolean {
        val normalizedThreshold = if (rebound) BOTTOM_REBOUND_BOUNDARY_THRESHOLD else BOTTOM_POSITION_THRESHOLD
        if (scrollbar.position >= normalizedThreshold) return true
        val thumb = scrollbar.thumbRect ?: return false
        val trackBottom = scrollbar.trackRect.top + scrollbar.trackRect.height
        val thumbBottom = thumb.top + thumb.height
        val gap = (trackBottom - thumbBottom).coerceAtLeast(0)
        return gap <= visualEdgeGapLimit(scrollbar.trackRect.height)
    }

    private fun visualEdgeGapLimit(trackHeight: Int): Int =
        maxOf(MIN_VISUAL_EDGE_GAP_PX, (trackHeight * VISUAL_EDGE_GAP_RATIO).toInt())

    private companion object {
        const val TOP_POSITION_THRESHOLD = 0.02
        const val BOTTOM_POSITION_THRESHOLD = 0.98
        // Wider only for a confirmed no-movement rebound; normal position decisions stay strict.
        const val TOP_REBOUND_BOUNDARY_THRESHOLD = 0.08
        const val BOTTOM_REBOUND_BOUNDARY_THRESHOLD = 0.92
        const val MIN_CONFIRMED_SCROLL_DELTA = 0.035
        const val SAME_POSITION_TOLERANCE = 0.025
        const val MIN_REBOUND_STABLE_OBSERVATIONS = 2
        const val MAX_SCROLL_SETTLE_OBSERVATIONS = 12
        const val MIN_VISUAL_EDGE_GAP_PX = 14
        const val VISUAL_EDGE_GAP_RATIO = 0.04
    }
}
