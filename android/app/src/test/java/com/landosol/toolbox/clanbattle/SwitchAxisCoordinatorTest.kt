package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.PixelSize
import com.landosol.toolbox.clanbattle.axis.AxisToggleState
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.axis.BossDelayTrigger
import com.landosol.toolbox.clanbattle.axis.CharacterUbTrigger
import com.landosol.toolbox.clanbattle.axis.PauseFrameTrigger
import com.landosol.toolbox.clanbattle.axis.SwitchAxisNode
import com.landosol.toolbox.clanbattle.axis.SwitchAxisOpening
import com.landosol.toolbox.clanbattle.axis.SwitchControlTarget
import com.landosol.toolbox.clanbattle.axis.TimedTrigger
import com.landosol.toolbox.clanbattle.recognition.BattleControlObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind
import com.landosol.toolbox.clanbattle.recognition.EnergyObservation
import com.landosol.toolbox.clanbattle.recognition.FilterResult
import com.landosol.toolbox.clanbattle.recognition.ToggleObservation
import com.landosol.toolbox.clanbattle.recognition.VisualToggleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAxisCoordinatorTest {
    @Test
    fun `opening waits for trustworthy clock and emits one convergence command`() {
        val runtime = SwitchAxisRuntime(SwitchAxisOpening(1, target()), emptyList())

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(91, trustworthy = true)))
        val command = runtime.update(frame(89, trustworthy = true)) as SwitchRuntimeCommand.Converge
        assertEquals("opening-1", command.nodeId)
        assertTrue(runtime.confirmConvergence(command.nodeId))
        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(89, trustworthy = true)))
    }

    @Test
    fun `clock skip queues switch nodes in source order`() {
        val first = node("first", 72, TimedTrigger)
        val second = node("second", 71, TimedTrigger)
        val runtime = SwitchAxisRuntime(null, listOf(first, second))

        val firstCommand = runtime.update(frame(70)) as SwitchRuntimeCommand.Converge
        assertEquals("first", firstCommand.nodeId)
        runtime.confirmConvergence("first")
        val secondCommand = runtime.update(frame(70)) as SwitchRuntimeCommand.Converge
        assertEquals("second", secondCommand.nodeId)
    }

    @Test
    fun `character UB cannot satisfy a node on its arming frame`() {
        val runtime = SwitchAxisRuntime(
            null,
            listOf(node("role4", 57, CharacterUbTrigger(BattleSlot.SLOT_4, "角色4"))),
        )

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(57, triggered = setOf(BattleSlot.SLOT_4))),
        )
        assertTrue(
            runtime.update(frame(57, triggered = setOf(BattleSlot.SLOT_4))) is SwitchRuntimeCommand.Converge,
        )
    }

    @Test
    fun `boss delay never converges before detection deadline`() {
        val runtime = SwitchAxisRuntime(
            null,
            listOf(node("boss", 26, BossDelayTrigger(1_200L, "1.20"))),
        )
        runtime.update(frame(26, wall = 10_000))
        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(25, wall = 15_000, boss = SwitchBossUbEvent(26, 15_000))),
        )
        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(25, wall = 16_200, trustworthy = false)),
        )
        assertTrue(
            runtime.update(frame(25, wall = 16_300, trustworthy = true)) is SwitchRuntimeCommand.Converge,
        )
    }

    @Test
    fun `zero delay boss node accepts early hold confirmation`() {
        val runtime = SwitchAxisRuntime(
            null,
            listOf(node("boss", 60, BossDelayTrigger(0L, "0.00"))),
        )
        runtime.update(frame(60, wall = 0))

        assertTrue(
            runtime.update(
                frame(60, wall = 7_000, boss = SwitchBossUbEvent(60, 7_000, early = true)),
            ) is SwitchRuntimeCommand.Converge,
        )
    }

    @Test
    fun `positive delay boss node waits for completed hold event`() {
        val runtime = SwitchAxisRuntime(
            null,
            listOf(node("boss", 60, BossDelayTrigger(1_200L, "1.20"))),
        )
        runtime.update(frame(60, wall = 0))

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(
                frame(60, wall = 7_000, boss = SwitchBossUbEvent(60, 7_000, early = true)),
            ),
        )
        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(
                frame(59, wall = 7_300, boss = SwitchBossUbEvent(60, 7_300, early = false)),
            ),
        )
        assertTrue(
            runtime.update(frame(59, wall = 8_500)) is SwitchRuntimeCommand.Converge,
        )
    }

    @Test
    fun `pause frame blocks later nodes until confirmed and converged`() {
        val runtime = SwitchAxisRuntime(
            null,
            listOf(
                node("pause", 18, PauseFrameTrigger(BattleSlot.SLOT_3, "角色3")),
                node("later", 17, TimedTrigger),
            ),
        )

        val enter = runtime.update(frame(18)) as SwitchRuntimeCommand.EnterPauseFrame
        assertEquals(BattleSlot.SLOT_3, enter.role)
        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(17)))
        assertTrue(runtime.confirmPauseFrame("pause"))
        assertEquals("pause", (runtime.update(frame(17)) as SwitchRuntimeCommand.Converge).nodeId)
        runtime.confirmConvergence("pause")
        assertEquals("later", (runtime.update(frame(17)) as SwitchRuntimeCommand.Converge).nodeId)
    }

    @Test
    fun `coordinator converges AUTO with two-frame visual confirmation`() {
        val coordinator = SwitchAxisCoordinator(
            opening = null,
            nodes = listOf(node("first", 60, nodeTarget = target(auto = AxisToggleState.ON))),
        )

        val tap = coordinator.update(observation(60, auto = VisualToggleState.OFF), 1_000L)
        assertTrue(tap.intents.single() is ClanBattleActionIntent.TapAuto)
        assertEquals("first", tap.activeNodeId)

        val confirming = coordinator.update(observation(59, auto = VisualToggleState.ON), 1_100L)
        assertTrue(confirming.intents.isEmpty())
        val done = coordinator.update(observation(59, auto = VisualToggleState.ON), 1_200L)
        assertEquals("target-confirmed", done.reason)
        assertFalse(done.busy)
    }

    @Test
    fun `coordinator converges role SET targets one verified click at a time`() {
        val coordinator = SwitchAxisCoordinator(
            opening = null,
            nodes = listOf(node("roles", 60, nodeTarget = target(roleState = AxisToggleState.ON))),
        )

        val first = coordinator.update(observation(60), 1_000L)
        assertEquals(BattleSlot.SLOT_1, (first.intents.single() as ClanBattleActionIntent.TapRole).role)

        val roleOneOn = mapOf(BattleSlot.SLOT_1 to VisualToggleState.ON)
        coordinator.update(observation(59, roleStates = roleOneOn), 1_100L)
        val second = coordinator.update(observation(59, roleStates = roleOneOn), 1_200L)
        assertEquals(BattleSlot.SLOT_2, (second.intents.single() as ClanBattleActionIntent.TapRole).role)
    }

    @Test
    fun `coordinator pauses after repeated unconfirmed control click`() {
        val coordinator = SwitchAxisCoordinator(
            opening = null,
            nodes = listOf(node("first", 60, nodeTarget = target(auto = AxisToggleState.ON))),
        )
        coordinator.update(observation(60, auto = VisualToggleState.OFF), 1_000L)

        coordinator.update(observation(59, auto = VisualToggleState.OFF), 1_100L)
        coordinator.update(observation(59, auto = VisualToggleState.OFF), 1_200L)
        val paused = coordinator.update(observation(59, auto = VisualToggleState.OFF), 1_300L)

        assertTrue(paused.intents.single() is ClanBattleActionIntent.Pause)
        assertEquals("control-click-confirmation-failed", paused.reason)
    }

    @Test
    fun `coordinator pause frame confirmation emits role click before target convergence`() {
        val coordinator = SwitchAxisCoordinator(
            opening = null,
            nodes = listOf(node("pause", 18, PauseFrameTrigger(BattleSlot.SLOT_3, "角色3"))),
        )
        val entered = coordinator.update(observation(18), 1_000L)
        assertTrue(entered.intents.single() is ClanBattleActionIntent.Pause)
        assertTrue(coordinator.confirmPauseFrame("pause"))

        val roleTap = coordinator.update(observation(18), 1_100L)
        assertEquals(BattleSlot.SLOT_3, (roleTap.intents.single() as ClanBattleActionIntent.TapRole).role)
        val targetDone = coordinator.update(observation(17), 1_200L)
        assertEquals("target-confirmed", targetDone.reason)
    }

    @Test
    fun `obscured menu anchor blocks switch axis convergence`() {
        val coordinator = SwitchAxisCoordinator(
            opening = null,
            nodes = listOf(node("timed", 60)),
        )

        val result = coordinator.update(
            observation(clock = 60, trustworthy = true, actionSafe = false),
            wallTimeMillis = 1_000L,
        )

        assertTrue(result.intents.isEmpty())
        assertEquals("waiting-runtime", result.reason)
    }

    private fun node(
        id: String,
        time: Int,
        trigger: com.landosol.toolbox.clanbattle.axis.SwitchNodeTrigger = TimedTrigger,
        nodeTarget: SwitchControlTarget = target(),
    ) = SwitchAxisNode(id, 2, time, trigger, nodeTarget)

    private fun target(
        auto: AxisToggleState = AxisToggleState.OFF,
        roleState: AxisToggleState = AxisToggleState.OFF,
    ) = SwitchControlTarget(
        auto = auto,
        roles = BattleSlot.entries.associateWith { roleState },
        rawAuto = if (auto == AxisToggleState.ON) "开" else "关",
        rawRoles = List(5) { if (roleState == AxisToggleState.ON) "开" else "关" },
    )

    private fun frame(
        clock: Int,
        wall: Long = 0L,
        triggered: Set<BattleSlot> = emptySet(),
        trustworthy: Boolean = true,
        boss: SwitchBossUbEvent? = null,
    ) = SwitchAxisFrame(clock, triggered, trustworthy, wall, boss)

    private fun observation(
        clock: Int,
        auto: VisualToggleState = VisualToggleState.OFF,
        roleStates: Map<BattleSlot, VisualToggleState> = emptyMap(),
        trustworthy: Boolean = true,
        actionSafe: Boolean = trustworthy,
    ) = ClanBattleObservation(
        frameTimestampMillis = clock * 1_000L,
        frameSize = PixelSize(1920, 1080),
        viewport = null,
        screenKind = ClanBattleScreenKind.BATTLE,
        screenConfidence = 1.0,
        clock = null,
        filteredClock = FilterResult(true, timeSeconds = clock, rawText = "0:${clock.toString().padStart(2, '0')}"),
        energy = EnergyObservation(emptyMap(), null, emptySet()),
        controls = BattleControlObservation(
            auto = ToggleObservation(auto, 1.0, 0.0, 1.0),
            globalSet = ToggleObservation(VisualToggleState.OFF, 1.0, 0.0, 1.0),
            roleSets = BattleSlot.entries.associateWith { role ->
                ToggleObservation(roleStates[role] ?: VisualToggleState.OFF, 1.0, 0.0, 1.0)
            },
            consistent = trustworthy,
            trustworthy = trustworthy,
        ),
        actionSafe = actionSafe,
        shouldPause = false,
        diagnostics = emptyList(),
    )
}
