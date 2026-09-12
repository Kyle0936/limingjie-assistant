package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEventChoicePlannerTest {
    @Test fun `configured win rates change battle choice without bypassing visual safety`() {
        val event = EventResource("1105", "斗技场", area = 1, choices = listOf(
            EventChoiceResource("11051", "普通", slot = 1), EventChoiceResource("11052", "极难", slot = 2)))
        fun decision(rate: Int, trusted: Boolean): LabyrinthEventChoiceDecision.Select {
            val planner = LabyrinthEventChoicePlanner(emptyMap(), LabyrinthTeamOptimizer(LabyrinthTeamScorer(
                LabyrinthTeamScoringConfig(attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75)))),
                LabyrinthStrategySettings(extremeWinRate = rate))
            return planner.decide(LabyrinthEventChoiceObservation(event,
                event.choices.map { LabyrinthEventChoiceVisual(it, EntryPixelRect(0, 0, 100, 100), 0.8) },
                "斗技场", 1.0, 0.2, 2, trusted), emptySet(), LabyrinthRoleDecisionContext(0), emptyMap()) as LabyrinthEventChoiceDecision.Select
        }
        assertEquals("11052", decision(70, true).choiceId)
        assertEquals("11051", decision(20, true).choiceId)
        assertFalse(decision(20, false).actionSafe)
    }

    private val planner = LabyrinthEventChoicePlanner(
        profiles = emptyMap(),
        optimizer = LabyrinthTeamOptimizer(
            LabyrinthTeamScorer(
                LabyrinthTeamScoringConfig(
                    attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
                ),
            ),
        ),
    )

    @Test
    fun `web utility model recommends fixed coins on lost-in-dungeon event`() {
        val decision = planner.decide(
            observation = observation(trusted = true, buttonConfidence = 0.8),
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = 0),
            relicStacks = LabyrinthRelicMark.entries.associateWith { 0 },
        ) as LabyrinthEventChoiceDecision.Select

        assertEquals("11082", decision.choiceId)
        assertTrue(decision.actionSafe)
    }

    @Test
    fun `recommendation remains visible but unsafe while mapped button is not blue`() {
        val decision = planner.decide(
            observation = observation(trusted = true, buttonConfidence = 0.0),
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = 0),
            relicStacks = LabyrinthRelicMark.entries.associateWith { 0 },
        ) as LabyrinthEventChoiceDecision.Select

        assertEquals("11082", decision.choiceId)
        assertFalse(decision.actionSafe)
        assertTrue(decision.safetyNote.orEmpty().contains("蓝色"))
    }

    @Test
    fun `disabled high utility conditional option falls back to best enabled choice`() {
        val event = EventResource(
            id = "2316",
            name = "再较量一次！",
            area = 2,
            choices = listOf(
                EventChoiceResource("23162", "到这里就行了……", slot = 1, description = "挑战结束"),
                EventChoiceResource(
                    "23161",
                    "今天真是走运！",
                    slot = 2,
                    description = "有50％的概率获得阿尔法金币×4000并进入下一个事件",
                    conditionType = 1,
                    conditionValue = 2000,
                ),
            ),
        )
        val observation = LabyrinthEventChoiceObservation(
            event = event,
            choices = listOf(
                LabyrinthEventChoiceVisual(event.choices[0], EntryPixelRect(100, 800, 300, 150), 0.85),
                LabyrinthEventChoiceVisual(event.choices[1], EntryPixelRect(600, 800, 300, 150), 0.02),
            ),
            rawText = event.name,
            confidence = 1.0,
            rivalMargin = 0.2,
            stableFrames = 2,
            trusted = true,
        )

        val decision = planner.decide(
            observation = observation,
            acquiredCharacterIds = emptySet(),
            context = LabyrinthRoleDecisionContext(defenseMarkStacks = 0),
            relicStacks = LabyrinthRelicMark.entries.associateWith { 0 },
        ) as LabyrinthEventChoiceDecision.Select

        assertEquals("23162", decision.choiceId)
        assertTrue(decision.actionSafe)
    }

    private fun observation(trusted: Boolean, buttonConfidence: Double): LabyrinthEventChoiceObservation {
        val event = EventResource(
            id = "1108",
            name = "在地下城迷路了！要走哪边？",
            area = 1,
            choices = listOf(
                EventChoiceResource("11082", "难道不是左边吗？", slot = 1, description = "阿尔法金币×1000"),
                EventChoiceResource(
                    "11081",
                    "当然是右边啊！",
                    slot = 2,
                    description = "50%★1遗物＋50%随机职阶随机角色×2",
                ),
            ),
        )
        return LabyrinthEventChoiceObservation(
            event = event,
            choices = event.choices.mapIndexed { index, choice ->
                LabyrinthEventChoiceVisual(
                    choice,
                    EntryPixelRect(500 + index * 500, 800, 300, 150),
                    buttonConfidence,
                )
            },
            rawText = event.name,
            confidence = 1.0,
            rivalMargin = 0.2,
            stableFrames = 2,
            trusted = trusted,
        )
    }
}
