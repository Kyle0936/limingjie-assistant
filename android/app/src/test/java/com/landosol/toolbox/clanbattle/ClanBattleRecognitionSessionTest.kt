package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.clanbattle.axis.AxisParser
import com.landosol.toolbox.clanbattle.axis.AxisType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClanBattleRecognitionSessionTest {
    @Test
    fun `axis can be configured while idle and is visible in state`() = runTest {
        val session = ClanBattleRecognitionSession(AutomationSessionManager())
        val axis = AxisParser.parse(
            """
                轴类型=开关
                轴名称=开关样例
                [轴开局] | AUTO=开 | SET=开,关,开,关,开
                1:20 | AUTO=关 | SET=关,关,开,开,开
            """.trimIndent(),
        )

        assertTrue(session.configureAxis(axis))
        assertEquals("开关样例", session.state.value.axisName)
        assertEquals(AxisType.SWITCH, session.state.value.axisType)
    }

    @Test
    fun `running session rejects axis replacement`() = runTest {
        val manager = AutomationSessionManager()
        val session = ClanBattleRecognitionSession(manager, captureActive = { true }, processorFactory = { { error("unused") } })
        assertTrue(session.configureAxis(AxisParser.parse("轴类型=顺序\n[轴]\n0:01 | 提示=x")))
        assertTrue(session.start() is ClanBattleRecognitionStartResult.Started)
        try {
            assertTrue(!session.configureAxis(null))
        } finally {
            session.stop()
        }
    }

    @Test
    fun `overlay pause and emergency stop stay bound to recognition lifecycle`() = runTest {
        val manager = AutomationSessionManager()
        val overlay = AutomationOverlayCoordinator()
        val session = ClanBattleRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("unused") } },
            overlayCoordinator = overlay,
        )

        val started = session.start() as ClanBattleRecognitionStartResult.Started
        assertEquals(started.sessionId, overlay.state.value?.sessionId)
        assertTrue(overlay.state.value?.dryRun == true)

        assertTrue(overlay.setPaused(started.sessionId, true))
        assertTrue(session.state.value.paused)
        assertTrue(manager.current()?.paused == true)

        assertTrue(overlay.setPaused(started.sessionId, false))
        assertTrue(!session.state.value.paused)
        assertTrue(overlay.stop(started.sessionId))
        assertEquals(ClanBattleRecognitionStatus.STOPPED, session.state.value.status)
        assertNull(CaptureFrameBus.currentOwner())
        assertNull(manager.current())
        assertNull(overlay.state.value)
        val labyrinth = manager.start(AutomationMode.LABYRINTH, dryRun = true)
        assertTrue(labyrinth is AutomationSessionStartResult.Started)
    }

    @Test
    fun `recognition session owns capture lease and releases shared automation session`() = runTest {
        val manager = AutomationSessionManager()
        val session = ClanBattleRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("frame processing is not part of this test") } },
        )

        val started = session.start()

        assertTrue(started is ClanBattleRecognitionStartResult.Started)
        assertEquals("clan-battle-recognition-preview", CaptureFrameBus.currentOwner())
        assertTrue(session.state.value.running)
        assertTrue(session.stop())
        assertNull(CaptureFrameBus.currentOwner())
        assertNull(manager.current())
    }

    @Test
    fun `busy capture consumer rolls back newly created automation session`() = runTest {
        val manager = AutomationSessionManager()
        val external = CaptureFrameBus.register("other-feature") { frame -> frame.bitmap.recycle() }
            as CaptureFrameRegistrationResult.Registered
        try {
            val session = ClanBattleRecognitionSession(
                sessionManager = manager,
                captureActive = { true },
                processorFactory = { { error("unused") } },
            )

            val result = session.start()

            assertTrue(result is ClanBattleRecognitionStartResult.Blocked)
            assertNull(manager.current())
            assertEquals("other-feature", CaptureFrameBus.currentOwner())
        } finally {
            CaptureFrameBus.unregister(external.lease)
        }
    }

    @Test
    fun `capture permission is required before claiming shared session`() = runTest {
        val manager = AutomationSessionManager()
        val session = ClanBattleRecognitionSession(
            sessionManager = manager,
            captureActive = { false },
            processorFactory = { { error("unused") } },
        )

        val result = session.start()

        assertTrue(result is ClanBattleRecognitionStartResult.Blocked)
        assertNull(manager.current())
        assertNull(CaptureFrameBus.currentOwner())
    }
}
