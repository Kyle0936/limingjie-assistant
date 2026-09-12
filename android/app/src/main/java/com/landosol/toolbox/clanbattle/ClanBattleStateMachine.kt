package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationSession
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.SessionExecutionResult
import com.landosol.toolbox.clanbattle.axis.AxisActionType
import com.landosol.toolbox.clanbattle.axis.AxisDocument
import com.landosol.toolbox.clanbattle.axis.AxisEvent
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.axis.BossDelayTrigger
import com.landosol.toolbox.clanbattle.axis.CharacterUbTrigger
import com.landosol.toolbox.clanbattle.axis.ConflictingSwitchTrigger
import com.landosol.toolbox.clanbattle.axis.PauseFrameTrigger
import com.landosol.toolbox.clanbattle.axis.TimedTrigger
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind
import com.landosol.toolbox.clanbattle.recognition.VisualToggleState

enum class ClanBattleRuntimePhase {
    IDLE,
    WAITING_FOR_BATTLE,
    WAITING_FOR_OPENING_CLOCK,
    RUNNING,
    WAITING_FOR_ROLE_SET_ON,
    WAITING_FOR_ROLE_UB,
    WAITING_FOR_ROLE_SET_OFF,
    WAITING_FOR_AUTO_CONFIRM,
    PAUSED,
    STOPPED,
    COMPLETED,
}

data class ClanBattleRuntimeState(
    val phase: ClanBattleRuntimePhase = ClanBattleRuntimePhase.IDLE,
    val activeEventId: String? = null,
    val nextEventIndex: Int = 0,
    val nextActionIndex: Int = 0,
    val waitingRole: BattleSlot? = null,
    val pendingPauseFrameRole: BattleSlot? = null,
    val confirmationFrames: Int = 0,
    val reason: String? = null,
)

sealed interface ClanBattleActionIntent {
    val eventId: String

    data class TapRole(
        override val eventId: String,
        val role: BattleSlot,
        val purpose: String,
    ) : ClanBattleActionIntent

    data class TapAuto(
        override val eventId: String,
        val purpose: String,
    ) : ClanBattleActionIntent

    data class Notify(
        override val eventId: String,
        val message: String,
    ) : ClanBattleActionIntent

    data class Pause(
        override val eventId: String,
        val reason: String,
    ) : ClanBattleActionIntent
}

data class ClanBattleTickResult(
    val state: ClanBattleRuntimeState,
    val intents: List<ClanBattleActionIntent> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)

/**
 * Conservative axis runtime. It creates intents but does not click the device.
 * Control confirmations and real action execution are deliberately kept behind the next stage.
 */
