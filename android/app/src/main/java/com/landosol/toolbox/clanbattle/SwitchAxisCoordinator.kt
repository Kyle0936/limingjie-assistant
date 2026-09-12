package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.clanbattle.axis.AxisToggleState
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.axis.BossDelayTrigger
import com.landosol.toolbox.clanbattle.axis.CharacterUbTrigger
import com.landosol.toolbox.clanbattle.axis.PauseFrameTrigger
import com.landosol.toolbox.clanbattle.axis.SwitchAxisNode
import com.landosol.toolbox.clanbattle.axis.SwitchAxisOpening
import com.landosol.toolbox.clanbattle.axis.SwitchControlTarget
import com.landosol.toolbox.clanbattle.axis.SwitchNodeTrigger
import com.landosol.toolbox.clanbattle.axis.TimedTrigger
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.VisualToggleState
import java.util.ArrayDeque

data class SwitchAxisFrame(
    val clockSeconds: Int?,
    val triggeredRoles: Set<BattleSlot>,
    val controlsTrustworthy: Boolean,
    val wallTimeMillis: Long,
    val bossUbEvent: SwitchBossUbEvent? = null,
)

data class SwitchBossUbEvent(
    val heldClockSeconds: Int,
    val detectedAtWallMillis: Long,
    val early: Boolean = false,
    val holdDurationMillis: Long? = null,
)

sealed interface SwitchRuntimeCommand {
    data object None : SwitchRuntimeCommand

    data class Converge(
        val nodeId: String,
        val target: SwitchControlTarget,
    ) : SwitchRuntimeCommand

    data class EnterPauseFrame(
        val nodeId: String,
        val role: BattleSlot,
    ) : SwitchRuntimeCommand
}

data class SwitchRuntimeSnapshot(
    val nodeId: String? = null,
    val sourceLine: Int? = null,
    val triggerType: String? = null,
    val runtimeState: String? = null,
    val eligibleWallMillis: Long? = null,
    val deadlineWallMillis: Long? = null,
)

/**
 * Pure switch-axis scheduler. It only decides when a node becomes eligible and never clicks.
 */
