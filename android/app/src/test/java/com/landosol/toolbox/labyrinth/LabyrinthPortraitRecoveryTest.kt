package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthPortraitRecoveryTest {
    @Test
    fun `unknown alone never authorizes a tap`() {
        val policy = LabyrinthPortraitRecovery()
        policy.observe(LabyrinthEntryPageState.UNKNOWN, false, 0)
        assertFalse(policy.canAttempt(3_000, false))
    }

    @Test
    fun `joined evidence survives loading but waits on unknown`() {
        val policy = LabyrinthPortraitRecovery()
        policy.observe(LabyrinthEntryPageState.CHARACTER_JOINED, true, 0)
        policy.observe(LabyrinthEntryPageState.GAME_LOADING_PROGRESS, false, 1_000)
        assertFalse(policy.canAttempt(3_000, false))
        policy.observe(LabyrinthEntryPageState.UNKNOWN, false, 4_000)
        assertFalse(policy.canAttempt(5_999, false))
        assertTrue(policy.canAttempt(6_000, false))
    }

    @Test
    fun `three attempt budget survives reward and loading jitter`() {
        val policy = armed()
        for (time in listOf(3_000L, 5_000L, 7_000L)) {
            assertTrue(policy.canAttempt(time, false))
            policy.recordAttempt(time)
            assertFalse(policy.canAttempt(time + 1_999, false))
        }
        policy.observe(LabyrinthEntryPageState.CHARACTER_JOINED, true, 8_000)
        policy.observe(LabyrinthEntryPageState.PRE_HOME_DATA_LOADING, false, 9_000)
        policy.observe(LabyrinthEntryPageState.UNKNOWN, false, 10_000)
        assertFalse(policy.canAttempt(12_000, false))
    }

    @Test
    fun `evidence expires without extending unknown timeout`() {
        assertTrue(armed().canAttempt(30_000, false))
        assertFalse(armed().canAttempt(30_001, false))
    }

    @Test
    fun `map shop battle and new nonreward choices disarm recovery`() {
        listOf(
            LabyrinthEntryPageState.NODE_SELECTION, LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.SHOP, LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
            LabyrinthEntryPageState.RELIC_CHOICE, LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
        ).forEach { page ->
            val policy = armed()
            policy.observe(page, false, 2_000)
            policy.observe(LabyrinthEntryPageState.UNKNOWN, false, 3_000)
            assertFalse(page.name, policy.canAttempt(5_000, false))
        }
    }

    @Test
    fun `blocked frame does not consume budget and reset discards evidence`() {
        val policy = armed()
        assertFalse(policy.canAttempt(3_000, true))
        assertTrue(policy.canAttempt(3_000, false))
        policy.reset()
        policy.observe(LabyrinthEntryPageState.UNKNOWN, false, 4_000)
        assertFalse(policy.canAttempt(6_000, false))
    }

    @Test
    fun `partial loading modal choice and map controls veto recovery`() {
        listOf(
            EntryAnchorId.DATA_CONNECTING, EntryAnchorId.SESSION_DATE_CHANGE_TITLE,
            EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE, EntryAnchorId.SHOP_BUY_BUTTON,
            EntryAnchorId.ROLE_REWARD_SELECT_BUTTON, EntryAnchorId.NODE_RETREAT_STANDARD,
            EntryAnchorId.JOINED_CLOSE_STANDARD,
            EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON,
            EntryAnchorId.BATTLE_IN_PROGRESS_AUTO_BUTTON,
        ).forEach { id ->
            assertTrue(id, labyrinthPortraitRecoveryBlocked(frame(mapOf(id to 0.45))))
            assertTrue(id, labyrinthPortraitRecoveryBlocked(frame().copy(
                anchorMatches = mapOf(id to EntryAnchorMatch(0.45, EntryPixelRect(0, 0, 10, 10))),
            )))
        }
        assertFalse(labyrinthPortraitRecoveryBlocked(frame()))
    }

    @Test
    fun `either battle control alone blocks animation taps without classifying a battle`() {
        listOf(EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON, EntryAnchorId.BATTLE_IN_PROGRESS_AUTO_BUTTON)
            .forEach { id ->
                assertTrue(labyrinthHasBattleControls(frame(mapOf(id to 0.45))))
                assertTrue(labyrinthHasBattleControls(frame().copy(
                    anchorMatches = mapOf(id to EntryAnchorMatch(0.45, EntryPixelRect(0, 0, 10, 10))),
                )))
                assertFalse(labyrinthHasBattleControls(frame(mapOf(id to 0.44))))
            }
        assertFalse(labyrinthHasBattleControls(frame(mapOf(EntryAnchorId.SHOP_TITLE to 1.0))))
    }

    private fun armed() = LabyrinthPortraitRecovery().apply {
        observe(LabyrinthEntryPageState.CHARACTER_JOINED, true, 0)
        observe(LabyrinthEntryPageState.UNKNOWN, false, 1_000)
    }

    private fun frame(scores: Map<String, Double> = emptyMap()) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(scores),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0,
    )
}
