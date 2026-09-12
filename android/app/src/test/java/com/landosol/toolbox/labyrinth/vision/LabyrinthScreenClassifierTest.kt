package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthScreenClassifierTest {
    private val classifier = LabyrinthScreenClassifier()

    @Test
    fun `recognizes a single trusted home anchor`() {
        val observation = classifier.classify(
            LabyrinthAnchorScores(mapOf("home.adventure" to 0.91)),
        )

        assertEquals(LabyrinthScreenStage.HOME, observation.stage)
        assertEquals(0.91, observation.confidence, 0.0001)
    }

    @Test
    fun `requires both labyrinth home anchors`() {
        val incomplete = classifier.classify(
            LabyrinthAnchorScores(mapOf("labyrinth.start" to 0.95)),
        )
        val complete = classifier.classify(
            LabyrinthAnchorScores(
                mapOf(
                    "labyrinth.start" to 0.95,
                    "ticket.owned" to 0.88,
                ),
            ),
        )

        assertEquals(LabyrinthScreenStage.UNKNOWN, incomplete.stage)
        assertEquals(LabyrinthScreenStage.LABYRINTH_HOME, complete.stage)
        assertEquals(0.88, complete.confidence, 0.0001)
    }

    @Test
    fun `guild select requires confirm and at least one guild anchor`() {
        val observation = classifier.classify(
            LabyrinthAnchorScores(
                mapOf(
                    "guild.confirm" to 0.93,
                    "guild.gourmet" to 0.86,
                ),
            ),
        )

        assertEquals(LabyrinthScreenStage.GUILD_SELECT, observation.stage)
        assertEquals(0.86, observation.confidence, 0.0001)
    }

    @Test
    fun `ambiguous or weak screens remain unknown`() {
        val ambiguous = classifier.classify(
            LabyrinthAnchorScores(
                mapOf(
                    "home.adventure" to 0.90,
                    "labyrinth.entry" to 0.88,
                ),
            ),
        )
        val weak = classifier.classify(
            LabyrinthAnchorScores(mapOf("battle.confirm" to 0.60)),
        )

        assertEquals(LabyrinthScreenStage.UNKNOWN, ambiguous.stage)
        assertEquals(LabyrinthScreenStage.UNKNOWN, weak.stage)
        assertTrue(ambiguous.reason != null)
        assertTrue(weak.reason != null)
    }
}