class SwitchAxisRuntime(
    private val opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>,
) {
    private sealed interface ActiveState {
        data object Armed : ActiveState
        data object PauseFrameEntered : ActiveState
        data object PauseFrameConfirmed : ActiveState
        data object Converging : ActiveState
    }

    private data class ActiveNode(
        val node: SwitchAxisNode,
        var state: ActiveState = ActiveState.Armed,
        val armedAtWallMillis: Long,
        var bossUbDetectedAtWallMillis: Long? = null,
    )

    private val remainingNodes = nodes.sortedBy(SwitchAxisNode::sourceLine).toMutableList()
    private val crossedNodes = ArrayDeque<SwitchAxisNode>()
    private var openingPending = opening != null
    private var openingConverging = false
    private var active: ActiveNode? = null

    fun update(frame: SwitchAxisFrame): SwitchRuntimeCommand {
        if (openingPending || openingConverging) return updateOpening(frame)

        enqueueCrossedNodes(frame.clockSeconds)
        var armedNow = false
        val current = active ?: crossedNodes.pollFirst()?.let { node ->
            ActiveNode(node, armedAtWallMillis = frame.wallTimeMillis).also {
                active = it
                armedNow = true
            }
        } ?: return SwitchRuntimeCommand.None
        return commandFor(current, frame, armedNow)
    }

    fun confirmPauseFrame(nodeId: String): Boolean {
        val current = active ?: return false
        if (current.node.id != nodeId || current.state != ActiveState.PauseFrameEntered) return false
        current.state = ActiveState.PauseFrameConfirmed
        return true
    }

    fun confirmConvergence(nodeId: String): Boolean {
        if (openingConverging && nodeId == OPENING_NODE_ID) {
            openingConverging = false
            openingPending = false
            return true
        }
        val current = active ?: return false
        if (current.node.id != nodeId || current.state != ActiveState.Converging) return false
        active = null
        return true
    }

    fun pendingNodeId(): String? = when {
        openingConverging -> OPENING_NODE_ID
        else -> active?.node?.id
    }

    fun snapshot(): SwitchRuntimeSnapshot {
        if (openingConverging) {
            return SwitchRuntimeSnapshot(
                nodeId = OPENING_NODE_ID,
                sourceLine = opening?.sourceLine,
                triggerType = "OPENING",
                runtimeState = "Converging",
            )
        }
        val current = active ?: return SwitchRuntimeSnapshot()
        val trigger = current.node.trigger
        return SwitchRuntimeSnapshot(
            nodeId = current.node.id,
            sourceLine = current.node.sourceLine,
            triggerType = trigger.typeName(),
            runtimeState = when (current.state) {
                ActiveState.Armed -> "Armed"
                ActiveState.PauseFrameEntered -> "PauseFrameEntered"
                ActiveState.PauseFrameConfirmed -> "PauseFrameConfirmed"
                ActiveState.Converging -> "Converging"
            },
            eligibleWallMillis = current.armedAtWallMillis,
            deadlineWallMillis = (trigger as? BossDelayTrigger)?.minimumDelayMs?.let {
                current.bossUbDetectedAtWallMillis?.plus(it)
            },
        )
    }

    private fun updateOpening(frame: SwitchAxisFrame): SwitchRuntimeCommand {
        val target = opening?.target ?: return SwitchRuntimeCommand.None
        if (openingConverging) return SwitchRuntimeCommand.Converge(OPENING_NODE_ID, target)
        if (frame.clockSeconds !in OPENING_WINDOW || !frame.controlsTrustworthy) {
            return SwitchRuntimeCommand.None
        }
        openingConverging = true
        return SwitchRuntimeCommand.Converge(OPENING_NODE_ID, target)
    }

    private fun enqueueCrossedNodes(clockSeconds: Int?) {
        if (clockSeconds == null) return
        val crossed = remainingNodes.filter { clockSeconds <= it.timeSeconds }
        if (crossed.isEmpty()) return
        crossed.forEach(crossedNodes::addLast)
        remainingNodes.removeAll(crossed.toSet())
    }

    private fun commandFor(
        active: ActiveNode,
        frame: SwitchAxisFrame,
        armedNow: Boolean,
    ): SwitchRuntimeCommand {
        if (active.state == ActiveState.Converging) {
            return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
        }
        return when (val trigger = active.node.trigger) {
            TimedTrigger -> convergeWhenTrustworthy(active, frame)
            is CharacterUbTrigger -> {
                val role = trigger.role
                if (!armedNow && role != null && role in frame.triggeredRoles) {
                    convergeWhenTrustworthy(active, frame)
                } else {
                    SwitchRuntimeCommand.None
                }
            }
            is BossDelayTrigger -> {
                if (active.bossUbDetectedAtWallMillis == null) {
                    frame.bossUbEvent
                        ?.takeIf {
                            it.isApplicableTo(active.node.timeSeconds) &&
                                (trigger.minimumDelayMs == null || trigger.minimumDelayMs == 0L || !it.early)
                        }
                        ?.let { active.bossUbDetectedAtWallMillis = it.detectedAtWallMillis }
                }
                val delayMs = trigger.minimumDelayMs ?: 0L
                if (
                    active.bossUbDetectedAtWallMillis != null &&
                    frame.wallTimeMillis - active.bossUbDetectedAtWallMillis!! >= delayMs
                ) {
                    convergeWhenTrustworthy(active, frame)
                } else {
                    SwitchRuntimeCommand.None
                }
            }
            is PauseFrameTrigger -> if (frame.controlsTrustworthy) {
                pauseFrameCommand(active, trigger)
            } else {
                SwitchRuntimeCommand.None
            }
            else -> SwitchRuntimeCommand.None
        }
    }

    private fun convergeWhenTrustworthy(
        active: ActiveNode,
        frame: SwitchAxisFrame,
    ): SwitchRuntimeCommand {
        if (!frame.controlsTrustworthy) return SwitchRuntimeCommand.None
        active.state = ActiveState.Converging
        return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
    }

    private fun pauseFrameCommand(
        active: ActiveNode,
        trigger: PauseFrameTrigger,
    ): SwitchRuntimeCommand {
        if (active.state == ActiveState.PauseFrameConfirmed) {
            active.state = ActiveState.Converging
            return SwitchRuntimeCommand.Converge(active.node.id, active.node.target)
        }
        if (active.state == ActiveState.PauseFrameEntered) return SwitchRuntimeCommand.None
        val role = trigger.role ?: return SwitchRuntimeCommand.None
        active.state = ActiveState.PauseFrameEntered
        return SwitchRuntimeCommand.EnterPauseFrame(active.node.id, role)
    }

    private fun SwitchNodeTrigger.typeName(): String = when (this) {
        TimedTrigger -> "TIMED"
        is CharacterUbTrigger -> "CHARACTER_UB"
        is BossDelayTrigger -> "BOSS_DELAY"
        is PauseFrameTrigger -> "PAUSE_FRAME"
        else -> "INVALID"
    }

    private fun SwitchBossUbEvent.isApplicableTo(nodeTimeSeconds: Int): Boolean =
        heldClockSeconds <= nodeTimeSeconds && nodeTimeSeconds - heldClockSeconds <= 2

    private companion object {
        const val OPENING_NODE_ID = "opening-1"
        val OPENING_WINDOW = 88..90
    }
}

