package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Test

class LabyrinthCurrentOpeningCriteriaTest {
    @Test
    fun `current selections replace frozen checkpoint target when revalidating same run`() {
        val oldPolicy = LabyrinthRoutePolicy(
            perfectStart = false,
            thirdBlockChoice = LabyrinthThirdBlockChoice.RELIC,
            area3BossIds = setOf(301),
            area5BossIds = setOf(501),
        )
        val selectedPolicy = LabyrinthRoutePolicy(
            perfectStart = true,
            thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT,
            area3BossIds = setOf(302),
            area5BossIds = setOf(502),
        )
        val checkpoint = LabyrinthRerollCheckpoint(
            accountId = 7,
            attempt = 19,
            enterId = 88001,
            guildId = 1,
            difficulty = 2,
            policy = oldPolicy,
            verdict = LabyrinthRouteVerdict.NOT_TARGET,
            message = "旧条件不匹配",
            updatedAt = 100,
        )

        val criteria = labyrinthCurrentOpeningCriteria(
            selectedGuildId = 4,
            selectedDifficulty = 5,
            selectedRoutePolicy = selectedPolicy,
            savedCheckpoint = checkpoint,
            currentEnterId = 88001,
        )

        assertEquals(4, criteria.guildId)
        assertEquals(5, criteria.difficulty)
        assertEquals(selectedPolicy, criteria.routePolicy)
        assertEquals(19, criteria.attempt)
    }

    @Test
    fun `checkpoint from another run cannot contribute its attempt counter`() {
        val checkpoint = LabyrinthRerollCheckpoint(
            accountId = 7,
            attempt = 19,
            enterId = 88001,
            guildId = 1,
            difficulty = 2,
            policy = LabyrinthRoutePolicy(),
            verdict = LabyrinthRouteVerdict.TARGET,
            message = null,
            updatedAt = 100,
        )

        val criteria = labyrinthCurrentOpeningCriteria(
            selectedGuildId = 2,
            selectedDifficulty = 3,
            selectedRoutePolicy = LabyrinthRoutePolicy(),
            savedCheckpoint = checkpoint,
            currentEnterId = 99002,
        )

        assertEquals(0, criteria.attempt)
    }
}
