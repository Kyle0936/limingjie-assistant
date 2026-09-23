package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.protocol.labyrinth.LabyrinthTop
import com.landosol.toolbox.protocol.labyrinth.LabyrinthResume
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthExecutionGateTest {
    @Test
    fun `execution requires target checkpoint and matching server enter id`() {
        val route = route()
        val checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId)
        val allowed = validateLabyrinthExecution(route, checkpoint, top(route.enterId))

        assertTrue(allowed is LabyrinthExecutionGateResult.Allowed)
    }

    @Test
    fun `old local route is rejected when server has a new enter id`() {
        val route = route(101L)
        val result = validateLabyrinthExecution(
            route = route,
            checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId),
            top = top(202L),
        )

        assertTrue(result is LabyrinthExecutionGateResult.Blocked)
        assertTrue((result as LabyrinthExecutionGateResult.Blocked).message.contains("不一致"))
    }

    @Test
    fun `pending checkpoint is not executable but non target can reuse same cached opening`() {
        val route = route()

        assertTrue(
            validateLabyrinthExecution(
                route,
                checkpoint(LabyrinthRouteVerdict.PENDING_VERIFICATION, route.enterId),
                top(route.enterId),
            ) is LabyrinthExecutionGateResult.Blocked,
        )
        val cached = validateLabyrinthExecution(
            route,
            checkpoint(LabyrinthRouteVerdict.NOT_TARGET, route.enterId),
            top = null,
        )
        assertTrue(cached is LabyrinthExecutionGateResult.Allowed)
        assertTrue((cached as LabyrinthExecutionGateResult.Allowed).message.contains("Enter ID"))
    }

    @Test
    fun `pending checkpoint remains blocked even when enter id matches`() {
        val route = route()
        val cached = validateLabyrinthExecution(
            route,
            checkpoint(LabyrinthRouteVerdict.PENDING_VERIFICATION, route.enterId),
            top = null,
        )

        assertTrue(cached is LabyrinthExecutionGateResult.Blocked)
    }

    @Test
    fun `takeover requires the live run to belong to the saved route`() {
        val route = route(currentBlockId = 10001L)
        val result = validateLabyrinthTakeoverExecution(
            route = route,
            checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId),
            top = top(route.enterId),
            resume = resume(route.enterId, currentBlockId = 99999L),
        )

        assertTrue(result is LabyrinthExecutionGateResult.Blocked)
        assertTrue((result as LabyrinthExecutionGateResult.Blocked).message.contains("不在已保存路线"))
    }

    @Test
    fun `takeover advances a stale local cursor to the server node`() {
        val route = route(currentBlockId = 10001L)
        val result = validateLabyrinthTakeoverExecution(
            route = route,
            checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId),
            top = top(route.enterId),
            resume = resume(route.enterId, currentBlockId = 20001L),
        )

        assertTrue(result is LabyrinthExecutionGateResult.Allowed)
        assertEquals(20001L, (result as LabyrinthExecutionGateResult.Allowed).route.currentBlockId)
    }

    @Test
    fun `takeover rejects a server cursor behind the persisted route`() {
        val route = route(currentBlockId = 20001L)
        val result = validateLabyrinthTakeoverExecution(
            route = route,
            checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId),
            top = top(route.enterId),
            resume = resume(route.enterId, currentBlockId = 10001L),
        )

        assertTrue(result is LabyrinthExecutionGateResult.Blocked)
        assertTrue((result as LabyrinthExecutionGateResult.Blocked).message.contains("领先"))
    }

    @Test
    fun `takeover rejects a corrupt persisted cursor outside the saved route`() {
        val route = route(currentBlockId = 77777L)
        val result = validateLabyrinthTakeoverExecution(
            route = route,
            checkpoint = checkpoint(LabyrinthRouteVerdict.TARGET, route.enterId),
            top = top(route.enterId),
            resume = resume(route.enterId, currentBlockId = 10001L),
        )

        assertTrue(result is LabyrinthExecutionGateResult.Blocked)
        assertTrue((result as LabyrinthExecutionGateResult.Blocked).message.contains("路线数据已损坏"))
    }

    private fun route(enterId: Long = 101L, currentBlockId: Long? = null) = LabyrinthRouteJson(
        enterId = enterId,
        guildId = 1,
        difficulty = 1,
        attempt = 1,
        rerollUntilFound = false,
        perfectStart = false,
        thirdBlockChoice = LabyrinthThirdBlockChoice.EITHER.name,
        area3BossIds = emptyList(),
        area5BossIds = emptyList(),
        nodes = listOf(node(10001L), node(20001L)),
        allNodes = listOf(node(10001L), node(20001L)),
        currentBlockId = currentBlockId,
    )

    private fun checkpoint(verdict: LabyrinthRouteVerdict, enterId: Long) = LabyrinthRerollCheckpoint(
        accountId = 1L,
        attempt = 1,
        enterId = enterId,
        guildId = 1,
        difficulty = 1,
        policy = LabyrinthRoutePolicy(),
        verdict = verdict,
        message = null,
        updatedAt = 1L,
    )

    private fun top(enterId: Long) = LabyrinthTop(
        enterId = enterId,
        guildId = 1,
        difficulty = 1,
        clearedDifficulties = emptyList(),
    )

    private fun resume(enterId: Long, currentBlockId: Long) = LabyrinthResume(
        enterId = enterId,
        guildId = 1,
        currentBlockId = currentBlockId,
        map = listOf(mapNode(10001L), mapNode(20001L), mapNode(99999L)),
    )

    private fun mapNode(blockId: Long) = LabyrinthMapNode(
        area = 1,
        column = 1,
        row = 1,
        blockId = blockId,
        blockType = 1,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = false,
    )

    private fun node(blockId: Long) = LabyrinthNodeJson(
        area = 1,
        column = 1,
        row = 1,
        blockId = blockId,
        blockType = 1,
        questId = null,
        nextBlockIds = emptyList(),
        isAreaLastPoint = true,
    )
}
