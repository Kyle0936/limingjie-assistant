package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-09-17 live: the final Boss completed the route (BEFORE_SCORE), then the RESULT page
 * appeared. The old NONE-only transition never entered SCORE_RESULT, so RESULT had no plan and
 * the run burned 402 blind animation taps ("RUN_CLEAR_RESULT：暂不自动处理").
 */
class LabyrinthPostBossStageTest {
    @Test
    fun `result page enters score stage from either pre-score stage`() {
        assertEquals(
            LabyrinthPostBossStage.SCORE_RESULT,
            labyrinthPostBossStageOnFinalResult(LabyrinthPostBossStage.NONE),
        )
        assertEquals(
            LabyrinthPostBossStage.SCORE_RESULT,
            labyrinthPostBossStageOnFinalResult(LabyrinthPostBossStage.BEFORE_SCORE),
        )
    }

    @Test
    fun `result page still visible after the first close tap returns to the score stage`() {
        assertEquals(
            LabyrinthPostBossStage.SCORE_RESULT,
            labyrinthPostBossStageOnFinalResult(LabyrinthPostBossStage.CHEST_SEQUENCE),
        )
    }

    @Test
    fun `result page never regresses a stage past the chest result`() {
        for (stage in listOf(
            LabyrinthPostBossStage.SCORE_RESULT,
            LabyrinthPostBossStage.FINAL_ITEM_REWARD,
            LabyrinthPostBossStage.WAITING_FOR_DAWN_HOME,
        )) {
            assertEquals(stage.name, stage, labyrinthPostBossStageOnFinalResult(stage))
        }
    }

    @Test
    fun `chest result dialog falls back to the bottom-centre confirm button`() {
        assertEquals(LabyrinthFallbackTap.BOTTOM_CENTER, labyrinthChestResultFallbackTap())
        // 1178x663 screenshot: 确认 centre (588,588) → 1080p (958,958); the tap must sit on it.
        val rect = labyrinthChestResultFallbackTap().rect(1920, 1080)
        val cx = rect.left + rect.width / 2
        val cy = rect.top + rect.height / 2
        assert(cx in 900..1020 && cy in 913..1003) { "tap ($cx,$cy) misses the 确认 button" }
    }
}
