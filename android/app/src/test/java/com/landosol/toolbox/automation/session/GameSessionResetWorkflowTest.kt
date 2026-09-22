package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionResetWorkflowTest {
    private class FakeBackend(
        private val supported: Boolean = true,
        override val kind: ClientTerminationKind = ClientTerminationKind.FORCE_STOP,
    ) : GameSessionResetBackend {
        var terminateCalls = 0
        var relaunchCalls = 0
        var terminateResult = ClientTerminationResult.TERMINATED
        var relaunchResult = GameClientRelaunchResult.LAUNCH_REQUESTED

        override suspend fun terminateClient(): ClientTerminationResult {
            terminateCalls++
            return terminateResult
        }

        override suspend fun relaunchClient(): GameClientRelaunchResult {
            relaunchCalls++
            return relaunchResult
        }

        override fun isSupported(): Boolean = supported
    }

    private fun workflow(
        manager: AutomationSessionManager = AutomationSessionManager(),
        backend: FakeBackend = FakeBackend(),
        captureActive: () -> Boolean = { true },
        onNextRoundReady: suspend (AutomationSessionId) -> Unit = {},
    ) = GameSessionResetWorkflow(
        sessionManager = manager,
        backend = backend,
        captureActive = captureActive,
        processorFactory = { { error("test does not dispatch frames") } },
        blockClassifier = { _: LabyrinthEntryFrameResult -> SessionBlockKind.NONE },
        onNextRoundReady = onNextRoundReady,
        presence = FixedGameClientPresenceObserver(foreground = true),
    )

    @Test
    fun `start owns session mode session-reset and stop releases everything`() = runTest {
        val manager = AutomationSessionManager()
        val session = workflow(manager = manager)

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Started)
        assertTrue(session.state.value.running)
        assertEquals(AutomationMode.SESSION_RESET, manager.current()?.mode)

        assertTrue(session.stop())
        assertFalse(session.state.value.running)
        assertNull(manager.current())
        assertEquals(GameSessionResetStatus.STOPPED, session.state.value.status)
    }

    @Test
    fun `blocks when capture is unavailable`() = runTest {
        val manager = AutomationSessionManager()
        val session = workflow(manager = manager, captureActive = { false })

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Blocked)
        assertNull(manager.current())
        assertEquals(GameSessionResetStatus.ERROR, session.state.value.status)
    }

    @Test
    fun `blocks when no termination backend is supported`() = runTest {
        val manager = AutomationSessionManager()
        val session = workflow(manager = manager, backend = FakeBackend(supported = false))

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Blocked)
        assertNull(manager.current())
    }

    @Test
    fun `blocks when another automation session already runs`() = runTest {
        val manager = AutomationSessionManager()
        manager.start(AutomationMode.LABYRINTH, dryRun = true)
        val session = workflow(manager = manager)

        val result = session.start()

        assertTrue(result is GameSessionResetStartResult.Blocked)
        assertEquals(AutomationMode.LABYRINTH, manager.current()?.mode)
    }

    @Test
    fun `runOnceAfterRound saves result and returns true`() = runTest {
        val manager = AutomationSessionManager()
        val saves = AtomicInteger(0)
        val session = workflow(
            manager = manager,
            onNextRoundReady = {},
        )

        try {
            val started = session.runOnceAfterRound(saveResult = { saves.incrementAndGet() })

            assertTrue(started)
            // The workflow does this work on real dispatchers, but runTest's delay/withTimeout
            // run on virtual time and complete instantly. Waiting in virtual time therefore
            // burned the whole 2 s budget before the real callback could land, and the test
            // failed whenever the machine was busy — which is exactly when a full suite runs.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000L) {
                    while (saves.get() == 0) delay(10L)
                }
                assertEquals(1, saves.get())
                // A real pause, so a duplicate callback has an actual chance to arrive.
                delay(50L)
            }
            assertEquals("保存回调不能重复执行", 1, saves.get())
            assertTrue(session.state.value.running)
        } finally {
            session.stop()
        }
    }
}
