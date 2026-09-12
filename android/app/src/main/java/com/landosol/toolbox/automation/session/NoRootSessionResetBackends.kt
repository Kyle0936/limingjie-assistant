package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.CoordinateMapper
import com.landosol.toolbox.automation.PixelRect
import com.landosol.toolbox.automation.ScreenPoint
import kotlinx.coroutines.delay

/**
 * 无 Root 环境下的客户端终止替代方案。
 *
 * 普通应用无法 force-stop 其他应用，因此“关闭客户端”必须借助游戏自身的
 * 退出通道，或请求用户协助。本模块不硬编码游戏流程：菜单入口、登出按钮等
 * 目标均由视觉锚点坐标（可配置）驱动，识别与决策分离。
 */

/**
 * 游戏内登出方案：模拟点击 菜单 → 设置 → 退出登录。
 * 每一步的目标坐标由调用方提供（来自视觉资源包/校准数据），不写死。
 */
data class GracefulLogoutStep(
    val label: String,
    val target: ScreenPoint,
    val expectPageToken: String? = null,
)

class GracefulLogoutTerminator(
    private val steps: List<GracefulLogoutStep>,
    private val onTap: suspend (AutomationAction) -> Unit,
    private val pauseBetweenStepsMillis: Long = 1_200L,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.GRACEFUL_LOGOUT

    override suspend fun terminate(): ClientTerminationResult {
        if (steps.isEmpty()) return ClientTerminationResult.UNSUPPORTED
        for (step in steps) {
            onTap(AutomationAction.Tap(step.target))
            if (pauseBetweenStepsMillis > 0) kotlinx.coroutines.delay(pauseBetweenStepsMillis)
        }
        return ClientTerminationResult.TERMINATED
    }

    override fun isSupported(): Boolean = steps.isNotEmpty()
}

/** 用户协助方案：暂停流程并提示用户手动关闭客户端 */
class UserAssistTerminator(
    private val onPromptUser: suspend (String) -> Boolean,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.USER_ASSIST

    override suspend fun terminate(): ClientTerminationResult {
        val accepted = onPromptUser("请手动关闭公主连结（划掉最近任务），完成后确认继续")
        return if (accepted) ClientTerminationResult.TERMINATED
        else ClientTerminationResult.REJECTED
    }

    override fun isSupported(): Boolean = true
}

/** 仅重启 Activity：不杀进程，通过全新任务栈启动客户端 */
class RelaunchOnlyTerminator : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.RELAUNCH_ONLY
    override suspend fun terminate(): ClientTerminationResult = ClientTerminationResult.ALREADY_GONE
    override fun isSupported(): Boolean = true
}

/**
 * 会话失效触发终止器：不杀进程，点击「冒险→黎明界」入口触发「会话失效」弹窗，
 * 命中「返回标题」按钮回到标题页。无 Root、无 ADB 依赖。
 *
 * 流程：
 *  1. 点击触发入口（点进冒险→黎明界，会话已失效时会弹出错误提示）
 *  2. 轮询识别「错误提示」弹窗出现（复用 [SessionBlockClassifier] 的锚点评分）
 *  3. 点击「返回标题」按钮
 *  4. 轮询标题页出现，作为完成条件
 *
 * 点击目标使用参考系坐标（默认 1920x1080，与识别模板一致），通过
 * [CoordinateMapper] 映射到实际屏幕；检测回调由上层注入。
 */
class SessionExpiryTerminator(
    private val onTap: suspend (ScreenPoint) -> Boolean,
    private val triggerEntryPoint: ScreenPoint,
    private val returnTitlePoint: ScreenPoint,
    private val popupVisible: suspend () -> Boolean,
    private val titleReached: suspend () -> Boolean,
    private val frameSize: () -> Pair<Int, Int>,
    private val coordinateMapper: CoordinateMapper = CoordinateMapper(),
    private val available: () -> Boolean = { true },
    private val popupTimeoutMillis: Long = 30_000L,
    private val titleTimeoutMillis: Long = 30_000L,
    private val frameReadyTimeoutMillis: Long = 5_000L,
    private val entryTapDelayMillis: Long = 2_000L,
    private val returnTapDelayMillis: Long = 1_500L,
    private val triggerAnchorPoint: () -> ScreenPoint? = { null },
    private val returnTitleAnchorPoint: () -> ScreenPoint? = { null },
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.SESSION_EXPIRY

    override suspend fun terminate(): ClientTerminationResult {
        // 等待首个有效识别帧（workflow 启动后首帧可能尚未到达）
        if (!await(frameReadyTimeoutMillis) {
                frameSize().let { (width, height) -> width > 0 && height > 0 }
            }
        ) {
            return ClientTerminationResult.UNSUPPORTED
        }
        val (width, height) = frameSize()
        val mapping = coordinateMapper.createMapping(
            PixelRect(0f, 0f, width.toFloat(), height.toFloat()),
        )
        val trigger = triggerAnchorPoint() ?: mapping.toScreen(triggerEntryPoint)
            ?: return ClientTerminationResult.UNSUPPORTED
        if (!onTap(trigger)) return ClientTerminationResult.UNSUPPORTED
        delay(entryTapDelayMillis)
        if (!await(popupTimeoutMillis) { popupVisible() }) {
            return ClientTerminationResult.TIMEOUT
        }
        val confirm = returnTitleAnchorPoint() ?: mapping.toScreen(returnTitlePoint)
            ?: return ClientTerminationResult.TIMEOUT
        if (!onTap(confirm)) return ClientTerminationResult.TIMEOUT
        delay(returnTapDelayMillis)
        if (!await(titleTimeoutMillis) { titleReached() }) {
            return ClientTerminationResult.TIMEOUT
        }
        return ClientTerminationResult.TERMINATED
    }

    override fun isSupported(): Boolean = available()

    private suspend fun await(timeoutMillis: Long, probe: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            delay(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 500L
    }
}

/** 不可用的空终止器（例如发布版本关闭了所有降级策略） */
class UnsupportedTerminator(
    override val kind: ClientTerminationKind,
) : GameClientTerminator {
    override suspend fun terminate(): ClientTerminationResult = ClientTerminationResult.UNSUPPORTED
    override fun isSupported(): Boolean = false
}

/**
 * 终止策略降级链：依次尝试候选策略，直到客户端确认不在场。
 * 每条策略只负责发出动作，确认由 [GameSessionResetWorkflow] 统一完成。
 */
class TerminatorFallbackChain(
    private val candidates: List<GameClientTerminator>,
) : GameClientTerminator {
    override val kind: ClientTerminationKind
        get() = candidates.firstOrNull { it.isSupported() }?.kind ?: ClientTerminationKind.USER_ASSIST

    override suspend fun terminate(): ClientTerminationResult {
        for (candidate in candidates) {
            if (!candidate.isSupported()) continue
            val result = candidate.terminate()
            if (result == ClientTerminationResult.TERMINATED || result == ClientTerminationResult.ALREADY_GONE) {
                return result
            }
            if (result == ClientTerminationResult.REJECTED) return result
        }
        return ClientTerminationResult.UNSUPPORTED
    }

    override fun isSupported(): Boolean = candidates.any { it.isSupported() }
}

/** 把“客户端是否已退出”作为决策输入的状态：关闭中 / 已退出 / 仍在运行 */
enum class ClientPresenceState {
    RUNNING,
    GONE,
    UNKNOWN,
}
