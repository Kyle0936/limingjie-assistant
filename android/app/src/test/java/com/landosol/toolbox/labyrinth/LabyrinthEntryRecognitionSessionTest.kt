package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEntryRecognitionSessionTest {
    @Test
    fun `starts dry run and releases the shared automation session`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
        )

        val result = session.start()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.state.value.running)
        assertTrue(manager.current()?.mode == AutomationMode.LABYRINTH)
        assertTrue(manager.current()?.dryRun == true)

        assertTrue(session.stop())
        assertFalse(session.state.value.running)
        assertTrue(manager.current() == null)
        assertTrue(captureStopCalls == 1)
    }

    @Test
    fun `blocks before loading templates when capture is unavailable`() = runTest {
        var factoryCalled = false
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = AutomationSessionManager(),
            captureActive = { false },
            processorFactory = {
                factoryCalled = true
                error("must not load")
            },
        )

        val result = session.start()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertFalse(factoryCalled)
        assertTrue(session.state.value.status == LabyrinthEntryRecognitionStatus.ERROR)
    }

    @Test
    fun `live mode blocks before templates when accessibility is unavailable`() = runTest {
        var factoryCalled = false
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = {
                factoryCalled = true
                error("must not load")
            },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { false },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertFalse(factoryCalled)
        assertTrue(manager.current() == null)
    }

    @Test
    fun `live mode checks accessibility before capture`() = runTest {
        var captureChecked = false
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = {
                captureChecked = true
                false
            },
            processorFactory = { error("must not load") },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { false },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertFalse(captureChecked)
        assertTrue(session.state.value.message?.contains("无障碍服务未连接") == true)
    }

    @Test
    fun `live mode owns a non dry run session and launches the game`() = runTest {
        var launchCalls = 0
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            gameLauncher = { launchCalls++; true },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Started)
        assertFalse(session.state.value.dryRun)
        assertFalse(manager.current()!!.dryRun)
        assertTrue(launchCalls == 1)
        assertTrue(session.stop())
    }

    @Test
    fun `failed game launch releases capture and shared session`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            gameLauncher = { false },
        )

        val result = session.startAutomation()

        assertTrue(result is LabyrinthEntryRecognitionStartResult.Blocked)
        assertTrue(manager.current() == null)
        assertTrue(CaptureFrameBus.currentOwner() == null)
        assertTrue(captureStopCalls == 1)
    }
}