class ClanBattleStateMachine(
    private val axis: AxisDocument,
    private val openingSeconds: Int = 90,
) {
    private var state = ClanBattleRuntimeState()
    private var sequenceEvents = axis.events
    private var waitingEvent: AxisEvent? = null
    private var pendingPauseFrameEvent: AxisEvent? = null
    private val confirmedPauseFrameEventIds = mutableSetOf<String>()
    private var phaseBeforePause = ClanBattleRuntimePhase.IDLE
    private var autoStateBeforeIntent: VisualToggleState? = null

    fun snapshot(): ClanBattleRuntimeState = state

    fun isManualPauseFramePending(): Boolean = pendingPauseFrameEvent != null

    fun start(): ClanBattleTickResult {
        require(state.phase == ClanBattleRuntimePhase.IDLE || state.phase == ClanBattleRuntimePhase.STOPPED) {
            "会战状态机已经启动"
        }
        sequenceEvents = axis.events.sortedByDescending(AxisEvent::timeSeconds)
        waitingEvent = null
        pendingPauseFrameEvent = null
        confirmedPauseFrameEventIds.clear()
        autoStateBeforeIntent = null
        phaseBeforePause = ClanBattleRuntimePhase.WAITING_FOR_BATTLE
        state = ClanBattleRuntimeState(phase = ClanBattleRuntimePhase.WAITING_FOR_BATTLE)
        return result("started")
    }

    fun pause(reason: String): ClanBattleTickResult {
        if (state.phase == ClanBattleRuntimePhase.STOPPED || state.phase == ClanBattleRuntimePhase.COMPLETED) {
            return result("pause-ignored")
        }
        phaseBeforePause = state.phase
        state = state.copy(phase = ClanBattleRuntimePhase.PAUSED, reason = reason)
        return result(reason, listOf(ClanBattleActionIntent.Pause(state.activeEventId.orEmpty(), reason)))
    }

    fun resume(): ClanBattleTickResult {
        require(state.phase == ClanBattleRuntimePhase.PAUSED) { "当前状态不是暂停" }
        if (pendingPauseFrameEvent != null) return result("pause-frame-confirmation-required")
        val nextPhase = if (phaseBeforePause == ClanBattleRuntimePhase.PAUSED) {
            ClanBattleRuntimePhase.RUNNING
        } else {
            phaseBeforePause
        }
        state = state.copy(phase = nextPhase, reason = null)
        return result("resumed")
    }

    /** Confirms that the user has completed the requested pause-frame intervention. */
    fun confirmManualPauseFrame(): ClanBattleTickResult {
        val event = pendingPauseFrameEvent
            ?: return result("pause-frame-confirmation-not-pending")
        if (state.phase != ClanBattleRuntimePhase.PAUSED) return result("pause-frame-confirmation-not-paused")
        pendingPauseFrameEvent = null
        confirmedPauseFrameEventIds += event.id
        state = state.copy(
            phase = ClanBattleRuntimePhase.RUNNING,
            activeEventId = event.id,
            pendingPauseFrameRole = null,
            confirmationFrames = 0,
            reason = null,
        )
        if (event.actions.isEmpty()) advanceEvent()
        return result("pause-frame-confirmed")
    }

    fun stop(reason: String = "stopped"): ClanBattleTickResult {
        waitingEvent = null
        pendingPauseFrameEvent = null
        confirmedPauseFrameEventIds.clear()
        state = state.copy(phase = ClanBattleRuntimePhase.STOPPED, reason = reason)
        return result(reason)
    }

    fun onObservation(observation: ClanBattleObservation): ClanBattleTickResult {
        when (state.phase) {
            ClanBattleRuntimePhase.IDLE,
            ClanBattleRuntimePhase.STOPPED,
            ClanBattleRuntimePhase.COMPLETED,
            -> return result("inactive")

            ClanBattleRuntimePhase.PAUSED -> return result("paused")
            else -> Unit
        }
        if (observation.screenKind == ClanBattleScreenKind.UNSUPPORTED_ORIENTATION) {
            return pause("portrait-frame")
        }
        if (observation.shouldPause) return pause(observation.diagnostics.firstOrNull() ?: "observation-unsafe")

        return when (state.phase) {
            ClanBattleRuntimePhase.WAITING_FOR_BATTLE -> {
                if (observation.screenKind == ClanBattleScreenKind.BATTLE) {
                    state = state.copy(phase = ClanBattleRuntimePhase.WAITING_FOR_OPENING_CLOCK, reason = null)
                    result("battle-detected")
                } else result("waiting-battle")
            }

            ClanBattleRuntimePhase.WAITING_FOR_OPENING_CLOCK -> {
                val seconds = observation.filteredClock?.timeSeconds
                if (seconds != null && seconds >= openingSeconds) {
                    state = state.copy(phase = ClanBattleRuntimePhase.RUNNING, reason = null)
                    result("opening-clock-confirmed")
                } else result("waiting-opening-clock")
            }

            ClanBattleRuntimePhase.RUNNING -> dispatchNext(observation)
            ClanBattleRuntimePhase.WAITING_FOR_ROLE_SET_ON -> confirmRoleSetOn(observation)
            ClanBattleRuntimePhase.WAITING_FOR_ROLE_UB -> finishRoleLifecycle(observation)
            ClanBattleRuntimePhase.WAITING_FOR_ROLE_SET_OFF -> confirmRoleSetOff(observation)
            ClanBattleRuntimePhase.WAITING_FOR_AUTO_CONFIRM -> confirmAuto(observation)
            else -> result("ignored")
        }
    }

    private fun dispatchNext(observation: ClanBattleObservation): ClanBattleTickResult {
        val clock = observation.filteredClock?.timeSeconds ?: return result("clock-unavailable")
        val event = sequenceEvents.getOrNull(state.nextEventIndex)
            ?: return complete()
        if (event.trigger is PauseFrameTrigger &&
            event.id !in confirmedPauseFrameEventIds &&
            clock <= event.timeSeconds
        ) {
            pendingPauseFrameEvent = event
            state = state.copy(
                activeEventId = event.id,
                pendingPauseFrameRole = event.trigger.role,
            )
            return pause("pause-frame-awaiting-manual-confirmation")
        }
        unsupportedTriggerReason(event, clock)?.let { return pause(it) }
        if (!isEligible(event, clock, observation)) return result("event-not-eligible")
        if (!observation.actionSafe) return pause("controls-not-safe")
        state = state.copy(activeEventId = event.id)
        val action = event.actions.getOrNull(state.nextActionIndex) ?: run {
            advanceEvent()
            return result("empty-event")
        }
        return when (action.type) {
            AxisActionType.CLICK_ROLE -> {
                val role = parseRole(action.role) ?: return pause("unknown-role:${action.role}")
                waitingEvent = event
                state = state.copy(
                    phase = ClanBattleRuntimePhase.WAITING_FOR_ROLE_SET_ON,
                    waitingRole = role,
                    confirmationFrames = 0,
                )
                result(
                    "role-set-on",
                    listOf(ClanBattleActionIntent.TapRole(event.id, role, "start-role-ub-lifecycle")),
                )
            }

            AxisActionType.CLICK_AUTO -> {
                autoStateBeforeIntent = observation.controls?.auto?.state
                state = state.copy(
                    phase = ClanBattleRuntimePhase.WAITING_FOR_AUTO_CONFIRM,
                    confirmationFrames = 0,
                )
                result("click-auto", listOf(ClanBattleActionIntent.TapAuto(event.id, "axis-click-auto")))
            }

            AxisActionType.NOTIFY -> {
                advanceAction(event)
                result("notify", listOf(ClanBattleActionIntent.Notify(event.id, action.message.orEmpty())))
            }

            AxisActionType.BOSS,
            AxisActionType.TOGGLE_AUTO,
            AxisActionType.SET_ROLES,
            -> pause("action-awaiting-confirmation:${action.type.name}")
        }
    }

    private fun finishRoleLifecycle(observation: ClanBattleObservation): ClanBattleTickResult {
        val event = waitingEvent ?: return pause("missing-role-event")
        val role = state.waitingRole ?: return pause("missing-role")
        if (role !in observation.energy?.confirmedDrops.orEmpty()) return result("waiting-role-ub")
        if (!observation.actionSafe) return pause("controls-not-safe-after-ub")
        state = state.copy(phase = ClanBattleRuntimePhase.WAITING_FOR_ROLE_SET_OFF, confirmationFrames = 0)
        return result(
            "role-set-off",
            listOf(ClanBattleActionIntent.TapRole(event.id, role, "finish-role-ub-lifecycle")),
        )
    }

    private fun confirmRoleSetOn(observation: ClanBattleObservation): ClanBattleTickResult {
        val role = state.waitingRole ?: return pause("missing-role")
        val actual = observation.controls?.roleSets?.get(role)?.state
        if (actual == VisualToggleState.ON) {
            state = state.copy(phase = ClanBattleRuntimePhase.WAITING_FOR_ROLE_UB, confirmationFrames = 0)
            return result("role-set-on-confirmed")
        }
        return waitForConfirmation("role-set-on-unconfirmed", observation)
    }

    private fun confirmRoleSetOff(observation: ClanBattleObservation): ClanBattleTickResult {
        val event = waitingEvent ?: return pause("missing-role-event")
        val role = state.waitingRole ?: return pause("missing-role")
        val actual = observation.controls?.roleSets?.get(role)?.state
        if (actual == VisualToggleState.OFF) {
            waitingEvent = null
            advanceAction(event)
            return result("role-set-off-confirmed")
        }
        return waitForConfirmation("role-set-off-unconfirmed", observation)
    }

    private fun confirmAuto(observation: ClanBattleObservation): ClanBattleTickResult {
        val event = sequenceEvents.getOrNull(state.nextEventIndex) ?: return complete()
        val actual = observation.controls?.auto?.state
        if (actual != null && actual != VisualToggleState.UNKNOWN && actual != autoStateBeforeIntent) {
            autoStateBeforeIntent = null
            advanceAction(event)
            return result("auto-change-confirmed")
        }
        return waitForConfirmation("auto-change-unconfirmed", observation)
    }

    private fun waitForConfirmation(reason: String, observation: ClanBattleObservation): ClanBattleTickResult {
        if (!observation.actionSafe) return pause("controls-not-safe-confirmation")
        val nextFrames = state.confirmationFrames + 1
        if (nextFrames >= MAX_CONFIRMATION_FRAMES) return pause(reason)
        state = state.copy(confirmationFrames = nextFrames)
        return result("waiting-$reason")
    }

    private fun isEligible(event: AxisEvent, clock: Int, observation: ClanBattleObservation): Boolean = when (val trigger = event.trigger) {
        TimedTrigger -> clock <= event.timeSeconds
        is CharacterUbTrigger -> trigger.role != null && trigger.role in observation.energy?.confirmedDrops.orEmpty() && clock <= event.timeSeconds
        is PauseFrameTrigger -> event.id in confirmedPauseFrameEventIds && clock <= event.timeSeconds
        else -> false
    }

    private fun unsupportedTriggerReason(event: AxisEvent, clock: Int): String? {
        if (clock > event.timeSeconds) return null
        return when (event.trigger) {
            is PauseFrameTrigger -> if (event.id !in confirmedPauseFrameEventIds) {
                "pause-frame-awaiting-manual-confirmation"
            } else {
                null
            }
            is BossDelayTrigger -> "boss-trigger-awaiting-detector"
            is ConflictingSwitchTrigger -> "conflicting-trigger"
            else -> null
        }
    }

    private fun advanceAction(event: AxisEvent) {
        if (state.nextActionIndex + 1 < event.actions.size) {
            state = state.copy(
                phase = ClanBattleRuntimePhase.RUNNING,
                activeEventId = null,
                waitingRole = null,
                pendingPauseFrameRole = null,
                nextActionIndex = state.nextActionIndex + 1,
                reason = null,
            )
        } else {
            advanceEvent()
        }
    }

    private fun advanceEvent() {
        state = state.copy(
            phase = ClanBattleRuntimePhase.RUNNING,
            activeEventId = null,
            waitingRole = null,
            pendingPauseFrameRole = null,
            confirmationFrames = 0,
            nextEventIndex = state.nextEventIndex + 1,
            nextActionIndex = 0,
            reason = null,
        )
    }

    private fun complete(): ClanBattleTickResult {
        state = state.copy(phase = ClanBattleRuntimePhase.COMPLETED, reason = null)
        return result("completed")
    }

    private fun parseRole(raw: String?): BattleSlot? = when (raw) {
        "角色1" -> BattleSlot.SLOT_1
        "角色2" -> BattleSlot.SLOT_2
        "角色3" -> BattleSlot.SLOT_3
        "角色4" -> BattleSlot.SLOT_4
        "角色5" -> BattleSlot.SLOT_5
        else -> null
    }

    private fun result(reason: String, intents: List<ClanBattleActionIntent> = emptyList()) =
        ClanBattleTickResult(state, intents, listOf(reason))

    private companion object {
        const val MAX_CONFIRMATION_FRAMES = 3
    }
}

