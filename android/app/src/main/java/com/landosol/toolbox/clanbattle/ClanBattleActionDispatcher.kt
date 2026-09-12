package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind

data class ClanBattleActionCoordinates(
    val auto: ScreenPoint = ScreenPoint(1828f, 845f),
    val roles: Map<BattleSlot, ScreenPoint> = BattleSlot.entries.associateWith { slot ->
        ScreenPoint(480f + slot.ordinal * 240f, 845f)
    },
) {
    init {
        require(roles.keys == BattleSlot.entries.toSet())
    }
}

sealed interface ClanBattleActionPlanResult {
    data class Planned(val action: AutomationAction) : ClanBattleActionPlanResult
    data class Blocked(val reason: String) : ClanBattleActionPlanResult
    data class NoDeviceAction(val reason: String) : ClanBattleActionPlanResult
}

class ClanBattleActionPlanner(
    private val coordinates: ClanBattleActionCoordinates = ClanBattleActionCoordinates(),
) {
    fun plan(intent: ClanBattleActionIntent, observation: ClanBattleObservation): ClanBattleActionPlanResult {
        if (intent is ClanBattleActionIntent.Notify) return ClanBattleActionPlanResult.NoDeviceAction("notification-only")
        if (intent is ClanBattleActionIntent.Pause) return ClanBattleActionPlanResult.NoDeviceAction("pause-only")
        if (!observation.actionSafe) return ClanBattleActionPlanResult.Blocked("observation-not-action-safe")
        if (observation.screenKind != ClanBattleScreenKind.BATTLE) {
            return ClanBattleActionPlanResult.Blocked("battle-screen-not-confirmed")
        }
        if (observation.controls?.trustworthy != true) {
            return ClanBattleActionPlanResult.Blocked("controls-not-trustworthy")
        }
        val clockSafe = observation.filteredClock?.let { it.accepted || it.reason == "same-time" } == true
        if (!clockSafe) return ClanBattleActionPlanResult.Blocked("clock-not-safe")
        val viewport = observation.viewport ?: return ClanBattleActionPlanResult.Blocked("viewport-unavailable")
        val reference = when (intent) {
            is ClanBattleActionIntent.TapAuto -> coordinates.auto
            is ClanBattleActionIntent.TapRole -> coordinates.roles.getValue(intent.role)
            is ClanBattleActionIntent.Notify,
            is ClanBattleActionIntent.Pause,
            -> error("handled above")
        }
        val point = ScreenPoint(
            x = viewport.left + reference.x * viewport.scale,
            y = viewport.top + reference.y * viewport.scale,
        )
        if (point.x !in 0f..observation.frameSize.width.toFloat() ||
            point.y !in 0f..observation.frameSize.height.toFloat()
        ) {
            return ClanBattleActionPlanResult.Blocked("mapped-point-outside-frame")
        }
        return ClanBattleActionPlanResult.Planned(AutomationAction.Tap(point))
    }
}

sealed interface ClanBattleDispatchResult {
    data class Action(val result: AutomationActionResult) : ClanBattleDispatchResult
    data class Blocked(val reason: String) : ClanBattleDispatchResult
    data class NoDeviceAction(val reason: String) : ClanBattleDispatchResult
}

class ClanBattleActionDispatcher(
    private val executor: SessionBoundActionExecutor,
    private val planner: ClanBattleActionPlanner = ClanBattleActionPlanner(),
) {
    suspend fun dispatch(
        sessionId: AutomationSessionId,
        intent: ClanBattleActionIntent,
        observation: ClanBattleObservation,
    ): ClanBattleDispatchResult = when (val plan = planner.plan(intent, observation)) {
        is ClanBattleActionPlanResult.Blocked -> ClanBattleDispatchResult.Blocked(plan.reason)
        is ClanBattleActionPlanResult.NoDeviceAction -> ClanBattleDispatchResult.NoDeviceAction(plan.reason)
        is ClanBattleActionPlanResult.Planned -> ClanBattleDispatchResult.Action(
            executor.execute(sessionId, plan.action),
        )
    }
}
