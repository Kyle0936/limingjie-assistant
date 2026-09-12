package com.landosol.toolbox.automation

sealed interface AutomationAction {
    data class Tap(val point: ScreenPoint) : AutomationAction
    data class Swipe(
        val start: ScreenPoint,
        val end: ScreenPoint,
        val durationMillis: Long,
    ) : AutomationAction
    data object Back : AutomationAction
}

sealed interface AutomationBackendResult {
    data object Completed : AutomationBackendResult
    data class Rejected(val reason: String) : AutomationBackendResult
}

fun interface AutomationActionBackend {
    suspend fun execute(action: AutomationAction): AutomationBackendResult
}

sealed interface AutomationActionResult {
    data class Executed(val action: AutomationAction) : AutomationActionResult
    data class DryRun(val action: AutomationAction) : AutomationActionResult
    data class Rejected(val action: AutomationAction, val reason: String) : AutomationActionResult
    data object StaleSession : AutomationActionResult
    data object Paused : AutomationActionResult
}

class SessionBoundActionExecutor(
    private val sessionManager: AutomationSessionManager,
    private val backend: AutomationActionBackend,
) {
    suspend fun execute(
        sessionId: AutomationSessionId,
        action: AutomationAction,
    ): AutomationActionResult = when (
        val gated = sessionManager.executeIfActive(sessionId) { session ->
            if (session.dryRun) {
                AutomationActionResult.DryRun(action)
            } else {
                when (val result = backend.execute(action)) {
                    AutomationBackendResult.Completed -> AutomationActionResult.Executed(action)
                    is AutomationBackendResult.Rejected -> AutomationActionResult.Rejected(action, result.reason)
                }
            }
        }
    ) {
        is SessionExecutionResult.Accepted -> gated.value
        SessionExecutionResult.StaleSession -> AutomationActionResult.StaleSession
        SessionExecutionResult.Paused -> AutomationActionResult.Paused
    }
}
