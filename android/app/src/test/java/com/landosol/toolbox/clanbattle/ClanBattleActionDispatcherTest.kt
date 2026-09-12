package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.PixelSize
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.recognition.BattleControlObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind
import com.landosol.toolbox.clanbattle.recognition.FilterResult
import com.landosol.toolbox.clanbattle.recognition.FrameViewport
import com.landosol.toolbox.clanbattle.recognition.ToggleObservation
import com.landosol.toolbox.clanbattle.recognition.VisualToggleState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClanBattleActionDispatcherTest {
    @Test
    fun `wide viewport maps reference role coordinate and stays dry run`() = runTest {
        val manager = AutomationSessionManager()
        val session = (manager.start(AutomationMode.CLAN_BATTLE, dryRun = true) as AutomationSessionStartResult.Started).session
        val dispatcher = ClanBattleActionDispatcher(
            SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
        )
        val observation = safeObservation(
            frameSize = PixelSize(2560, 1080),
            viewport = FrameViewport(320, 0, 1920, 1080, 1f),
        )

        val result = dispatcher.dispatch(
            session.id,
            ClanBattleActionIntent.TapRole("event", BattleSlot.SLOT_1, "test"),
            observation,
        )

        assertEquals(
            ClanBattleDispatchResult.Action(
                AutomationActionResult.DryRun(AutomationAction.Tap(ScreenPoint(800f, 845f))),
            ),
            result,
        )
    }

    @Test
    fun `unsafe observation is blocked before backend`() = runTest {
        val manager = AutomationSessionManager()
        val session = (manager.start(AutomationMode.CLAN_BATTLE, dryRun = false) as AutomationSessionStartResult.Started).session
        var backendCalled = false
        val dispatcher = ClanBattleActionDispatcher(
            SessionBoundActionExecutor(manager) {
                backendCalled = true
                AutomationBackendResult.Completed
            },
        )

        val result = dispatcher.dispatch(
            session.id,
            ClanBattleActionIntent.TapAuto("event", "test"),
            safeObservation(actionSafe = false),
        )

        assertEquals(ClanBattleDispatchResult.Blocked("observation-not-action-safe"), result)
        assertTrue(!backendCalled)
    }

    private fun safeObservation(
        actionSafe: Boolean = true,
        frameSize: PixelSize = PixelSize(1920, 1080),
        viewport: FrameViewport = FrameViewport(0, 0, 1920, 1080, 1f),
    ) = ClanBattleObservation(
        frameTimestampMillis = 1L,
        frameSize = frameSize,
        viewport = viewport,
        screenKind = ClanBattleScreenKind.BATTLE,
        screenConfidence = 1.0,
        clock = null,
        filteredClock = FilterResult(true, timeSeconds = 80, rawText = "1:20"),
        energy = null,
        controls = BattleControlObservation(
            auto = ToggleObservation(VisualToggleState.OFF, 1.0),
            globalSet = ToggleObservation(VisualToggleState.OFF, 1.0),
            roleSets = BattleSlot.entries.associateWith { ToggleObservation(VisualToggleState.OFF, 1.0) },
            consistent = true,
            trustworthy = true,
        ),
        actionSafe = actionSafe,
        shouldPause = false,
        diagnostics = emptyList(),
    )
}
