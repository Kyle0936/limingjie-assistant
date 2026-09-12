package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionExecutionResult
import com.landosol.toolbox.automation.PixelSize
import com.landosol.toolbox.clanbattle.axis.AxisParser
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.recognition.BattleControlObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind
import com.landosol.toolbox.clanbattle.recognition.EnergyObservation
import com.landosol.toolbox.clanbattle.recognition.FilterResult
import com.landosol.toolbox.clanbattle.recognition.ToggleObservation
import com.landosol.toolbox.clanbattle.recognition.VisualToggleState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClanBattleStateMachineTest {
    @Test
    fun `manual pause resumes the previous preflight phase`() {
        val machine = ClanBattleStateMachine(AxisParser.parse("轴类型=顺序\n[轴]\n1:16 | 点击=AUTO"))
        machine.start()

        machine.pause("manual")
        val resumed = machine.resume()

        assertEquals(ClanBattleRuntimePhase.WAITING_FOR_BATTLE, resumed.state.phase)
    }

    @Test
    fun `sequence role action waits for confirmed UB before emitting close intent`() {
        val axis = AxisParser.parse(
            """
                轴类型=顺序
                [轴]
                1:16 | 点击=角色1
            """.trimIndent(),
        )
        val machine = ClanBattleStateMachine(axis)
        machine.start()

        machine.onObservation(observation(90))
        machine.onObservation(observation(90))
        val open = machine.onObservation(observation(76))

        assertEquals(ClanBattleRuntimePhase.WAITING_FOR_ROLE_SET_ON, open.state.phase)
        assertTrue(open.intents.single() is ClanBattleActionIntent.TapRole)
        assertEquals(BattleSlot.SLOT_1, (open.intents.single() as ClanBattleActionIntent.TapRole).role)

        val confirmedOn = machine.onObservation(observation(76, roleOn = BattleSlot.SLOT_1))
        assertEquals(ClanBattleRuntimePhase.WAITING_FOR_ROLE_UB, confirmedOn.state.phase)
        val waiting = machine.onObservation(observation(75, roleOn = BattleSlot.SLOT_1))
        assertTrue(waiting.intents.isEmpty())
        val close = machine.onObservation(
            observation(74, confirmedDrop = BattleSlot.SLOT_1, roleOn = BattleSlot.SLOT_1),
        )

        assertEquals(ClanBattleRuntimePhase.WAITING_FOR_ROLE_SET_OFF, close.state.phase)
        assertEquals("finish-role-ub-lifecycle", (close.intents.single() as ClanBattleActionIntent.TapRole).purpose)
        val confirmedOff = machine.onObservation(observation(74))
        assertEquals(ClanBattleRuntimePhase.RUNNING, confirmedOff.state.phase)
    }

    @Test
    fun `multiple actions on one axis line are dispatched in order`() {
        val axis = AxisParser.parse("轴类型=顺序\n[轴]\n1:16 | 点击=角色1 | 点击=AUTO")
        val machine = ClanBattleStateMachine(axis)
        machine.start()
        machine.onObservation(observation(90))
        machine.onObservation(observation(90))
        machine.onObservation(observation(76))
        machine.onObservation(observation(76, roleOn = BattleSlot.SLOT_1))
        machine.onObservation(observation(75, confirmedDrop = BattleSlot.SLOT_1, roleOn = BattleSlot.SLOT_1))
        machine.onObservation(observation(75))

        val next = machine.onObservation(observation(75))

        assertTrue(next.intents.single() is ClanBattleActionIntent.TapAuto)
        assertEquals(ClanBattleRuntimePhase.WAITING_FOR_AUTO_CONFIRM, next.state.phase)
        val confirmedAuto = machine.onObservation(observation(75, autoOn = true))
        assertEquals(ClanBattleRuntimePhase.RUNNING, confirmedAuto.state.phase)
        assertEquals(1, confirmedAuto.state.nextEventIndex)
        assertEquals(0, confirmedAuto.state.nextActionIndex)
    }

    @Test
    fun `missing SET visual confirmation pauses without retry clicking`() {
        val machine = ClanBattleStateMachine(AxisParser.parse("轴类型=顺序\n[轴]\n1:16 | 点击=角色1"))
        machine.start()
        machine.onObservation(observation(90))
        machine.onObservation(observation(90))
        val intent = machine.onObservation(observation(76))

        val first = machine.onObservation(observation(76))
        val second = machine.onObservation(observation(76))
        val paused = machine.onObservation(observation(76))

        assertTrue(intent.intents.single() is ClanBattleActionIntent.TapRole)
        assertTrue(first.intents.isEmpty())
        assertTrue(second.intents.isEmpty())
        assertEquals(ClanBattleRuntimePhase.PAUSED, paused.state.phase)
    }

    @Test
    fun `pause frame requires explicit confirmation and then advances to the next event`() {
        val axis = AxisParser.parse(
            """
                轴类型=顺序
                [轴]
                1:09 | 卡帧=角色1
                1:05 | 点击=AUTO
            """.trimIndent(),
        )
        val machine = ClanBattleStateMachine(axis)
        machine.start()
        machine.onObservation(observation(90))
        machine.onObservation(observation(90))

        val paused = machine.onObservation(observation(69))
        assertEquals(ClanBattleRuntimePhase.PAUSED, paused.state.phase)
        assertEquals(BattleSlot.SLOT_1, paused.state.pendingPauseFrameRole)
        assertEquals(
            "pause-frame-confirmation-required",
            machine.resume().diagnostics.single(),
        )
        assertEquals(ClanBattleRuntimePhase.PAUSED, machine.snapshot().phase)

        val confirmed = machine.confirmManualPauseFrame()
        assertEquals("pause-frame-confirmed", confirmed.diagnostics.single())
        assertEquals(ClanBattleRuntimePhase.RUNNING, confirmed.state.phase)
        assertEquals(1, confirmed.state.nextEventIndex)

        val next = machine.onObservation(observation(65))
        assertTrue(next.intents.single() is ClanBattleActionIntent.TapAuto)
    }

    @Test
    fun `confirmed pause frame can dispatch actions declared on the same line`() {
        val machine = ClanBattleStateMachine(
            AxisParser.parse("轴类型=顺序\n[轴]\n1:09 | 卡帧=角色1 | 点击=AUTO"),
        )
        machine.start()
        machine.onObservation(observation(90))
        machine.onObservation(observation(90))
        machine.onObservation(observation(69))

        val confirmed = machine.confirmManualPauseFrame()
        assertEquals(0, confirmed.state.nextEventIndex)
        val action = machine.onObservation(observation(69))

        assertTrue(action.intents.single() is ClanBattleActionIntent.TapAuto)
        assertEquals(ClanBattleRuntimePhase.WAITING_FOR_AUTO_CONFIRM, action.state.phase)
    }

    @Test
    fun `unsafe control observation pauses before creating intent`() {
        val axis = AxisParser.parse("轴类型=顺序\n[轴]\n1:16 | 点击=角色1")
        val machine = ClanBattleStateMachine(axis)
        machine.start()
        machine.onObservation(observation(90))
        machine.onObservation(observation(90))

        val result = machine.onObservation(observation(76, controlsTrustworthy = false))

        assertEquals(ClanBattleRuntimePhase.PAUSED, result.state.phase)
        assertTrue(result.intents.single() is ClanBattleActionIntent.Pause)
    }

    @Test
    fun `unsupported boss trigger pauses instead of being treated as timed`() {
        val axis = AxisParser.parse("轴类型=顺序\n[轴]\n1:16 | UB后=BOSS | 点击=AUTO")
        val machine = ClanBattleStateMachine(axis)
        machine.start()
        machine.onObservation(observation(90))
        machine.onObservation(observation(90))

        val result = machine.onObservation(observation(76))

        assertEquals(ClanBattleRuntimePhase.PAUSED, result.state.phase)
        assertTrue(result.diagnostics.single().contains("boss-trigger"))
    }

    @Test
    fun `session controller enforces mutual exclusion pause and stale session`() = runTest {
        val manager = AutomationSessionManager(clock = { 1L })
        val controller = ClanBattleSessionController(manager)
        val axis = AxisParser.parse("轴类型=顺序\n[轴]\n1:16 | 点击=AUTO")
        val first = controller.start(axis, dryRun = true) as ClanBattleSessionStartResult.Started
        val duplicate = controller.start(axis, dryRun = true)

        assertTrue(duplicate is ClanBattleSessionStartResult.AlreadyRunning)
        assertTrue(controller.pause(first.handle.session.id, "test-pause"))
        assertEquals(
            SessionExecutionResult.Paused,
            controller.onObservation(first.handle.session.id, observation(90)),
        )
        assertTrue(controller.resume(first.handle.session.id))
        assertTrue(controller.stop(first.handle.session.id))
        assertEquals(
            SessionExecutionResult.StaleSession,
            controller.onObservation(first.handle.session.id, observation(90)),
        )
    }

    @Test
    fun `session controller gates automatic pause and allows only explicit frame confirmation`() = runTest {
        val manager = AutomationSessionManager(clock = { 1L })
        val controller = ClanBattleSessionController(manager)
        val axis = AxisParser.parse(
            """
                轴类型=顺序
                [轴]
                1:09 | 卡帧=角色1
            """.trimIndent(),
        )
        val started = controller.start(axis, dryRun = true) as ClanBattleSessionStartResult.Started
        val sessionId = started.handle.session.id

        controller.onObservation(sessionId, observation(90))
        controller.onObservation(sessionId, observation(90))
        val paused = controller.onObservation(sessionId, observation(69))
        assertTrue(paused is SessionExecutionResult.Accepted)
        assertEquals(ClanBattleRuntimePhase.PAUSED, (paused as SessionExecutionResult.Accepted).value.state.phase)
        assertTrue(manager.current()?.paused == true)

        assertTrue(controller.resume(sessionId).not())
        val confirmed = controller.confirmManualPauseFrame(sessionId)
        assertTrue(confirmed is SessionExecutionResult.Accepted)
        assertEquals(
            ClanBattleRuntimePhase.RUNNING,
            (confirmed as SessionExecutionResult.Accepted).value.state.phase,
        )
        assertTrue(manager.current()?.paused == false)
    }

    private fun observation(
        seconds: Int,
        confirmedDrop: BattleSlot? = null,
        roleOn: BattleSlot? = null,
        autoOn: Boolean = false,
        controlsTrustworthy: Boolean = true,
    ) = ClanBattleObservation(
        frameTimestampMillis = seconds * 1_000L,
        frameSize = PixelSize(1920, 1080),
        viewport = null,
        screenKind = ClanBattleScreenKind.BATTLE,
        screenConfidence = 1.0,
        clock = null,
        filteredClock = FilterResult(true, timeSeconds = seconds, rawText = "1:${seconds.toString().padStart(2, '0')}"),
        energy = EnergyObservation(
            slots = emptyMap(),
            averageDelta = null,
            confirmedDrops = confirmedDrop?.let(::setOf).orEmpty(),
        ),
        controls = controls(controlsTrustworthy, roleOn, autoOn),
        actionSafe = controlsTrustworthy,
        shouldPause = false,
        diagnostics = emptyList(),
    )

    private fun controls(trustworthy: Boolean, roleOn: BattleSlot?, autoOn: Boolean) = BattleControlObservation(
        auto = ToggleObservation(if (autoOn) VisualToggleState.ON else VisualToggleState.OFF, 1.0, 0.0, 1.0),
        globalSet = ToggleObservation(VisualToggleState.OFF, 1.0, 0.0, 1.0),
        roleSets = BattleSlot.entries.associateWith { slot ->
            ToggleObservation(if (slot == roleOn) VisualToggleState.ON else VisualToggleState.OFF, 1.0, 0.0, 1.0)
        },
        consistent = trustworthy,
        trustworthy = trustworthy,
    )
}
