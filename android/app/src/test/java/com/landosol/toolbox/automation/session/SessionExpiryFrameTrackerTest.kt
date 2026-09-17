package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleEndConfirmationObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleEndConfirmationStage
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionExpiryFrameTrackerTest {
    private fun result(
        state: LabyrinthEntryPageState,
        failure: LabyrinthBattleFailureObservation? = null,
        anchors: Map<String, EntryAnchorMatch> = emptyMap(),
        endConfirmation: LabyrinthBattleEndConfirmationObservation? = null,
    ) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = 1.0,
            anchorScores = LabyrinthAnchorScores(anchors.mapValues { it.value.score }),
            stateScores = emptyMap(),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0L,
        anchorMatches = anchors,
        battleFailure = failure,
        battleEndConfirmation = endConfirmation,
    )

    private val tab = EntryAnchorId.DAWN_HOME_MY_HOME_TAB to
        EntryAnchorMatch(score = 0.97, rect = EntryPixelRect(85, 945, 175, 125))

    @Test
    fun `labyrinth home trigger is the recognised 我的主页 tab and nothing when it is weak or absent`() {
        val tracker = SessionExpiryFrameTracker()

        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, anchors = mapOf(tab)))
        val trigger = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(172.5f, trigger.x, 0.01f)
        assertEquals(1007.5f, trigger.y, 0.01f)

        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, anchors = mapOf(tab)))
        assertEquals(trigger, tracker.sessionInvalidationTrigger)

        val weak = tab.first to tab.second.copy(score = 0.5)
        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, anchors = mapOf(weak)))
        assertNull(tracker.sessionInvalidationTrigger)

        // Same anchor score on a page that is not the labyrinth home: never a trigger.
        tracker.record(result(LabyrinthEntryPageState.NODE_SELECTION, anchors = mapOf(tab)))
        assertNull(tracker.sessionInvalidationTrigger)
        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, anchors = mapOf(tab)))
        assertNull(tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `failure page trigger walks 结束 then 撤退 then 确认 by recognised dialog stage`() {
        // 2026-09-17: 重新挑战 only returns to the EX challenge page; the retreat chain is the
        // action that reaches the server.
        val tracker = SessionExpiryFrameTracker()
        val failure = LabyrinthBattleFailureObservation(
            confidence = 0.9,
            endButtonRect = EntryPixelRect(945, 935, 430, 110),
            retryButtonRect = EntryPixelRect(1405, 935, 430, 110),
        )

        tracker.record(result(LabyrinthEntryPageState.BATTLE_FAILED, failure))
        val end = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1160f, end.x, 0.01f)
        assertEquals(990f, end.y, 0.01f)

        val choice = LabyrinthBattleEndConfirmationObservation(
            stage = LabyrinthBattleEndConfirmationStage.CHOICE,
            confidence = 0.9,
            advanceButtonRect = EntryPixelRect(835, 700, 255, 90),
        )
        tracker.record(result(LabyrinthEntryPageState.BATTLE_FAILED, failure, endConfirmation = choice))
        val retreat = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(962.5f, retreat.x, 0.01f)
        assertEquals(745f, retreat.y, 0.01f)

        // The dimmed page may drop the failure detection; the dialog alone still drives the chain.
        val confirm = LabyrinthBattleEndConfirmationObservation(
            stage = LabyrinthBattleEndConfirmationStage.RETREAT_CONFIRM,
            confidence = 0.9,
            advanceButtonRect = EntryPixelRect(1120, 700, 265, 90),
        )
        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, null, endConfirmation = confirm))
        val confirmPoint = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1252.5f, confirmPoint.x, 0.01f)

        // A bare UNKNOWN frame with no dialog never taps the (unrecognised) end button.
        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, null))
        assertNull(tracker.sessionInvalidationTrigger)

        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, null))
        assertNull(tracker.sessionInvalidationTrigger)
    }
}
