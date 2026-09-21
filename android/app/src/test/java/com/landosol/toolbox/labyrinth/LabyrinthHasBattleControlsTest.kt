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

class LabyrinthHasBattleControlsTest {
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

    private fun frame(scores: Map<String, Double> = emptyMap()) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(scores),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0,
    )
}
