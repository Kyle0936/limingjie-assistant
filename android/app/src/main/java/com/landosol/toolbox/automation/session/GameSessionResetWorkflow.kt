package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationMode
import com.landosol.toolbox.automation.AutomationActionResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionStartResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureFrameRegistrationResult
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.overlay.AutomationOverlaySessionHandler
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionDecision
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class GameSessionResetStatus { IDLE, RUNNING, STOPPED, ERROR }

data class GameSessionResetState(
    val status: GameSessionResetStatus = GameSessionResetStatus.IDLE,
    val sessionId: AutomationSessionId? = null,
    val stage: GameSessionResetStage = GameSessionResetStage.PENDING,
    val frameCount: Long = 0,
    val actionCount: Int = 0,
    val lastBlock: SessionBlockKind = SessionBlockKind.NONE,
    val paused: Boolean = false,
    val message: String? = null,
) {
    val running: Boolean get() = status == GameSessionResetStatus.RUNNING
}

sealed interface GameSessionResetStartResult {
    data class Started(val sessionId: AutomationSessionId) : GameSessionResetStartResult
    data class AlreadyRunning(val sessionId: AutomationSessionId) : GameSessionResetStartResult
    data class Blocked(val reason: String) : GameSessionResetStartResult
}

/**
 * 会话重置编排：保存本局结果 → 关闭客户端 → 重启 → 等登录 → 主页 → 冒险 →
 * 黎明界 → 就绪下一轮。复用共享自动化会话仲裁、截图帧总线与悬浮窗。
 */
