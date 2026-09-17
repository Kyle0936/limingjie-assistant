package com.landosol.toolbox

import android.app.Application
import android.content.Intent
import android.util.Log
import com.landosol.toolbox.account.AccountRepository
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.AndroidAccessibilityActionBackend
import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.capture.MediaProjectionCaptureService
import com.landosol.toolbox.automation.session.AccessibilityForegroundPresenceObserver
import com.landosol.toolbox.automation.session.CompositeSessionResetBackend
import com.landosol.toolbox.automation.session.GameClientRelaunchResult
import com.landosol.toolbox.automation.session.GameSessionResetStartResult
import com.landosol.toolbox.automation.session.GameSessionResetStatus
import com.landosol.toolbox.automation.session.GameSessionResetWorkflow
import com.landosol.toolbox.automation.session.Relauncher
import com.landosol.toolbox.automation.session.SessionBlockClassifier
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.automation.session.SessionExpiryFrameTracker
import com.landosol.toolbox.automation.session.SessionExpiryTerminator
import com.landosol.toolbox.automation.session.toSessionBlockScores
import com.landosol.toolbox.automation.capture.CaptureState
import com.landosol.toolbox.clanbattle.ClanBattleRecognitionSession
import com.landosol.toolbox.automation.overlay.AndroidAutomationNotificationHost
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlanner
import com.landosol.toolbox.labyrinth.LabyrinthEntryActionPlannerConfig
import com.landosol.toolbox.labyrinth.AndroidLabyrinthRoleDecisionDataLoader
import com.landosol.toolbox.labyrinth.AndroidLabyrinthCnDatabaseRepository
import com.landosol.toolbox.labyrinth.LabyrinthCnDatabaseUpdateResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataResult
import com.landosol.toolbox.labyrinth.RoomLabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.RoomLabyrinthRouteStore
import com.landosol.toolbox.labyrinth.node.AndroidLabyrinthNodeTemplateLoader
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthEntryFrameProcessor
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.protocol.bilibili.BilibiliLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.BilibiliGameGatewayFactory
import com.landosol.toolbox.protocol.bilibili.BilibiliNativeLoginCoordinator
import com.landosol.toolbox.protocol.bilibili.BilibiliSdkGatewayFactory
import com.landosol.toolbox.protocol.bilibili.InMemoryGameSessionRegistry
import com.landosol.toolbox.security.AndroidKeystoreCredentialStore
import com.landosol.toolbox.security.AndroidKeystoreSdkSessionStore
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSession
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStartResult
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunConfig
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunWorkflow
import com.landosol.toolbox.labyrinth.LabyrinthAutoRunRoundOutcome
import com.landosol.toolbox.labyrinth.LabyrinthExecutionGateResult
import com.landosol.toolbox.labyrinth.validateLabyrinthExecution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LandosolToolboxApplication : Application() {
    private val databaseUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The dashboard is loopback-only, so it is safe to start for test/release APKs too.
        // Starting it here also makes http://127.0.0.1:8765/ immediately reachable from a
        // browser running inside the emulator, before the first recognition session starts.
        labyrinthDebugDashboard
        databaseUpdateScope.launch {
            when (val result = labyrinthCnDatabaseRepository.updateIfNeeded()) {
                is LabyrinthCnDatabaseUpdateResult.UpToDate ->
                    Log.i(DATABASE_UPDATE_LOG_TAG, "CN database is current: ${result.metadata.version}")
                is LabyrinthCnDatabaseUpdateResult.Updated ->
                    Log.i(DATABASE_UPDATE_LOG_TAG, "CN database updated: ${result.metadata.version}")
                is LabyrinthCnDatabaseUpdateResult.Failed ->
                    Log.w(DATABASE_UPDATE_LOG_TAG, result.reason)
            }
        }
    }

    val automationSessionManager by lazy { AutomationSessionManager() }
    val automationOverlayCoordinator by lazy {
        AutomationOverlayCoordinator(AndroidAutomationNotificationHost(this))
    }
    private val accessibilityActionBackend by lazy { AndroidAccessibilityActionBackend() }
    val automationActionExecutor by lazy {
        SessionBoundActionExecutor(automationSessionManager, accessibilityActionBackend)
    }
    val clanBattleRecognitionSession by lazy {
        ClanBattleRecognitionSession(automationSessionManager, overlayCoordinator = automationOverlayCoordinator)
    }
    private val labyrinthRoleDecisionData by lazy {
        AndroidLabyrinthRoleDecisionDataLoader(this).load()
    }
    val labyrinthStrategySettings by lazy {
        com.landosol.toolbox.labyrinth.AndroidLabyrinthStrategySettingsStore(this)
    }
    suspend fun loadLabyrinthRoleRatings(): List<com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val data = labyrinthRoleDecisionData
            val document = (data as? LabyrinthRoleDecisionDataResult.Ready)?.runtime?.document
                ?: error((data as? LabyrinthRoleDecisionDataResult.Unavailable)?.reason ?: "角色资料不可用")
            val icons = try {
                assets.open("resource-packs/cn-bilibili/icons.json").bufferedReader().use {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<com.landosol.toolbox.gamedata.GameIconPackDocument>(it.readText())
                }
            } catch (_: Exception) { null }
            com.landosol.toolbox.labyrinth.labyrinthRoleRatingCatalog(document, icons)
        }
    val labyrinthCnDatabaseRepository by lazy {
        AndroidLabyrinthCnDatabaseRepository(this)
    }
    private val labyrinthDebugDashboard by lazy {
        LabyrinthDebugDashboardServer(
            frameArchiveDirectory = java.io.File(cacheDir, "labyrinth-frame-archive"),
        ).also { server ->
            server.start()
        }
    }
    val labyrinthEntryRecognitionSession: LabyrinthEntryRecognitionSession by lazy {
        // Start the debug endpoint with its initial waiting snapshot instead of waiting for the
        // first captured frame. That keeps first-frame capture failures observable in the browser.
        val debugDashboard = labyrinthDebugDashboard
        val roleDecisionRuntime = (labyrinthRoleDecisionData as? LabyrinthRoleDecisionDataResult.Ready)?.runtime
        val roleDecisionUnavailableReason =
            (labyrinthRoleDecisionData as? LabyrinthRoleDecisionDataResult.Unavailable)?.reason
        val characterAttributes = roleDecisionRuntime?.profiles
            ?.mapValues { (_, profile) -> profile.attribute }
            .orEmpty()
        LabyrinthEntryRecognitionSession(
            strategyProvider = {
                val settings = labyrinthStrategySettings.state.value
                com.landosol.toolbox.labyrinth.LabyrinthStrategySnapshot(
                    settings,
                    roleDecisionRuntime?.document?.let { document ->
                        com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataParser.createRuntime(
                            settings.applyTo(document), settings,
                        )
                    },
                )
            },
            rerollRequester = { accountId ->
                // A batch-owned run does not reroll from inside the run: the batch controller
                // receives FailedMaxRetry and performs the reroll itself.
                if (labyrinthBatchController.state.value?.stage ==
                    com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RUNNING_LABYRINTH
                ) {
                    true
                } else {
                    labyrinthController.startAfterBattleFailureReroll(accountId)
                }
            },
            runTerminalListener = { event -> labyrinthBatchController.onRunTerminal(event) },
            sessionManager = automationSessionManager,
            processorFactory = { finalBossOnly ->
                AndroidLabyrinthEntryFrameProcessor.create(
                    context = this,
                    characterAttributes = characterAttributes,
                    finalBossOnly = finalBossOnly,
                    skipJoinedCharacters = { labyrinthEntryRecognitionSession.skipRoleRewardJoinedRecognition },
                    nodeSearchHint = { labyrinthEntryRecognitionSession.currentNodeSearchHint() },
                    nodeScanRequested = { labyrinthEntryRecognitionSession.nodeScanRequested() },
                )::process
            },
            captureStop = { MediaProjectionCaptureService.stop(this) },
            overlayCoordinator = automationOverlayCoordinator,
            actionExecutor = automationActionExecutor,
            actionsAvailable = LandosolAccessibilityService::isConnected,
            actionTargetReady = {
                LandosolAccessibilityService.foregroundPackage() == GAME_PACKAGE_NAME
            },
            actionPlannerFactory = {
                LabyrinthEntryActionPlanner(
                    LabyrinthEntryActionPlannerConfig(
                        manualCharacterSelection = false,
                        requireConfiguredOpeningRoster = true,
                    ),
                )
            },
            roleRewardChoicePlanner = roleDecisionRuntime?.rewardChoicePlanner,
            roleRewardChoiceUnavailableReason = roleDecisionUnavailableReason,
            eventChoicePlanner = roleDecisionRuntime?.eventChoicePlanner,
            battleTeamRecommendationPlanner = roleDecisionRuntime?.battleTeamRecommendationPlanner,
            battleTeamRecommendationUnavailableReason = roleDecisionUnavailableReason,
            battleTeamSelectionPlanner = roleDecisionRuntime?.battleTeamSelectionPlanner,
            runStateStore = labyrinthRunStateStore,
            routeLoader = { accountId ->
                accountId?.let { labyrinthRouteStore.loadLatest(it) }
            },
            routeProgressSaver = { accountId, enterId, currentBlockId ->
                labyrinthRouteStore.updateCurrentBlock(accountId, enterId, currentBlockId)
            },
            routeExecutionGate = ::validateLabyrinthExecutionForAccount,
            nodeTemplateLoader = { AndroidLabyrinthNodeTemplateLoader(this).load() },
            debugFramePublisher = debugDashboard?.let { dashboard ->
                { frame, state, result, presentation ->
                    dashboard.publish(
                        bitmap = frame.bitmap,
                        state = state,
                        result = result,
                        presentation = presentation,
                        nowMillis = frame.timestampMillis,
                    )
                }
            },
            gameLauncher = gameLauncher@{
                val intent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE_NAME)
                    ?: return@gameLauncher false
                runCatching {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            },
        )
    }
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val labyrinthRunStateStore by lazy { RoomLabyrinthRunStateStore(database) }
    val labyrinthRouteStore by lazy { RoomLabyrinthRouteStore(database) }
    private val credentialStore by lazy { AndroidKeystoreCredentialStore(this) }
    private val sessionStore by lazy { AndroidKeystoreSdkSessionStore(this) }
    val gameSessionRegistry by lazy { InMemoryGameSessionRegistry() }
    private val bilibiliSdkGateway by lazy { BilibiliSdkGatewayFactory.create(this) }
    private val bilibiliSdkLoginCoordinator by lazy {
        BilibiliLoginCoordinator(bilibiliSdkGateway, sessionStore)
    }
    val bilibiliNativeLoginCoordinator: BilibiliNativeLoginCoordinator by lazy {
        BilibiliNativeLoginCoordinator(
            sdkCoordinator = bilibiliSdkLoginCoordinator,
            sdkGateway = bilibiliSdkGateway,
            sessionStore = sessionStore,
            gameGateway = BilibiliGameGatewayFactory.create(this),
            gameSessionRegistry = gameSessionRegistry,
        )
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(database, credentialStore, sessionStore, gameSessionRegistry)
    }
    val labyrinthController by lazy {
        com.landosol.toolbox.labyrinth.LabyrinthController(
            accountRepository, gameSessionRegistry, database, bilibiliNativeLoginCoordinator,
            com.landosol.toolbox.labyrinth.AndroidLabyrinthRerollSettingsStore(this),
            launchForeground = { com.landosol.toolbox.labyrinth.LabyrinthRerollService.start(this) },
        )
    }

    private suspend fun validateLabyrinthExecutionForAccount(
        accountId: Long?,
    ): LabyrinthExecutionGateResult {
        val id = accountId
            ?: return LabyrinthExecutionGateResult.Blocked("未选择账号，已禁止执行保存路线")
        val route = labyrinthRouteStore.loadLatest(id)
        val checkpoint = com.landosol.toolbox.labyrinth.RoomLabyrinthRerollCheckpointStore(database).load(id)
        // 执行入口复用上次刷开局/“当前开局”成功后保存的 TARGET 检查点。
        // 这里不能再次调用 top：应用进程重启后内存游戏会话不存在，也不应因此要求用户重新登录。
        return validateLabyrinthExecution(route, checkpoint, top = null)
    }

    /**
     * 无 Root 会话失效触发式重置：点进黎明界触发「会话失效」弹窗 → 识别到
     * 「错误提示」→ 点「返回标题」→ 回到标题页，随后等待登录/主页/冒险/黎明界。
     */
    val gameSessionResetWorkflow by lazy {
        val frameTracker = SessionExpiryFrameTracker()
        val presence = AccessibilityForegroundPresenceObserver(
            gamePackageName = GAME_PACKAGE_NAME,
            foregroundPackage = LandosolAccessibilityService::foregroundPackage,
        )
        // After a server-side reroll the client still holds the previous run's local state.
        // Tapping 进入黎明界 there opens the unrecognised guild-selection page and never
        // triggers the expiry popup; the bottom 主页 tab requests home data immediately and
        // is rejected by the server, which is the popup we want (labyrinth-full-automation §5.1).
        val terminator = com.landosol.toolbox.automation.session.AnchorTriggerSessionExpiryTerminator(
            onTap = { point ->
                when (accessibilityActionBackend.execute(AutomationAction.Tap(point))) {
                    com.landosol.toolbox.automation.AutomationBackendResult.Completed -> true
                    else -> false
                }
            },
            triggerPoint = { frameTracker.sessionInvalidationTrigger },
            returnTitlePoint = SESSION_RETURN_TITLE_POINT,
            popupVisible = { frameTracker.popupVisible },
            titleReached = { frameTracker.titleReached },
            frameSize = { frameTracker.frameSize },
            available = LandosolAccessibilityService::isConnected,
            returnTitleAnchorPoint = { frameTracker.anchorCenter(EntryAnchorId.SESSION_RETURN_TITLE) },
            trace = { step -> android.util.Log.d("LabyrinthReset", "trigger-terminator $step") },
        )
        val resetDashboard = labyrinthDebugDashboard
        GameSessionResetWorkflow(
            sessionManager = automationSessionManager,
            backend = CompositeSessionResetBackend(
                terminator = terminator,
                relauncher = Relauncher {
                    val intent = packageManager.getLaunchIntentForPackage(GAME_PACKAGE_NAME)
                        ?: return@Relauncher GameClientRelaunchResult.LAUNCH_UNAVAILABLE
                    runCatching {
                        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess.let { launched ->
                        if (launched) GameClientRelaunchResult.LAUNCH_REQUESTED
                        else GameClientRelaunchResult.LAUNCH_UNAVAILABLE
                    }
                },
            ),
            captureActive = CaptureStateRegistry::isActive,
            processorFactory = {
                val processor = AndroidLabyrinthEntryFrameProcessor.create(this)
                val processFrame: (CapturedFrame) -> LabyrinthEntryFrameResult = { frame ->
                    frameTracker.trackFrame(frame.bitmap.width, frame.bitmap.height)
                    processor.process(frame).also(frameTracker::record)
                }
                processFrame
            },
            blockClassifier = { result ->
                SessionBlockClassifier().classify(
                    scores = result.observation.anchorScores.toSessionBlockScores(),
                    pageTitleScore = result.observation.stateScores[LabyrinthEntryPageState.TITLE_WAITING_TAP] ?: 0.0,
                )
            },
            overlayCoordinator = automationOverlayCoordinator,
            presence = presence,
            actionExecutor = automationActionExecutor,
            entryActionPlannerFactory = { LabyrinthEntryActionPlanner() },
            debugFramePublisher = resetDashboard?.let { dashboard ->
                { frame, resetState, result, presentation ->
                    // The dashboard is keyed to the labyrinth session state; during a reset the
                    // session is stopped, so hand it a synthetic snapshot that carries the reset
                    // stage in the message and the real frame/page for the picture.
                    dashboard.publish(
                        bitmap = frame.bitmap,
                        state = com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState(
                            status = com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStatus.RUNNING,
                            dryRun = false,
                            frameCount = resetState.frameCount,
                            actionCount = resetState.actionCount,
                            message = "会话重置 ${resetState.stage.name}：${resetState.message ?: ""}",
                        ),
                        result = result,
                        presentation = presentation,
                        nowMillis = frame.timestampMillis,
                    )
                }
            },
        )
    }

    val labyrinthBatchCheckpointStore by lazy {
        com.landosol.toolbox.labyrinth.batch.AndroidLabyrinthBatchCheckpointStore(this)
    }

    /**
     * Unattended multi-run orchestration (labyrinth-full-automation.md §4). Sequences reroll →
     * client invalidation → run over the existing components; never touches capture itself.
     */
    val labyrinthBatchController by lazy {
        com.landosol.toolbox.labyrinth.batch.LabyrinthBatchController(
            ports = object : com.landosol.toolbox.labyrinth.batch.LabyrinthBatchPorts {
                override suspend fun reroll(accountId: Long, guildId: Int, difficulty: Int) =
                    labyrinthController.rerollForBatch(accountId, guildId, difficulty)

                override suspend fun invalidateClientSessionAndReturn():
                    com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult {
                    // AnchorTriggerSessionExpiryTerminator taps the recognised trigger, expects the
                    // "session expired" popup, taps 返回标题, then the entry planner navigates
                    // title → home → adventure → labyrinth home. READY means the stale local
                    // state is gone and the new server entry is what the client sees.
                    if (!CaptureStateRegistry.isActive()) {
                        return com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure(
                            "屏幕捕获已停止" + (CaptureStateRegistry.lastStopReason()?.let { "（$it）" } ?: ""),
                        )
                    }
                    return when (val started = gameSessionResetWorkflow.start()) {
                        is GameSessionResetStartResult.Started -> {
                            val final = gameSessionResetWorkflow.state.first { !it.running }
                            val ok = final.status == GameSessionResetStatus.STOPPED &&
                                final.lastBlock == SessionBlockKind.NONE
                            if (ok) {
                                com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Success("home-tab-session-expiry")
                            } else {
                                com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure(
                                    "重置流程 ${final.stage.name}/${final.status.name}" +
                                        (final.message?.let { "：$it" } ?: "") +
                                        (final.lastBlock.takeIf { it != SessionBlockKind.NONE }?.let { "，阻塞 $it" } ?: ""),
                                )
                            }
                        }
                        is GameSessionResetStartResult.Blocked ->
                            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure(started.reason)
                        is GameSessionResetStartResult.AlreadyRunning ->
                            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchInvalidationResult.Failure("会话重置已在运行")
                    }
                }

                override suspend fun startRun(accountId: Long, guildId: Int, runId: String): Boolean =
                    labyrinthEntryRecognitionSession.startAutomation(accountId, runId) is
                        LabyrinthEntryRecognitionStartResult.Started

                override suspend fun stopRun(reason: String) {
                    labyrinthEntryRecognitionSession.stop(reason, releaseCapture = false)
                }

                override suspend fun saveCheckpoint(
                    checkpoint: com.landosol.toolbox.labyrinth.batch.LabyrinthBatchCheckpoint,
                ) = labyrinthBatchCheckpointStore.save(checkpoint)
            },
        ).also { controller ->
            // Environment loss must pause the batch without widening any action (§18).
            autoRunScope.launch {
                CaptureStateRegistry.observe().collect { state ->
                    if (state !is CaptureState.Running && controller.state.value?.stage in ACTIVE_BATCH_STAGES) {
                        controller.haltForEnvironment(
                            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchHaltReason.CAPTURE_LOST,
                            "屏幕捕获已停止" + (CaptureStateRegistry.lastStopReason()?.let { "：$it" } ?: ""),
                        )
                    }
                }
            }
        }
    }

    /**
     * 刷取次数外层循环：入口+路线执行 → 会话重置 → 重新进入，直到达到目标轮数。
     * 单轮成功以路线进度 complete 为准；会话提前停止（急停/拒绝/超时）会中止循环。
     */
    val labyrinthAutoRunWorkflow by lazy {
        LabyrinthAutoRunWorkflow(
            startRun = { accountId ->
                labyrinthEntryRecognitionSession.startAutomation(accountId) is
                    LabyrinthEntryRecognitionStartResult.Started
            },
            awaitRunFinished = {
                labyrinthEntryRecognitionSession.state.first { it.running }
                val final = labyrinthEntryRecognitionSession.state.first { !it.running }
                if (final.routeProgress?.complete == true) {
                    LabyrinthAutoRunRoundOutcome.Completed
                } else {
                    LabyrinthAutoRunRoundOutcome.Aborted(final.message ?: "路线执行提前结束")
                }
            },
            resetSession = {
                when (gameSessionResetWorkflow.start()) {
                    is GameSessionResetStartResult.Started -> {
                        gameSessionResetWorkflow.state.first { it.running }
                        val final = gameSessionResetWorkflow.state.first { !it.running }
                        final.status == GameSessionResetStatus.STOPPED &&
                            final.lastBlock == SessionBlockKind.NONE
                    }

                    else -> false
                }
            },
        )
    }

    /** 自动执行循环运行在应用级作用域，离开页面不中断；停止需显式调用。 */
    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var autoRunJob: Job? = null

    /**
     * Starts an unattended batch: [targetRuns] cleared runs of the account's currently selected
     * guild and difficulty. Returns false when no account is selected or a batch is active.
     */
    fun startLabyrinthAutoRun(
        accountId: Long?,
        goals: List<com.landosol.toolbox.labyrinth.batch.LabyrinthBatchGoal>,
    ): Boolean {
        val id = accountId ?: return false
        if (goals.isEmpty()) return false
        val ui = labyrinthController.uiState.value
        val batchId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
            .format(java.util.Date())
        autoRunJob?.cancel()
        autoRunJob = autoRunScope.launch {
            labyrinthBatchController.start(
                batchId = batchId,
                accountId = id,
                goals = goals,
                difficulty = ui.selectedDifficulty,
            )
        }
        return true
    }

    fun stopLabyrinthAutoRun() {
        autoRunJob?.cancel()
        autoRunJob = null
        autoRunScope.launch {
            labyrinthBatchController.stop("用户停止自动执行")
            gameSessionResetWorkflow.stop("用户停止自动执行")
            // The batch is over: release the capture session the user authorized for it.
            labyrinthEntryRecognitionSession.stop("用户停止自动执行")
        }
    }

    private companion object {
        val ACTIVE_BATCH_STAGES = setOf(
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.REROLLING,
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.INVALIDATING_OLD_CLIENT_SESSION,
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RUNNING_LABYRINTH,
            com.landosol.toolbox.labyrinth.batch.LabyrinthBatchStage.RECORDING_RESULT,
        )
        const val DATABASE_UPDATE_LOG_TAG = "LabyrinthCnDatabase"
        const val GAME_PACKAGE_NAME = "com.bilibili.priconne"
        /** 旧触发点「冒险→黎明界」。已弃用：刷开局后会进入无识别的公会选择页。 */
        @Suppress("unused")
        val SESSION_EXPIRY_TRIGGER_POINT = ScreenPoint(1735f, 805f)
        /** 「返回标题」按钮（模板中心，1080p 参考系） */
        val SESSION_RETURN_TITLE_POINT = ScreenPoint(961f, 739f)
    }
}
