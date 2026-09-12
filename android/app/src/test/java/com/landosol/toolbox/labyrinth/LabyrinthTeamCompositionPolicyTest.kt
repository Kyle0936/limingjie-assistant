package com.landosol.toolbox.labyrinth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthTeamCompositionPolicyTest {
    private val config = LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 })
    private val scorer = LabyrinthTeamScorer(config)
    private fun role(id: String, tank: Boolean = false, healer: Boolean = false) = LabyrinthRoleProfile(
        characterId = id, displayName = id,
        roleClass = if (tank) "掩护者" else if (healer) "治疗者" else "攻击者",
        attribute = LabyrinthCharacterAttribute.LIGHT,
        damageType = LabyrinthRoleDamageType.PHYSICAL,
        position = if (tank) 1 else 10,
        userScore = 80.0,
        physicalDamagePotential = if (tank || healer) 0.0 else 80.0,
        magicDamagePotential = 0.0,
        functions = LabyrinthRoleFunctions(
            reliableVanguard = if (tank) 100.0 else 0.0,
            selfSustain = if (tank) 100.0 else 0.0,
            healing = if (healer) 100.0 else 0.0,
            pureTank = if (tank) 100.0 else 0.0,
            pureHealer = if (healer) 100.0 else 0.0,
            singleTargetDamage = if (tank || healer) 0.0 else 80.0,
        ),
    )
    private fun tankTeam() = listOf(role("tank1", tank = true), role("tank2", tank = true),
        role("heal", healer = true), role("dps1"), role("dps2"))

    @Test
    fun `second pure role costs four six eight points as defense grows`() {
        for ((stacks, penalty) in listOf(0 to 4.0, 1 to 4.0, 2 to 6.0, 3 to 6.0, 4 to 8.0, 15 to 8.0)) {
            val context = LabyrinthRoleDecisionContext(stacks)
            val actual = scorer.evaluate(tankTeam(), context)
            val noPenalty = LabyrinthTeamScorer(config.copy(duplicatePureRolePenalty = 0.0)).evaluate(tankTeam(), context)
            assertEquals(2, actual.pureTankCount)
            assertEquals(1, actual.pureHealerCount)
            assertEquals(penalty, actual.duplicateRolePenalty, 1e-9)
            assertEquals(noPenalty.score - penalty, actual.score, 1e-9)
            assertEquals(noPenalty.systemScore - penalty, actual.systemScore, 1e-9)
            assertEquals(-penalty, actual.components.getValue("重复纯职能"), 1e-9)
            assertTrue(actual.reasons.first().contains("重复纯职能"))
        }
    }

    @Test
    fun `two tanks and two healers accumulate independent penalties`() {
        val team = listOf(role("t1", tank = true), role("t2", tank = true),
            role("h1", healer = true), role("h2", healer = true), role("dps"))
        val actual = scorer.evaluate(team, LabyrinthRoleDecisionContext(4))
        assertEquals(2, actual.pureHealerCount)
        assertEquals(16.0, actual.duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `class labels alone do not impose caps and no healer slot is mandatory`() {
        val team = (0 until 5).map { role("r$it").copy(roleClass = if (it < 3) "掩护者" else "治疗者") }
        val evaluation = scorer.evaluate(team, LabyrinthRoleDecisionContext(4))
        assertEquals(0, evaluation.pureTankCount)
        assertEquals(0, evaluation.pureHealerCount)
        assertEquals(0.0, evaluation.duplicateRolePenalty, 1e-9)
        assertEquals(5, requireNotNull(LabyrinthTeamOptimizer(scorer).bestFormation(team,
            LabyrinthRoleDecisionContext(4))).members.size)
    }

    @Test
    fun `composite damage buffs and matching defense down exempt defensive roles`() {
        val pure = tankTeam()[1]
        val composites = listOf(
            pure.copy(physicalDamagePotential = 60.0),
            pure.copy(support = LabyrinthRoleSupport(universalOffense = 60.0)),
            pure.copy(support = LabyrinthRoleSupport(physicalOffense = 60.0)),
            pure.copy(functions = pure.functions.copy(physicalDefenseDown = 60.0)),
        )
        for (composite in composites) {
            val team = tankTeam().toMutableList().also { it[1] = composite }
            val actual = scorer.evaluate(team, LabyrinthRoleDecisionContext(4))
            assertEquals(1, actual.pureTankCount)
            assertEquals(0.0, actual.duplicateRolePenalty, 1e-9)
        }
        val offSystem = tankTeam().toMutableList().also {
            it[1] = pure.copy(support = LabyrinthRoleSupport(magicOffense = 100.0))
        }
        assertEquals(8.0, scorer.evaluate(offSystem, LabyrinthRoleDecisionContext(4)).duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `damage exemption follows target scenario and dual tags count only once`() {
        val team = tankTeam().toMutableList().also {
            it[1] = it[1].copy(physicalDamage1Target = 20.0, physicalDamage3Target = 80.0,
                functions = it[1].functions.copy(pureHealer = 100.0))
        }
        assertEquals(8.0, scorer.evaluate(team, LabyrinthRoleDecisionContext(4, targetCount = 1)).duplicateRolePenalty, 1e-9)
        assertEquals(0.0, scorer.evaluate(team, LabyrinthRoleDecisionContext(4, targetCount = 3)).duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `missing survival and explicit fallback halve but never eliminate penalties`() {
        val withoutHealing = tankTeam().toMutableList().also { it[2] = role("dps3") }
        assertEquals(2.0, scorer.evaluate(withoutHealing, LabyrinthRoleDecisionContext(0)).duplicateRolePenalty, 1e-9)
        assertEquals(4.0, scorer.evaluate(tankTeam(), LabyrinthRoleDecisionContext(4, survivalRecovery = true))
            .duplicateRolePenalty, 1e-9)
        val searcher = LabyrinthTeamPlanSearcher(LabyrinthTeamOptimizer(scorer),
            LabyrinthTeamPlanSearchConfig(oneTeamKillScore = 0.0, mainTeamScore = 0.0,
                cleanupTeamScore = 0.0, mainPlusCleanupCombinedScore = 0.0,
                stableTeamScore = 0.0, fallbackTeamScore = 0.0, threeTeamCombinedScore = 0.0))
        assertEquals(8.0, searcher.initialSearch(tankTeam(), LabyrinthRoleDecisionContext(4)).teams.single()
            .duplicateRolePenalty, 1e-9)
        assertEquals(4.0, searcher.fallbackSearch(tankTeam(), LabyrinthRoleDecisionContext(4)).teams.single()
            .duplicateRolePenalty, 1e-9)
    }

    @Test
    fun `optimizer prefers useful replacement but still returns forced double tank teams`() {
        val roster = tankTeam() + role("extraDamage")
        val best = requireNotNull(LabyrinthTeamOptimizer(scorer).bestFormation(roster, LabyrinthRoleDecisionContext(4)))
        assertEquals(1, best.pureTankCount)
        assertTrue(best.members.any { it.characterId == "extraDamage" })
        assertEquals(5, requireNotNull(LabyrinthTeamOptimizer(scorer).bestFormation(tankTeam(),
            LabyrinthRoleDecisionContext(4))).members.size)
    }

    @Test
    fun `invalid rule parameters fail fast`() {
        for (invalid in listOf<() -> Any>(
            { config.copy(duplicatePureRolePenalty = -1.0) },
            { config.copy(duplicatePureRolePenalty = Double.NaN) },
            { config.copy(compositeOffenseThreshold = 101.0) },
            { config.copy(lowDefenseDuplicateMultiplier = 1.1) },
        )) {
            assertTrue(runCatching(invalid).exceptionOrNull() is IllegalArgumentException)
        }
    }
}
