package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRoleRewardCommitTest {
    @Test fun `successful choice persists before any joined page and stop clears the bypass`() =
        exercise(AutomationBackendResult.Completed, failStore = false, expectedWrites = 1, expectedBypass = true)

    @Test fun `rejected choice never records ownership or bypasses joined recognition`() =
        exercise(AutomationBackendResult.Rejected("test rejection"), failStore = false, expectedWrites = 0, expectedBypass = false)

    @Test fun `failed persistence keeps joined recognition available for recovery`() =
        exercise(AutomationBackendResult.Completed, failStore = true, expectedWrites = 1, expectedBypass = false)

    private fun exercise(backend: AutomationBackendResult, failStore: Boolean, expectedWrites: Int, expectedBypass: Boolean) = runBlocking {
        val manager = AutomationSessionManager()
        val store = MemoryStore(failStore)
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("no captured frames in this action test") } },
            actionExecutor = SessionBoundActionExecutor(manager) { backend },
            actionsAvailable = { true }, gameLauncher = { true }, runStateStore = store,
        )
        try {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            @Suppress("UNCHECKED_CAST")
            val state = field(session, "_state").get(session) as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            val rect = EntryPixelRect(100, 100, 80, 40)
            state.value = state.value.copy(roleRewardChoiceDecision = LabyrinthRoleRewardChoiceDecision.Select(
                "1002", "千歌（礼服）", rect, emptyList(),
            ))
            val dispatch = session.javaClass.declaredMethods.single {
                it.name.startsWith("dispatchPostEntryTap-") && !it.name.endsWith("\$default")
            }.apply { isAccessible = true }
            dispatch.invoke(session, manager.current()!!.id.value, "选择角色：千歌（礼服）", rect,
                System.currentTimeMillis(), "left:1002", "1002", null, null, null, false)
            val inFlight = field(session, "actionInFlight").get(session) as AtomicBoolean
            withTimeout(5_000) { while (inFlight.get()) delay(10) }
            assertEquals(expectedWrites, store.writes)
            assertEquals(expectedBypass, session.skipRoleRewardJoinedRecognition)
            if (expectedBypass) {
                assertEquals(listOf("1002"), store.snapshot.joinedCharacters.map { it.characterId })
                assertEquals(listOf("1002"), session.state.value.joinedCharacters.map { it.characterId })
            } else if (expectedWrites == 0) {
                assertTrue(store.snapshot.joinedCharacters.isEmpty())
            }
        } finally {
            session.stop()
        }
        assertFalse(session.skipRoleRewardJoinedRecognition)
    }

    private fun field(session: LabyrinthEntryRecognitionSession, name: String) =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private class MemoryStore(private val fail: Boolean) : LabyrinthRunStateStore {
        var writes = 0
        var snapshot = LabyrinthRunSnapshot("test", null, "ACTIVE", null, 0, emptyList(), 0)
        override suspend fun begin(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun startNew(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun load(accountId: Long?) = snapshot
        override suspend fun recordPage(accountId: Long?, page: String, characters: List<LabyrinthCharacterMatch>,
            nowMillis: Long, relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean): LabyrinthRunSnapshot {
            writes++
            if (fail) error("test disk failure")
            snapshot = snapshot.copy(joinedCharacters = LabyrinthRosterMerger.merge(
                snapshot.joinedCharacters, characters, nowMillis,
            ))
            return snapshot
        }
    }
}
