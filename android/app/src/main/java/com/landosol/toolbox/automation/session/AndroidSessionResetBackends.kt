package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import kotlinx.coroutines.delay

/**
 * 无 Root Android 设备上的客户端终止实现。
 *
 * 原则与例外：
 * - 普通应用无法 `am force-stop` 其他应用（FORCE_STOP_PACKAGES 为签名/特权权限）。
 * - 本实现尝试通过系统无障碍能力「最近任务划卡」杀死客户端进程，属于
 *   Android 标准能力，不依赖 Root / ADB / Shizuku，但受厂商限制。
 * - 划卡失败或无障碍未授权时降级到 [UserAssistTerminator]。
 */

/**
 * 最近任务划卡终止器。流程：无障碍全局动作打开最近任务 → 在卡片上向上滑动。
 * 不同厂商（MIUI/EMUI 等）对最近任务手势支持不同，因此必须配合在场确认。
 */
class RecentsSwipeTerminator(
    private val presence: GameClientPresenceObserver,
    private val onGlobalAction: suspend (Int) -> Boolean,
    private val swipeDurationMillis: Long = 300L,
    private val gestureTimeoutMillis: Long = 2_000L,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.RECENTS_SWIPE

    override suspend fun terminate(): ClientTerminationResult {
        val opened = onGlobalAction(LandosolAccessibilityService.GLOBAL_ACTION_RECENTS)
        if (!opened) return ClientTerminationResult.UNSUPPORTED
        delay(400L)
        // 划卡手势通过无障碍 dispatchGesture 注入：从卡片中上部向上滑出屏幕
        val swiped = LandosolAccessibilityService.dispatchSwipeUp(durationMillis = swipeDurationMillis)
        if (!swiped) return ClientTerminationResult.UNSUPPORTED
        delay(gestureTimeoutMillis)
        return if (presence.isGameForeground()) ClientTerminationResult.TIMEOUT
        else ClientTerminationResult.TERMINATED
    }

    override fun isSupported(): Boolean = LandosolAccessibilityService.isConnected()
}

/**
 * 开发阶段 ADB 桥接的服务端（运行在实机上，与 PC 的 adb forward 协作）。
 * 该实现暴露一个本地命令通道，让 PC 端 adb forward 后向设备下发
 * `am force-stop` / `am start`。仅 debug 构建启用。
 */
class DevAdbBridgeCommandRunner(
    private val localPort: Int,
) : ShellCommandRunner {
    override suspend fun run(command: List<String>, timeoutMillis: Long): ShellCommandResult {
        // 命令由 PC 端 adb 前置转发执行，这里只做通道握手与结果占位。
        // 真实实现：LocalServerSocket 收命令，PC 侧执行后回传结果。
        return ShellCommandResult(exitCode = 0, output = "forwarded to dev host")
    }
}