data class ClanBattleSessionHandle(val session: AutomationSession)

sealed interface ClanBattleSessionStartResult {
    data class Started(val handle: ClanBattleSessionHandle) : ClanBattleSessionStartResult
    data class AlreadyRunning(val session: AutomationSession) : ClanBattleSessionStartResult
}

class ClanBattleSessionController(
    private val sessionManager: AutomationSessionManager,
) {
    private var machine: ClanBattleStateMachine? = null

    suspend fun start(axis: AxisDocument, dryRun: Boolean): ClanBattleSessionStartResult =
        when (val started = sessionManager.start(AutomationMode.CLAN_BATTLE, dryRun)) {
            is AutomationSessionStartResult.AlreadyRunning -> ClanBattleSessionStartResult.AlreadyRunning(started.session)
            is AutomationSessionStartResult.Started -> {
                machine = ClanBattleStateMachine(axis).also { it.start() }
                ClanBattleSessionStartResult.Started(ClanBattleSessionHandle(started.session))
            }
        }

    suspend fun pause(sessionId: AutomationSessionId, reason: String): Boolean {
        val changed = sessionManager.setPaused(sessionId, true)
        if (changed) machine?.pause(reason)
        return changed
    }

    suspend fun resume(sessionId: AutomationSessionId): Boolean {
        if (machine?.isManualPauseFramePending() == true) return false
        val changed = sessionManager.setPaused(sessionId, false)
        if (changed) machine?.resume()
        return changed
    }

    suspend fun confirmManualPauseFrame(
        sessionId: AutomationSessionId,
    ): SessionExecutionResult<ClanBattleTickResult> {
        val result = sessionManager.executeIfActiveAllowPaused(sessionId) {
            machine?.confirmManualPauseFrame() ?: ClanBattleTickResult(
                state = ClanBattleRuntimeState(ClanBattleRuntimePhase.STOPPED, reason = "missing-machine"),
                diagnostics = listOf("missing-machine"),
            )
        }
        if (result is SessionExecutionResult.Accepted && result.value.state.phase != ClanBattleRuntimePhase.PAUSED) {
            sessionManager.setPaused(sessionId, false)
        }
        return result
    }

    suspend fun stop(sessionId: AutomationSessionId): Boolean {
        val stopped = sessionManager.stop(sessionId)
        if (stopped) {
            machine?.stop()
            machine = null
        }
        return stopped
    }

    suspend fun onObservation(
        sessionId: AutomationSessionId,
        observation: ClanBattleObservation,
    ): SessionExecutionResult<ClanBattleTickResult> {
        val result = sessionManager.executeIfActive(sessionId) {
            machine?.onObservation(observation) ?: ClanBattleTickResult(
                state = ClanBattleRuntimeState(ClanBattleRuntimePhase.STOPPED, reason = "missing-machine"),
                diagnostics = listOf("missing-machine"),
            )
        }
        if (result is SessionExecutionResult.Accepted && result.value.state.phase == ClanBattleRuntimePhase.PAUSED) {
            sessionManager.setPaused(sessionId, true)
        }
        return result
    }
}