class GameSessionResetWorkflow(
    private val sessionManager: AutomationSessionManager,
    private val backend: GameSessionResetBackend,
    private val captureActive: () -> Boolean = CaptureStateRegistry::isActive,
    private val processorFactory: () -> (CapturedFrame) -> LabyrinthEntryFrameResult,
    private val blockClassifier: (LabyrinthEntryFrameResult) -> SessionBlockKind,
    private val overlayCoordinator: AutomationOverlayCoordinator = AutomationOverlayCoordinator(),
    private val onSaveRoundResult: suspend () -> Unit = {},
    private val onNextRoundReady: suspend (AutomationSessionId) -> Unit = {},
    private val onBlocked: suspend (AutomationSessionId, SessionBlockKind) -> Unit = { _, _ -> },
    private val presence: GameClientPresenceObserver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val actionExecutor: SessionBoundActionExecutor? = null,
    private val entryActionPlannerFactory: () -> LabyrinthEntryActionPlanner = { LabyrinthEntryActionPlanner() },
    private val totalTimeoutMillis: Long = DEFAULT_TOTAL_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = AtomicBoolean(false)
    private val _state = MutableStateFlow(GameSessionResetState())
    val state: StateFlow<GameSessionResetState> = _state.asStateFlow()

    @Volatile
    private var activeSessionId: AutomationSessionId? = null
    private var lease: com.landosol.toolbox.automation.capture.CaptureFrameConsumerLease? = null
    private var stage: GameSessionResetStage = GameSessionResetStage.PENDING
    private var stableStageFrames = 0
    private var lastStageChangeAt = Long.MIN_VALUE

    init {
        require(totalTimeoutMillis > 0)
    }

    suspend fun start(): GameSessionResetStartResult = mutex.withLock {
        startLocked(onSaveRoundResult)
    }

    private suspend fun startLocked(
        saveRoundResult: suspend () -> Unit,
    ): GameSessionResetStartResult {
        activeSessionId?.let { return GameSessionResetStartResult.AlreadyRunning(it) }
        if (!captureActive()) {
            val reason = "请先在权限中心开启屏幕捕获"
            _state.value = _state.value.copy(status = GameSessionResetStatus.ERROR, message = reason)
            return GameSessionResetStartResult.Blocked(reason)
        }
        if (!backend.isSupported()) {
            val reason = "当前设备没有可用的客户端终止策略"
            _state.value = _state.value.copy(status = GameSessionResetStatus.ERROR, message = reason)
            return GameSessionResetStartResult.Blocked(reason)
        }
        val session = when (val started = sessionManager.start(AutomationMode.SESSION_RESET, dryRun = false)) {
            is AutomationSessionStartResult.AlreadyRunning -> {
                val reason = "已有${started.session.mode.name}任务运行"
                _state.value = _state.value.copy(status = GameSessionResetStatus.ERROR, message = reason)
                return GameSessionResetStartResult.Blocked(reason)
            }
            is AutomationSessionStartResult.Started -> started.session
        }
        val processor = runCatching(processorFactory).getOrElse { error ->
            sessionManager.stop(session.id)
            val reason = "加载入口识别模板失败：${error.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = GameSessionResetStatus.ERROR, message = reason)
            return GameSessionResetStartResult.Blocked(reason)
        }
        val entryPlanner = runCatching(entryActionPlannerFactory).getOrElse { error ->
            sessionManager.stop(session.id)
            val reason = "初始化入口动作规划器失败：${error.message ?: "未知错误"}"
            _state.value = _state.value.copy(status = GameSessionResetStatus.ERROR, message = reason)
            return GameSessionResetStartResult.Blocked(reason)
        }.also { planner ->
            planner.start(clock())
        }
        val registration = CaptureFrameBus.register(OWNER) { frame ->
            onFrame(session.id, processor, entryPlanner, frame)
        }
        if (registration is CaptureFrameRegistrationResult.Busy) {
            sessionManager.stop(session.id)
            val reason = "截图帧正在由${registration.owner}使用"
            _state.value = _state.value.copy(status = GameSessionResetStatus.ERROR, message = reason)
            return GameSessionResetStartResult.Blocked(reason)
        }
        lease = (registration as CaptureFrameRegistrationResult.Registered).lease
        activeSessionId = session.id
        inFlight.set(false)
        stage = GameSessionResetStage.PENDING
        stableStageFrames = 0
        lastStageChangeAt = clock()
        _state.value = GameSessionResetState(
            status = GameSessionResetStatus.RUNNING,
            sessionId = session.id,
            stage = stage,
            message = "会话重置已启动",
        )
        val attached = overlayCoordinator.attach(
            presentation = buildOverlayPresentation(session.id),
            sessionHandler = object : AutomationOverlaySessionHandler {
                override suspend fun setPaused(paused: Boolean): Boolean =
                    this@GameSessionResetWorkflow.setPaused(paused)

                override suspend fun stop(): Boolean =
                    this@GameSessionResetWorkflow.stop("通知栏停止")
            },
        )
        if (!attached) {
            lease?.let(CaptureFrameBus::unregister)
            lease = null
            activeSessionId = null
            sessionManager.stop(session.id)
            val reason = "任务控制通知不可用或已有任务占用，请检查通知权限与频道"
            _state.value = _state.value.copy(
                status = GameSessionResetStatus.ERROR,
                sessionId = null,
                message = reason,
            )
            return GameSessionResetStartResult.Blocked(reason)
        }
        actionScope.launch {
            runResetFlow(
                sessionId = session.id,
                saveRoundResult = saveRoundResult,
            )
        }
        return GameSessionResetStartResult.Started(session.id)
    }

    suspend fun setPaused(paused: Boolean): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        if (!sessionManager.setPaused(sessionId, paused)) return false
        _state.value = _state.value.copy(
            paused = paused,
            message = if (paused) "会话重置已暂停" else "会话重置已继续",
        )
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
        true
    }

    suspend fun stop(reason: String = "用户停止会话重置"): Boolean = mutex.withLock {
        val sessionId = activeSessionId ?: return false
        activeSessionId = null
        inFlight.set(false)
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        val stopped = sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        _state.value = _state.value.copy(
            status = GameSessionResetStatus.STOPPED,
            sessionId = null,
            message = reason,
        )
        stopped
    }

    /** 供外部在自动刷完成后触发下一轮前的重置 */
    suspend fun runOnceAfterRound(
        saveResult: suspend () -> Unit = onSaveRoundResult,
    ): Boolean = mutex.withLock {
        when (startLocked(saveResult)) {
            is GameSessionResetStartResult.Started -> true
            is GameSessionResetStartResult.AlreadyRunning -> false
            is GameSessionResetStartResult.Blocked -> false
        }
    }

    private suspend fun runResetFlow(
        sessionId: AutomationSessionId,
        saveRoundResult: suspend () -> Unit,
    ) {
        val outcome = runCatching {
            withTimeout(totalTimeoutMillis) {
                // 1. 保存本局结果。runOnceAfterRound 只把回调交给本流程，不在外层重复调用。
                setMessage("保存本局结果")
                saveRoundResult()

                // 2. 关闭/退出客户端（ADB force-stop 或会话失效触发弹窗返回标题）
                val terminationLabel = when (backend.kind) {
                    ClientTerminationKind.SESSION_EXPIRY -> "触发会话失效并返回标题页"
                    else -> "关闭/退出公主连结客户端"
                }
                transitionTo(sessionId, GameSessionResetStage.TERMINATING, terminationLabel)
                val termination = backend.terminateClient()
                if (
                    termination != ClientTerminationResult.TERMINATED &&
                    termination != ClientTerminationResult.ALREADY_GONE
                ) {
                    error("终止客户端未确认完成：$termination")
                }

                // 3. 会话失效触发已回到标题页，无需重启；其余策略重新启动客户端
                if (backend.kind != ClientTerminationKind.SESSION_EXPIRY) {
                    transitionTo(sessionId, GameSessionResetStage.RELAUNCHING, "重新启动公主连结")
                    val relaunch = backend.relaunchClient()
                    if (relaunch != GameClientRelaunchResult.LAUNCH_REQUESTED) {
                        error("重新启动客户端失败：$relaunch")
                    }
                }

                // 4-7. 入口规划器根据识别帧实际点击：标题 → 公告 → 主页 → 冒险 → 黎明界。
                transitionTo(sessionId, GameSessionResetStage.WAITING_LOGIN, "等待登录完成")
                awaitStageReached(sessionId, GameSessionResetStage.READY_FOR_NEXT_ROUND)
            }
        }

        if (outcome.isSuccess) {
            runCatching { onNextRoundReady(sessionId) }
                .onFailure { callbackError ->
                    finishFlow(
                        sessionId = sessionId,
                        status = GameSessionResetStatus.ERROR,
                        terminalStage = GameSessionResetStage.FAILED,
                        message = "下一轮回调失败：${callbackError.message ?: "未知错误"}",
                    )
                    return
                }
            finishFlow(
                sessionId = sessionId,
                status = GameSessionResetStatus.STOPPED,
                terminalStage = GameSessionResetStage.READY_FOR_NEXT_ROUND,
                message = "会话重置完成，可以开始下一轮",
            )
        } else {
            val error = outcome.exceptionOrNull()
            finishFlow(
                sessionId = sessionId,
                status = GameSessionResetStatus.ERROR,
                terminalStage = GameSessionResetStage.FAILED,
                message = "会话重置失败：${error?.message ?: "未知错误"}",
            )
        }
    }

    private suspend fun awaitStageReached(
        sessionId: AutomationSessionId,
        target: GameSessionResetStage,
    ) {
        // 识别帧回调驱动推进；此处等待目标阶段被 onFrame 转换到达
        while (activeSessionId == sessionId && stage != target) {
            if (stage == GameSessionResetStage.FAILED) error("会话重置失败")
            if (stage == GameSessionResetStage.SESSION_BLOCKED) {
                onBlocked(sessionId, _state.value.lastBlock)
                error("会话被阻塞：${_state.value.lastBlock}")
            }
            kotlinx.coroutines.delay(FRAME_POLL_MILLIS)
        }
        if (activeSessionId != sessionId) error("会话重置已被停止")
        if (stage != target) error("会话重置未到达目标阶段：$target")
    }

    private fun onFrame(
        sessionId: AutomationSessionId,
        processor: (CapturedFrame) -> LabyrinthEntryFrameResult,
        entryPlanner: LabyrinthEntryActionPlanner,
        frame: CapturedFrame,
    ) {
        if (activeSessionId != sessionId || _state.value.paused) {
            recycle(frame)
            return
        }
        if (inFlight.get()) {
            recycle(frame)
            return
        }
        runCatching { processor(frame) }
            .onSuccess { result ->
                if (activeSessionId != sessionId) return@onSuccess
                val block = blockClassifier(result)
                val current = _state.value
                _state.value = current.copy(
                    frameCount = current.frameCount + 1,
                    lastBlock = block,
                )
                handleObservation(
                    sessionId = sessionId,
                    result = result,
                    block = block,
                    frameWidth = frame.bitmap.width,
                    frameHeight = frame.bitmap.height,
                    entryPlanner = entryPlanner,
                )
            }
            .onFailure { error ->
                if (activeSessionId == sessionId) {
                    _state.value = _state.value.copy(message = "识别失败：${error.message ?: "未知错误"}")
                }
            }
            .also { recycle(frame) }
    }

    private fun handleObservation(
        sessionId: AutomationSessionId,
        result: LabyrinthEntryFrameResult,
        block: SessionBlockKind,
        frameWidth: Int,
        frameHeight: Int,
        entryPlanner: LabyrinthEntryActionPlanner,
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        actionScope.launch {
            try {
                val expectedExpiryPopup =
                    stage == GameSessionResetStage.TERMINATING &&
                        backend.kind == ClientTerminationKind.SESSION_EXPIRY
                if (block != SessionBlockKind.NONE && !expectedExpiryPopup) {
                    stage = GameSessionResetStage.SESSION_BLOCKED
                    _state.value = _state.value.copy(
                        stage = GameSessionResetStage.SESSION_BLOCKED,
                        message = "会话被阻塞：$block",
                    )
                    onBlocked(sessionId, block)
                    return@launch
                }
                if (!presence.isGameForeground()) {
                    _state.value = _state.value.copy(message = "等待公主连结回到前台")
                    return@launch
                }

                val pageState = result.observation.state
                if (
                    pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE ||
                    pageState == LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE ||
                    pageState == LabyrinthEntryPageState.NODE_SELECTION
                ) {
                    markReady(sessionId, "已回到黎明界，可以开始下一轮")
                    return@launch
                }
                if (
                    pageState == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION ||
                    pageState == LabyrinthEntryPageState.CHARACTER_JOINED
                ) {
                    markFailed(sessionId, "会话重置意外进入开局角色流程，已停止避免误操作")
                    return@launch
                }

                updateNavigationStage(pageState)
                when (
                    val decision = entryPlanner.decide(
                        state = pageState,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        nowMillis = clock(),
                        anchorScores = result.observation.anchorScores,
                        anchorMatches = result.anchorMatches,
                    )
                ) {
                    is LabyrinthEntryActionDecision.Execute -> {
                        val executor = actionExecutor
                            ?: run {
                                markFailed(sessionId, "入口动作执行器未配置")
                                return@launch
                            }
                        when (val executed = executor.execute(sessionId, decision.action)) {
                            is AutomationActionResult.Executed,
                            is AutomationActionResult.DryRun,
                            -> {
                                val current = _state.value
                                _state.value = current.copy(
                                    actionCount = current.actionCount + 1,
                                    message = decision.label,
                                )
                                overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
                            }
                            is AutomationActionResult.Rejected ->
                                markFailed(sessionId, "动作被拒绝：${executed.reason}")
                            AutomationActionResult.StaleSession ->
                                markFailed(sessionId, "动作会话已失效")
                            AutomationActionResult.Paused ->
                                _state.value = _state.value.copy(message = "会话重置已暂停")
                        }
                    }
                    is LabyrinthEntryActionDecision.Wait -> {
                        stableStageFrames++
                        _state.value = _state.value.copy(message = decision.reason)
                    }
                    is LabyrinthEntryActionDecision.Complete ->
                        markReady(sessionId, decision.reason)
                    is LabyrinthEntryActionDecision.Stop ->
                        markFailed(sessionId, decision.reason)
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun updateNavigationStage(pageState: LabyrinthEntryPageState) {
        val next = when (pageState) {
            LabyrinthEntryPageState.HOME -> GameSessionResetStage.NAVIGATING_HOME
            LabyrinthEntryPageState.ADVENTURE -> GameSessionResetStage.NAVIGATING_ADVENTURE
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE,
            -> GameSessionResetStage.NAVIGATING_DAWN_REALM
            else -> GameSessionResetStage.WAITING_LOGIN
        }
        if (stage != next) {
            stage = next
            stableStageFrames = 0
            lastStageChangeAt = clock()
            _state.value = _state.value.copy(stage = next)
        }
    }

    private fun markReady(sessionId: AutomationSessionId, message: String) {
        stage = GameSessionResetStage.READY_FOR_NEXT_ROUND
        _state.value = _state.value.copy(
            stage = GameSessionResetStage.READY_FOR_NEXT_ROUND,
            message = message,
        )
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun markFailed(sessionId: AutomationSessionId, message: String) {
        stage = GameSessionResetStage.FAILED
        _state.value = _state.value.copy(
            stage = GameSessionResetStage.FAILED,
            message = message,
        )
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private suspend fun transitionTo(
        sessionId: AutomationSessionId,
        stage: GameSessionResetStage,
        message: String,
    ) {
        this.stage = stage
        stableStageFrames = 0
        lastStageChangeAt = clock()
        _state.value = _state.value.copy(stage = stage, message = message)
        overlayCoordinator.update(sessionId, buildOverlayPresentation(sessionId))
    }

    private fun setMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    private suspend fun finishFlow(
        sessionId: AutomationSessionId,
        status: GameSessionResetStatus,
        terminalStage: GameSessionResetStage,
        message: String,
    ) = mutex.withLock {
        if (activeSessionId != sessionId) return@withLock
        activeSessionId = null
        inFlight.set(false)
        lease?.let(CaptureFrameBus::unregister)
        lease = null
        sessionManager.stop(sessionId)
        overlayCoordinator.detach(sessionId)
        stage = terminalStage
        _state.value = _state.value.copy(
            status = status,
            sessionId = null,
            stage = terminalStage,
            message = message,
        )
    }

    private fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation {
        val current = _state.value
        return AutomationOverlayPresentation(
            sessionId = sessionId,
            title = "游戏会话重置",
            status = when (current.paused) {
                true -> "已暂停"
                else -> "运行中"
            },
            detail = buildList {
                add("阶段：${current.stage}")
                add("帧：${current.frameCount}")
                add("动作：${current.actionCount}")
                current.message?.let { add(it) }
            }.joinToString("\n"),
            dryRun = false,
            paused = current.paused,
        )
    }

    private fun recycle(frame: CapturedFrame) {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }

    private companion object {
        const val OWNER = "game-session-reset"
        const val FRAME_POLL_MILLIS = 250L
        const val DEFAULT_TOTAL_TIMEOUT_MILLIS = 180_000L
    }
}
