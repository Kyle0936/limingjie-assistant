package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    fun `stop can end the run while keeping the capture session for the next run`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
        )

        assertTrue(session.start() is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop("handing off to reroll", releaseCapture = false))

        // Run lifetime ended: shared automation session and frame ownership are released.
        assertFalse(session.state.value.running)
        assertTrue(manager.current() == null)
        assertTrue(CaptureFrameBus.currentOwner() == null)
        // Capture lifetime continues: the projection is untouched.
        assertTrue(captureStopCalls == 0)

        // A second run can start on the same capture without a new authorization.
        assertTrue(session.start() is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop())
        assertTrue(captureStopCalls == 1)
    }

    @Test
    fun `stop without an active run still honours the capture flag`() = runTest {
        var captureStopCalls = 0
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = AutomationSessionManager(),
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
        )

        assertFalse(session.stop(releaseCapture = false))
        assertTrue(captureStopCalls == 0)
        assertFalse(session.stop())
        assertTrue(captureStopCalls == 1)
    }

    @Test
    fun `run logic stops keep capture alive while a batch owns the run`() = runTest {
        val manager = AutomationSessionManager()
        var captureStopCalls = 0
        val events = mutableListOf<LabyrinthRunTerminalEvent>()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            captureStop = { captureStopCalls++ },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            runTerminalListener = { events += it },
        )

        // Batch-owned: a planner/terminal-page stop must not touch capture.
        assertTrue(session.startAutomation(accountId = 1L, runId = "b1-run001") is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stopFromRunLogic("已完成最终结算并返回黎明界主页"))
        awaitEvent(events)
        assertTrue(captureStopCalls == 0)
        assertTrue(manager.current() == null)
        assertTrue(events.single().runId == "b1-run001")

        // Interactive: the same stop releases capture as before.
        events.clear()
        assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stopFromRunLogic("点击被拒绝：test"))
        settle()
        assertTrue(captureStopCalls == 1)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `batch run reports a formal terminal event and an interactive run reports none`() = runTest {
        val manager = AutomationSessionManager()
        val events = mutableListOf<LabyrinthRunTerminalEvent>()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            runTerminalListener = { events += it },
        )

        // Batch-owned run stopped by the user (capture released, no explicit outcome).
        assertTrue(session.startAutomation(accountId = 1L, runId = "b1-run001") is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop("用户停止"))
        awaitEvent(events)
        assertTrue(events.single() is LabyrinthRunTerminalEvent.UserStopped)
        assertTrue(events.single().runId == "b1-run001")

        // Batch-owned run stopped for the reroll handoff: capture retained, no explicit outcome
        // set by the test path, so it is an unexplained stop the batch must not count.
        events.clear()
        assertTrue(session.startAutomation(accountId = 1L, runId = "b1-run002") is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop("环境异常", releaseCapture = false))
        awaitEvent(events)
        assertTrue(events.single() is LabyrinthRunTerminalEvent.FatalUnknown)

        // Interactive run: no run id, no event.
        events.clear()
        assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.stop())
        settle()
        assertTrue(events.isEmpty())
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
    fun `manual takeover suppresses unknown and return-title actions until route frames are stable`() = runTest {
        var launchCalls = 0
        val actions = mutableListOf<com.landosol.toolbox.automation.AutomationAction>()
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { action ->
                actions += action
                AutomationBackendResult.Completed
            },
            actionsAvailable = { true },
            gameLauncher = { launchCalls++; true },
        )

        assertTrue(session.startAutomation(takeoverCurrentRun = true) is LabyrinthEntryRecognitionStartResult.Started)
        assertTrue(session.state.value.message?.contains("不重启游戏或刷开局") == true)
        assertTrue(launchCalls == 0)

        // These are the exact dangerous frames from the review: UNKNOWN and a false-positive
        // session-expiry popup. Neither may reach the normal action handler while takeover waits.
        session.processRecognizedFrameForTest(frame(LabyrinthEntryPageState.UNKNOWN), 1_000L)
        session.processRecognizedFrameForTest(sessionBlockFrame(), 2_000L)
        session.processRecognizedFrameForTest(sessionBlockFrame(), 3_000L)
        settle()
        assertTrue(actions.isEmpty())
        assertTrue(session.state.value.message?.contains("接管等待中") == true)

        // One route frame is insufficient; two consecutive stable route frames release the gate.
        session.processRecognizedFrameForTest(frame(LabyrinthEntryPageState.NODE_SELECTION), 4_000L)
        assertTrue(session.state.value.message?.contains("正在确认") == true)
        session.processRecognizedFrameForTest(frame(LabyrinthEntryPageState.NODE_SELECTION), 5_000L)
        assertFalse(session.takeoverPendingForTest())
        assertTrue(session.stop())
    }

    private fun frame(state: LabyrinthEntryPageState) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = if (state == LabyrinthEntryPageState.UNKNOWN) 0.0 else 1.0,
            stateScores = emptyMap(),
            anchorScores = LabyrinthAnchorScores(emptyMap()),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0L,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    private fun sessionBlockFrame(): LabyrinthEntryFrameResult {
        val rect = EntryPixelRect(800, 650, 320, 120)
        return LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.HOME,
                confidence = 0.9,
                stateScores = emptyMap(),
                anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.SESSION_ERROR_TITLE to 0.99)),
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 0L,
            anchorMatches = mapOf(
                EntryAnchorId.SESSION_RETURN_TITLE to EntryAnchorMatch(0.99, rect),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
        )
    }

    @Test
    fun `manual takeover stops after its bounded wait`() = runTest {
        val manager = AutomationSessionManager()
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("test does not dispatch frames") } },
            actionExecutor = SessionBoundActionExecutor(manager) { AutomationBackendResult.Completed },
            actionsAvailable = { true },
            takeoverWaitTimeoutMillis = 20L,
        )

        assertTrue(session.startAutomation(takeoverCurrentRun = true) is LabyrinthEntryRecognitionStartResult.Started)
        withContext(Dispatchers.Default) {
            withTimeout(2_000L) { while (session.state.value.running) delay(10L) }
        }
        assertFalse(session.state.value.running)
        assertTrue(session.state.value.message?.contains("已安全停止") == true)
        assertTrue(manager.current() == null)
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

    /**
     * Terminal events are delivered from the session's own Dispatchers.Default scope, so runTest's
     * virtual-time delay proves nothing about them; wait on the wall clock instead.
     */
    private suspend fun awaitEvent(events: List<LabyrinthRunTerminalEvent>) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { while (events.isEmpty()) delay(10L) }
        }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(200L) }
}