data class SwitchAxisCoordinatorResult(
    val intents: List<ClanBattleActionIntent> = emptyList(),
    val activeNodeId: String? = null,
    val pauseFrameRole: BattleSlot? = null,
    val busy: Boolean = false,
    val runtime: SwitchRuntimeSnapshot = SwitchRuntimeSnapshot(),
    val reason: String = "idle",
)

/**
 * Converts switch-axis runtime commands into one-at-a-time, visually confirmed action intents.
 * It is deliberately independent from Android services and does not execute device actions.
 */
class SwitchAxisCoordinator(
    opening: SwitchAxisOpening?,
    nodes: List<SwitchAxisNode>,
) {
    private var runtime = SwitchAxisRuntime(opening, nodes)
    private var convergingNodeId: String? = null
    private var convergingTarget: SwitchControlTarget? = null
    private var pendingClick: PendingClick? = null
    private var failedConfirmationFrames = 0
    private var pauseFrame: PendingPauseFrame? = null

    fun update(
        observation: ClanBattleObservation,
        wallTimeMillis: Long,
        bossUbEvent: SwitchBossUbEvent? = null,
    ): SwitchAxisCoordinatorResult {
        val frame = observation.toSwitchAxisFrame(wallTimeMillis, bossUbEvent)
        pauseFrame?.let { pending ->
            if (!pending.confirmed) return result(pause = pending, reason = "pause-frame-awaiting-confirmation")
            if (!pending.roleClickIssued) {
                if (!observation.actionSafe) return result(pause = pending, reason = "pause-frame-screen-unsafe")
                pending.roleClickIssued = true
                return result(
                    pause = pending,
                    reason = "pause-frame-role-click",
                    intents = listOf(
                        ClanBattleActionIntent.TapRole(
                            pending.nodeId,
                            pending.role,
                            "switch-pause-frame-target-role",
                        ),
                    ),
                )
            }
            pauseFrame = null
        }

        pendingClick?.let { pending ->
            return confirmPendingClick(pending, observation)
        }

        convergingTarget?.let { target ->
            return convergeTarget(target, observation)
        }

        return when (val command = runtime.update(frame)) {
            SwitchRuntimeCommand.None -> result(reason = "waiting-runtime")
            is SwitchRuntimeCommand.Converge -> {
                convergingNodeId = command.nodeId
                convergingTarget = command.target
                convergeTarget(command.target, observation)
            }
            is SwitchRuntimeCommand.EnterPauseFrame -> {
                val pending = PendingPauseFrame(command.nodeId, command.role)
                pauseFrame = pending
                result(
                    pause = pending,
                    reason = "pause-frame-entered",
                    intents = listOf(ClanBattleActionIntent.Pause(command.nodeId, "switch-pause-frame")),
                )
            }
        }
    }

    fun confirmPauseFrame(nodeId: String): Boolean {
        val pending = pauseFrame ?: return false
        if (pending.nodeId != nodeId || pending.confirmed) return false
        if (!runtime.confirmPauseFrame(nodeId)) return false
        pending.confirmed = true
        return true
    }

    fun reset(opening: SwitchAxisOpening?, nodes: List<SwitchAxisNode>) {
        runtime = SwitchAxisRuntime(opening, nodes)
        convergingNodeId = null
        convergingTarget = null
        pendingClick = null
        failedConfirmationFrames = 0
        pauseFrame = null
    }

    fun snapshot(): SwitchRuntimeSnapshot = runtime.snapshot()

    private fun confirmPendingClick(
        pending: PendingClick,
        observation: ClanBattleObservation,
    ): SwitchAxisCoordinatorResult {
        val controls = observation.controls ?: return result(reason = "waiting-trustworthy-controls")
        if (!controls.trustworthy) return result(reason = "waiting-trustworthy-controls")
        if (pending.matches(observation)) {
            pending.matchingFrames++
            if (pending.matchingFrames >= CONFIRM_FRAMES) {
                pendingClick = null
                failedConfirmationFrames = 0
                return convergeTarget(convergingTarget ?: return result(reason = "missing-target"), observation)
            }
            return result(reason = "confirming-control-click")
        }
        pending.matchingFrames = 0
        failedConfirmationFrames++
        if (failedConfirmationFrames >= MAX_FAILED_CONFIRMATION_FRAMES) {
            return result(
                reason = "control-click-confirmation-failed",
                intents = listOf(ClanBattleActionIntent.Pause(convergingNodeId.orEmpty(), "control-click-confirmation-failed")),
            )
        }
        return result(reason = "waiting-control-click-confirmation")
    }

    private fun convergeTarget(
        target: SwitchControlTarget,
        observation: ClanBattleObservation,
    ): SwitchAxisCoordinatorResult {
        val controls = observation.controls ?: return result(reason = "waiting-trustworthy-controls")
        if (!controls.trustworthy) return result(reason = "waiting-trustworthy-controls")
        if (!observation.actionSafe) {
            return result(
                reason = "controls-not-action-safe",
                intents = listOf(ClanBattleActionIntent.Pause(convergingNodeId.orEmpty(), "controls-not-action-safe")),
            )
        }
        target.auto?.let { desired ->
            val actual = controls.auto.state
            if (actual != desired.toVisualState()) {
                val pending = PendingClick(
                    nodeId = convergingNodeId.orEmpty(),
                    action = ClanBattleActionIntent.TapAuto(convergingNodeId.orEmpty(), "switch-converge-auto"),
                    expected = desired.toVisualState(),
                )
                pendingClick = pending
                return result(pending = pending, reason = "converge-auto", intents = listOf(pending.action))
            }
        }
        BattleSlot.entries.firstOrNull { role ->
            controls.roleSets.getValue(role).state != target.roles[role]?.toVisualState()
        }?.let { role ->
            val expected = target.roles[role]?.toVisualState()
                ?: return result(reason = "incomplete-switch-target")
            val pending = PendingClick(
                nodeId = convergingNodeId.orEmpty(),
                action = ClanBattleActionIntent.TapRole(convergingNodeId.orEmpty(), role, "switch-converge-role-set"),
                expected = expected,
                role = role,
            )
            pendingClick = pending
            return result(pending = pending, reason = "converge-role", intents = listOf(pending.action))
        }
        val nodeId = convergingNodeId ?: return result(reason = "missing-node")
        runtime.confirmConvergence(nodeId)
        convergingNodeId = null
        convergingTarget = null
        return result(reason = "target-confirmed")
    }

    private fun result(
        pending: PendingClick? = null,
        pause: PendingPauseFrame? = pauseFrame,
        reason: String,
        intents: List<ClanBattleActionIntent> = emptyList(),
    ) = SwitchAxisCoordinatorResult(
        intents = intents,
        activeNodeId = convergingNodeId ?: pause?.nodeId ?: runtime.pendingNodeId(),
        pauseFrameRole = pause?.role,
        busy = convergingNodeId != null || pending != null || pause != null || runtime.pendingNodeId() != null,
        runtime = runtime.snapshot(),
        reason = reason,
    )

    private data class PendingPauseFrame(
        val nodeId: String,
        val role: BattleSlot,
        var confirmed: Boolean = false,
        var roleClickIssued: Boolean = false,
    )

    private data class PendingClick(
        val nodeId: String,
        val action: ClanBattleActionIntent,
        val expected: VisualToggleState,
        val role: BattleSlot? = null,
        var matchingFrames: Int = 0,
    ) {
        fun matches(observation: ClanBattleObservation): Boolean = when (action) {
            is ClanBattleActionIntent.TapAuto -> observation.controls?.auto?.state == expected
            is ClanBattleActionIntent.TapRole -> observation.controls?.roleSets?.get(role)?.state == expected
            else -> false
        }
    }

    private fun ClanBattleObservation.toSwitchAxisFrame(
        wallTimeMillis: Long,
        bossUbEvent: SwitchBossUbEvent?,
    ) = SwitchAxisFrame(
        clockSeconds = filteredClock?.timeSeconds,
        triggeredRoles = energy?.confirmedDrops.orEmpty(),
        controlsTrustworthy = actionSafe && controls?.trustworthy == true,
        wallTimeMillis = wallTimeMillis,
        bossUbEvent = bossUbEvent,
    )

    private fun AxisToggleState.toVisualState() = when (this) {
        AxisToggleState.ON -> VisualToggleState.ON
        AxisToggleState.OFF -> VisualToggleState.OFF
    }

    private companion object {
        const val CONFIRM_FRAMES = 2
        const val MAX_FAILED_CONFIRMATION_FRAMES = 3
    }
}
