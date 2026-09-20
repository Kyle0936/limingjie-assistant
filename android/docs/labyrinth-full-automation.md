# 黎明界最终全自动流程设计与当前项目状态

> 目标：在用户一次完成必要授权并设置批量目标后，让助手无人值守地重复执行“刷开局 → 进入黎明界 → 完成整局 → 通关或达到失败上限 → 再刷开局 → 强制回标题 → 下一轮”，直到所有目标轮次完成。
>
> 本文以当前分支 `feature/approval-shell-recognition-team-plan`、HEAD `6ed2ecc` 及工作区未提交改动为基线。文中严格区分 **已存在/已验证能力** 与 **待实现的最终批处理能力**。
>
> 复核日期 2026-09-16。除 HEAD 外，工作区另有四批未提交改动：阶段二状态机去文案化、连结奖励链与容错保留、美空详情 OCR 收紧、强列绑定防误点。这些改动已影响第 14 节的结论。

## 1. 当前结论

当前项目已经具备最终全自动所需的大部分底层能力：

- 一级难度黎明界单局流程已经可以在实机条件下完整跑通到结算。
- 已有 MediaProjection 截图前台服务、Accessibility 输入服务、黎明界入口/地图/节点/战斗/奖励/结算状态机。
- 已有网络侧刷开局 `LabyrinthRerollWorkflow`、`LabyrinthRerollService` 与 Room 检查点持久化。
- 已有“战斗连续 3 次失败后停止当前自动战斗并调用刷开局”的接线。
- 已有 `GameSessionResetPlanner`，已经定义了“结束当前会话 → 登录 → 主页 → 冒险 → 黎明界 → 下一轮”的外层重置阶段。
- 当前 MediaProjection 实现已经针对 Android 14+/15 的单 token / 单 VirtualDisplay 规则做了保护。

因此下一阶段的主要任务不是重写单局黎明界逻辑，而是增加一个真正的 **BatchController / 多局生命周期管理器**，把已经存在但彼此独立的单局执行、刷开局、会话失效回标题、重新进入、轮次统计串成一个可恢复的长期任务。

---

## 2. 最终用户体验

用户在正式开始前只做一次设置，例如：

```text
批量任务

美食殿堂：10 次
咲恋救济院：10 次
破晓之星：成功通关 5 次

难度：1
战斗失败上限：3 次
失败达到上限：放弃当前开局并重新刷取

[开始全自动]
```

启动时完成：

1. Accessibility 已启用并连接；
2. 游戏保持最终横屏方向；
3. 用户授权一次 MediaProjection；
4. 创建一次 `VirtualDisplay`；
5. 批量任务开始。

之后直到目标全部完成，不再要求用户参与。

最终目标不是“每局授权一次”，而是：

```text
一次屏幕授权 = 一整个批量任务
```

例如 10 + 10 + 5 共 25 个目标轮次，共用同一个截图会话。

---

## 3. 当前已有模块

### 3.1 屏幕捕获

实现：`automation/capture/MediaProjectionCaptureService.kt`

当前行为：

- 作为 `mediaProjection` 类型 Foreground Service 运行；
- 注册 `MediaProjection.Callback.onStop()`；
- 等待真实显示尺寸稳定后才第一次创建 `VirtualDisplay`；
- Android 14+ 不重复调用 `createVirtualDisplay()`；
- 输入始终发往真实默认显示器，而不是 MediaProjection 的虚拟显示器；
- 捕获尺寸变化时不会冒险继续识别，而是停止捕获并要求重新授权。

当前代码中特别重要的设计：

```text
Android 14+：同一个 MediaProjection token 不允许创建第二个 VirtualDisplay
MuMu：resize()/setSurface() 后可能保留旧镜像变换
=> 尺寸变化时 fail closed
```

因此最终批处理的硬约束是：

> 开始批量任务前就把游戏置于最终横屏尺寸，整个批次期间禁止改变模拟器分辨率、旋转屏幕或销毁 Capture Service。

### 3.2 输入执行

实现：`automation/accessibility/LandosolAccessibilityService.kt`

用于：

- 点击；
- 滑动；
- 全局操作；
- 判断前台包名；
- 驱动游戏 UI 自动化。

这套能力与 MediaProjection 生命周期独立，因此游戏 Activity 跳转、返回标题、游戏自身会话失效都不会天然导致 Accessibility 授权失效。

### 3.3 单局黎明界自动化

核心实现：`LabyrinthEntryRecognitionSession.kt` 及 `labyrinth/*` 识别、策略、路线、编组模块。

当前单局已经覆盖的主要阶段包括：

```text
标题/加载
→ 主页
→ 冒险
→ 黎明界
→ 公会/开局
→ 初始角色
→ 地图与节点
→ 普通战斗 / EX / BOSS
→ 角色奖励 / 事件 / 遗物 / 商店 / 连结
→ 最终结算
```

项目目前采用“识别 → 决策 → 动作 → 状态确认”的受限自动化原则。入口阶段允许有限兜底动作，进入黎明界主体后不应使用无限制盲点。

### 3.4 战斗失败策略

当前 `LabyrinthEntryRecognitionSession` 已实现：

- 战斗失败页识别；
- 自动重试计数；
- EX/BOSS 独立策略；
- 单队 Boss 失败后可切换多队，`teamIndex` 在 3 队以内递进（`coerceIn(1, 3)`）；
- `rerollAfterThreeBattleFailures` 开关；
- 第三次挑战失败时不点击战斗“结束”结算，而是调用 `finishFromPlannerAndRequestReroll()`；
- `rerollRequester` 已接入 `LabyrinthController.startAfterBattleFailureReroll(accountId)`。

当前逻辑已经形成了：

```text
战斗失败
→ 达到失败阈值
→ 停止当前 RecognitionSession
→ 请求网络侧刷开局
```

缺少的是刷取完成后的 **UI 会话恢复与下一轮自动重启**。

### 3.5 网络侧刷开局

核心：

- `LabyrinthRerollWorkflow.kt`
- `LabyrinthController.kt`
- `LabyrinthRerollService.kt`
- `LabyrinthRerollCheckpointStore`
- Room 表 `labyrinth_reroll_checkpoints`

现有能力包括：

- 指定公会；
- 指定难度；
- 路线筛选；
- Boss 条件；
- 最大尝试次数 / 刷到出；
- 退出现有服务端黎明界；
- enter/top/read 等 API 流程；
- 刷取结果检查点持久化；
- 后台 `dataSync` Foreground Service；
- Partial WakeLock；
- 战斗失败时可由单局 FSM 直接触发。

需要注意：`LabyrinthRerollService` 的注释明确说明它是 **network-only reroll service**：

```text
Never starts a capture session or replays an interrupted enter.
```

这是正确边界。最终设计不应该让它接管截图/点击，而应由外层 BatchController 在刷取成功后恢复 UI 流程。

### 3.6 会话重置规划器

已有：`automation/session/GameSessionResetPlanner.kt`

已经定义阶段：

```text
PENDING
TERMINATING
RELAUNCHING
WAITING_LOGIN
NAVIGATING_HOME
NAVIGATING_ADVENTURE
NAVIGATING_DAWN_REALM
READY_FOR_NEXT_ROUND
SESSION_BLOCKED
FAILED
```

并且已经特别考虑“通过服务端刷取导致客户端旧会话失效”的模式：

- 不一定杀游戏进程；
- 可以等待“重新连接/重新登录”弹窗或标题页；
- 标题页出现后进入正常登录流程。

这与最终需求高度一致，但目前仍需要把它真正接到批量控制器以及两类结束场景。

---

## 4. 最终批量状态机

建议新增独立的顶层状态机，例如：

```kotlin
enum class LabyrinthBatchStage {
    IDLE,
    PREPARING_CAPTURE,
    REROLLING,
    WAITING_REROLL_RESULT,
    INVALIDATING_OLD_CLIENT_SESSION,
    RETURNING_TO_TITLE,
    ENTERING_GAME,
    ENTERING_LABYRINTH,
    RUNNING_LABYRINTH,
    RUN_CLEARED,
    RUN_FAILED,
    RECORDING_RESULT,
    ADVANCING_TARGET,
    COMPLETED,
    PAUSED,
    FAILED,
}
```

职责边界：

```text
BatchController
│
├── 决定当前该刷哪个公会、还差几次
├── 发起 reroll
├── 等待 reroll 成功
├── 触发旧客户端会话失效
├── 驱动返回标题
├── 启动/恢复单局 RecognitionSession
├── 接收通关 / 失败终态
├── 记录结果
└── 判断继续还是完成

LabyrinthEntryRecognitionSession
└── 只负责“一局内部怎么打”

LabyrinthRerollWorkflow
└── 只负责“服务端怎么刷出一个开局”

GameSessionResetPlanner
└── 只负责“旧客户端状态怎么回到新一局入口”
```

不要继续把多局生命周期塞进 `LabyrinthEntryRecognitionSession`。当前该类已经非常大，外层批处理继续堆进去会让节点状态、战斗状态和批次状态互相污染。

---

## 5. 两种结束场景的正式流程

### 5.1 情况 A：正常通关

已知游戏行为：

1. 通关结算完成后回到黎明界主页；
2. 后台完成下一次刷开局后，客户端仍停留在上一局结束后的旧本地状态；
3. 此时如果直接点击“黎明界”，会先进入公会选择页；
4. 由于本地状态未经过网络刷新，点击任意公会才触发旧会话失效并返回标题；
5. 因此最终自动化 **禁止使用“重新点击黎明界 → 再点公会”作为刷新方法**。

推荐流程：

```text
识别最终通关
→ 完成结算链
→ 回到黎明界主页
→ BatchController 记录本轮 SUCCESS
→ 后台执行下一次 reroll
→ reroll 成功
→ 不点击“进入黎明界”
→ 点击底部“主页”或“冒险”标签
→ 借网络请求触发旧客户端会话失效
→ 等待“重新连接/重新登录”弹窗或标题页
→ 确认返回标题
→ 正常入口导航
→ 主页
→ 冒险
→ 黎明界
→ 使用新服务端开局
→ 开始下一轮
```

关键安全规则：

> 当 BatchController 处于 `INVALIDATING_OLD_CLIENT_SESSION` 时，黎明界主页的“进入黎明界”按钮必须被全局禁止。只能使用已批准的“主页/冒险”刷新动作。

否则会进入一个正常单局 FSM 从未设计过的“旧本地状态公会选择页”。

### 5.2 情况 B：战斗失败达到上限

当前代码已经能在第三次挑战失败后发起 reroll。

目标流程：

```text
识别 BATTLE_FAILED
→ 达到允许重试次数
→ 不点击“结束”进行失败结算
→ BatchController 记录本轮 FAILED_MAX_RETRY
→ 后台执行 reroll
→ reroll 成功
→ 客户端仍保持战斗失败页
→ 点击“重新挑战”或“退出”中的一个经过实机验证的安全按钮
→ 该按钮触发网络交互
→ 旧客户端会话失效
→ 返回标题
→ 正常入口导航
→ 新一轮
```

这里建议最终只保留 **一个** 实机验证过最稳定的按钮作为“session invalidation trigger”，不要长期同时保留“重试或退出任选”。

已定：触发按钮为「重新挑战」。失败页没有底栏，通关路径用的主页标签在此页不存在；「结束」会提交失败结算，排除。`SessionExpiryFrameTracker.battleFailureRetryTrigger` 提供当前帧识别到的按钮中心，`AnchorTriggerSessionExpiryTerminator` 在该页点它。批次拥有的单局到达重试上限时，无论策略里「刷开局」是否开启，都以 FAILED_MAX_RETRY 结束（`labyrinthBatchOwnedRunEndsAtRetryLimit`），批次随后 reroll → 失败页点「重新挑战」→ 失效弹窗 → 返回标题。

优先原则：

- 不会真正提交上一局失败结算；
- 必然发起网络请求；
- 被服务端新 enter 状态拒绝后必然进入重新登录/标题链；
- 坐标或识别锚点稳定。

---

## 6. 批量目标模型

不要只保存“总共还要跑 N 次”。需要保存按公会拆分的目标和完成口径。

推荐：

```kotlin
enum class BatchGoalMode {
    ATTEMPTS,       // 启动/完成一轮即计数，不要求通关
    CLEARS,         // 只有通关才计数
}

data class LabyrinthBatchGoal(
    val guildId: Int,
    val targetCount: Int,
    val mode: BatchGoalMode,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
)
```

这样可以准确表达：

```text
美食殿堂：开局 10 次
咲恋救济院：开局 10 次
破晓之星：通关 5 次
```

如果实际需求最终统一为“全部按通关次数计算”，仍然建议保留 `mode`，因为测试期很可能需要“只跑 N 个开局采样”的能力。

### 6.1 计数时机

必须只在明确终态提交：

```text
SUCCESS            -> clearCount +1；ATTEMPTS 也 +1
FAILED_MAX_RETRY   -> failedCount +1；ATTEMPTS +1；CLEARS 不 +1
ABORTED_UNKNOWN    -> 不计正常轮次，单独记录异常
USER_STOPPED       -> 不计
```

不要在“刷到新开局”时就把轮次算完成，否则应用或游戏在途中崩溃会造成统计虚高。

---

## 7. 批量检查点与崩溃恢复

当前项目已经有 reroll 检查点，但最终多局任务还需要独立 Batch checkpoint。

建议持久化：

```kotlin
data class LabyrinthBatchCheckpoint(
    val batchId: String,
    val accountId: Long,
    val goals: List<LabyrinthBatchGoal>,
    val activeGoalIndex: Int,
    val stage: LabyrinthBatchStage,
    val currentRunId: String?,
    val currentEnterId: Long?,
    val currentGuildId: Int?,
    val currentDifficulty: Int,
    val currentRunOutcome: RunOutcome?,
    val rerollCompletedForNextRun: Boolean,
    val clientSessionNeedsInvalidation: Boolean,
    val lastSessionInvalidationAction: String?,
    val updatedAt: Long,
)
```

每一个不可逆操作之前/之后都落盘，例如：

```text
准备 reroll
reroll 成功
准备触发旧会话失效
已经返回标题
开始一局
确认通关
确认失败上限
结果已计数
切换到下一目标
```

这样 App 进程如果异常退出，重新打开后至少能告诉用户：

```text
上次批量任务停在：reroll 已成功、客户端旧会话尚未失效
```

而不是猜测并重复 enter / retire。

注意：Android 15 下 MediaProjection session 随 App 进程死亡而结束，因此 **进程死亡后的自动恢复仍然需要用户重新授权屏幕捕获**。检查点用于恢复业务状态，不用于绕过系统授权。

---

## 8. Android 15 屏幕授权与生命周期

### 8.1 正确模型

最终批次必须这样运行：

```text
用户点击“开始全自动”
→ 获取一次 MediaProjection 授权
→ 启动/保持 MediaProjectionCaptureService
→ 创建一次 VirtualDisplay
→ 全部轮次共享
→ 批次全部结束
→ 才 stop projection / release display
```

### 8.2 批次过程中禁止

禁止：

- 每轮停止 Capture Service；
- 每轮重新请求 MediaProjection；
- 每轮 release 后使用同一个 token 第二次 `createVirtualDisplay()`；
- 改变模拟器分辨率；
- 横竖屏切换；
- 批处理中清理助手自身进程；
- “清后台”时误杀 Landosol Toolbox；
- 将 reroll service 的结束等同于 capture service 的结束。

### 8.3 可以发生

在 Capture Service 仍存活的情况下，以下事件不应结束整个批次：

- PCR Activity 跳转；
- PCR 回标题；
- PCR 登录/加载；
- PCR 自身进程被单独重启；
- 单局 RecognitionSession stop/start；
- `LabyrinthRerollService` 独立启动和结束。

### 8.4 当前代码的实际限制

`MediaProjectionCaptureService.reconfigureDisplayIfNeeded()` 当前发现捕获尺寸变化时会主动：

```text
stopCapture("...请保持游戏当前方向并重新授权屏幕捕获")
```

所以最终全自动必须在 UI 上明确提示：

> 开始前先进入与游戏相同的最终横屏尺寸；批量过程中不要旋转、调整模拟器分辨率或改变显示模式。

---

## 9. RecognitionSession 与 CaptureSession 必须解耦

这是最终实现最容易出错的地方。

当前 `finishFromPlannerAndRequestReroll()` 会：

```text
stop 当前 RecognitionSession
→ 调用 rerollRequester
```

这本身没有问题，但 **`stop()` 的语义目前同时结束了截图会话**。这一点已核实，不是待确认项：

```text
LabyrinthEntryRecognitionSession.stop()      // 会话源码
→ 释放 CaptureFrameBus 租约
→ requestCaptureStop()                        // 无条件调用，共 9 处调用点
→ captureStop()                               // LandosolToolboxApplication.kt:165 接线
→ MediaProjectionCaptureService.stop(this)
```

`finishFromPlannerAndRequestReroll()` 走的正是这条 `stop()`，所以现状恰好就是本节下方标注为“不能是”的那种形态：Run STOP 会连带 Capture STOP。批量模式下这意味着第二局必须重新授权屏幕捕获，与“一次授权 = 一个批量任务”的目标直接冲突。

**因此这是 Phase 1 的前置阻塞项，必须先解耦，再接入任何批量编排。** 第 15 节的 Phase 2 之所以“路径较短、适合先实机验证”，前提也正是先修掉这一条。

最终需要三层生命周期：

```text
Capture lifetime
└── 整个批量任务，最长

Batch lifetime
└── 10+10+5 等全部目标

Run lifetime
└── 一次黎明界开局，最短
```

期望关系：

```text
Capture START
  Batch START
    Run #1 START -> STOP
    reroll
    reset client
    Run #2 START -> STOP
    reroll
    ...
  Batch COMPLETE
Capture STOP
```

不能是：

```text
Run STOP
→ Capture STOP
→ 下一轮再次申请授权
```

---

## 10. 入口审批壳在批量模式中的角色

最终多局流程更需要审批壳，而不是更少。

### 10.1 正常登录阶段

允许页面集合应限制为：

```text
TITLE_WAITING_TAP
GAME_LOADING_PROGRESS
PRE_HOME_DATA_LOADING
公告/主页相关已知状态
HOME
ADVENTURE
DAWN_REALM_HOME_IDLE
```

只有入口阶段可以使用有限的兜底推进点击。

### 10.2 会话失效阶段

BatchController 明确进入 `RETURNING_TO_TITLE` 后，允许：

```text
旧页面
重新连接弹窗
重新登录弹窗
TITLE_WAITING_TAP
```

其它任何未知页面不得自动扩大点击范围。

### 10.3 单局运行阶段

继续沿用现有黎明界 FSM 的受限动作，不允许因为外层加入 BatchController 又恢复“UNKNOWN 页面随机点击”。

---

## 11. 通关终态必须成为正式事件

最终 BatchController 不应该通过 UI 文案或 RecognitionSession 是否 stop 来猜“是不是通关了”。

建议单局自动化向外只发明确事件：

```kotlin
sealed interface LabyrinthRunTerminalEvent {
    data class Cleared(...) : LabyrinthRunTerminalEvent
    data class FailedMaxRetry(...) : LabyrinthRunTerminalEvent
    data class FatalUnknown(val reason: String) : LabyrinthRunTerminalEvent
    data object UserStopped : LabyrinthRunTerminalEvent
}
```

其中 `Cleared` 必须在最终结算链完成、确认已经回到黎明界主页后再发。

这样可以避免：

- Boss 刚死亡就开始下一次 reroll；
- 角色奖励尚未领取就替换服务端状态；
- 结算动画中断；
- “完成路线”与“真正完成本局”混淆。

---

## 12. 下一轮公会选择

批量目标本身决定下一局 reroll 的公会。

例如：

```text
目标 1：美食殿堂 10
目标 2：咲恋救济院 10
目标 3：破晓之星通关 5
```

调度策略建议默认顺序执行：

```text
美食殿堂完成目标
→ 咲恋救济院完成目标
→ 破晓之星完成目标
```

而不是每局轮换公会。原因：

- 更容易排查识别/角色策略问题；
- opening roster policy 可在一批内保持稳定；
- 日志更容易比较；
- 减少频繁切换策略状态。

目标切换时必须重新调用现有：

```text
planner.configureOpeningRoster(guildId)
```

并清空上一局角色、节点、战斗、编组、pending transition 等所有 **Run-scoped** 状态。

---

## 13. 每轮开始前必须清空的状态

历史实机问题已经证明，跨局状态污染会造成非常隐蔽的错误。

下一轮开始时至少应重置：

- 当前路线执行位置；
- 当前节点/待确认节点；
- `pendingNodeTransition`；
- 已加入角色 roster；
- reward acquisition 状态；
- battle retry count；
- EX retry 状态；
- Boss single/multi fallback 状态；
- 当前战斗类型/context；
- 上一次队伍签名；
- 编组扫描游标；
- 有效角色扫描结果；
- shop/event 子流程状态；
- settlement 子流程状态；
- 滚动方向/边界证据；
- UNKNOWN 恢复计数；
- 本局 trace id。

但不得重置：

- MediaProjection；
- VirtualDisplay；
- Accessibility 连接；
- Batch checkpoint；
- 当前批量目标累计计数。

---

## 14. 与当前“节点状态遗漏”问题的关系

近期出现过：20601 已通关并领取角色后，下一节点已经是 20701，但系统仍继续寻找 20601。

截至 2026-09-16 已定位并修复三个独立根因：

```text
连结奖励链未被当作进入凭证
  连结只接受 LINK_CHOICE，而三个角色物品奖励页与角色奖励选择页未被接受；
  采样帧跳过短暂的连结选择页后，游标不再推进。已对齐事件节点的页面集合。

容错期间 pending 转移被页面切换清掉
  三帧类型容错的设计前提是下一帧仍能看到待确认转移，但语义页面立即清空了它；
  第二帧的正确页面已无转移可推进，票据恢复又因多后继被拒。已加入容错期保留。

语义修复把单个候选绑定到错误槽位
  完整三节点列被识别后，发光动画让下方节点短暂消失，剩下的单个候选框跨越第 1、2 行，
  语义修复只依据类型唯一性采纳了它，点击落在上方事件节点。已加入强列绑定记忆。

这类问题在单局模式下只是“卡当前局”，在无人值守多局模式下会直接破坏整个批次。

上述三例都已修复并入库为回归测试，但都是“补住已知破口”，**账本本身仍然缺失**。

2026-09-17 第一次完整跑到最终结算，又暴露出结算链自身的两个问题（与节点账本无关，记在这里便于对照第 11 节的 `Cleared` 前置条件）：

```text
RESULT 页无计划
  最终 Boss 完成路线时先进入 BEFORE_SCORE，随后 RESULT 页出现；
  旧转换只接受 NONE → SCORE_RESULT，于是 RESULT 页永远拿不到关闭按钮计划，
  402 次盲点全部落在动画兜底点上。已改为 NONE/BEFORE_SCORE 均可进入 SCORE_RESULT。
  实机复测（2026-09-17 02:08）又发现第一次点「关闭」只跳过分数滚动动画，RESULT 页仍在，
  而阶段已推进到 CHEST_SEQUENCE，RESULT 页再次无计划。已允许 CHEST_SEQUENCE 在 RESULT 页
  可见时退回 SCORE_RESULT；可见的 RESULT 页就是分数阶段，宝箱链在它消失前不算开始。

宝箱开封结果的确认按钮位置错
  该弹窗是居中模态，确认在底部居中 (958,958)；原兜底点和锚点都指向右下角的「下一步」。
  已按实机截图改锚点与兜底点。

宝箱开封结果与最终获得道具无法识别（2026-09-17 录像 公主连结(28).mp4 逐帧回放）
  宝箱页：run_clear/ 模板目录从未存在，页面只能分到 UNKNOWN。已从录像裁出
  run_clear_chest_result_title.png 与 run_clear_chest_confirm_button.png 入包，锚点框与裁剪对齐。
  获得道具页：地图的标题和两侧控件在弹窗后面仍可见，NODE_SELECTION 0.82 与 ITEM_REWARD 0.88 落在
  歧义边距内，页面判为 UNKNOWN，最终道具永远关不掉。分类器增加「强模态覆盖地图」规则：
  三个道具页锚点都 ≥0.80 时弹窗拥有该帧。
  录像 10 个关键帧已固化为回归夹具（test/resources/labyrinth/run-clear-20260917），
  用发布资源包逐帧断言页面。
```

所以在批量模式上线前，需要保证：

```text
进入节点
→ 建立节点语义凭证
→ 节点主体完成
→ 奖励/结算完成
→ commit 当前节点完成
→ route cursor 前进
→ 才允许寻找下一个节点
```

不能仅依赖当前视觉画面反推“之前节点是否完成”。

建议把“本局流程账本”作为 Run checkpoint 的一部分，至少记录：

```text
enteredNodeId
nodeOutcomeConfirmed
rewardCommitted
lastCompletedNodeId
nextExpectedNodeIds
```

BatchController 不直接操作这些字段，只要求单局 FSM 在发出 `Cleared` 前内部账本完全一致。

---

## 15. 推荐实现顺序

### Phase 1：只做外层编排，不动识别

新增：

- `LabyrinthBatchController`
- `LabyrinthBatchCheckpointStore`
- `LabyrinthBatchGoal`
- `LabyrinthRunTerminalEvent`

先使用手动/测试事件模拟：

```text
RUNNING
→ Cleared
→ REROLLING
→ RETURNING_TO_TITLE
→ NEXT_RUN
```

验证状态机本身。

### Phase 2：接通战斗失败路径

复用现有 `finishFromPlannerAndRequestReroll()`，改成把终态交给 BatchController，而不是单局 FSM 自己决定整个后续生命周期。

目标：

```text
第三次失败
→ reroll
→ 失败页触发会话失效
→ 标题
→ 新一轮自动开始
```

这一条比通关路径短，适合先实机验证。

### Phase 3：接通正常通关路径

增加可靠 `Cleared` 事件并实现：

```text
黎明界主页
→ reroll
→ 点击主页/冒险触发旧会话失效
→ 标题
→ 新一轮
```

特别测试：不得误点“进入黎明界”。

### Phase 4：批量目标与 UI

支持：

- 多个公会目标；
- ATTEMPTS / CLEARS；
- 目标完成数；
- 当前第几轮；
- 成功/失败/异常计数；
- 暂停；
- 停止；
- 崩溃后显示可恢复 checkpoint。

### Phase 5：长时间稳定性

至少执行：

```text
同一公会 10 轮
两个公会各 10 轮
混合成功/失败 20+ 轮
```

观察：

- Capture Service 是否始终存活；
- VirtualDisplay 是否从未重建；
- Accessibility 是否保持连接；
- 内存是否持续增长；
- Bitmap/ImageReader 是否泄漏；
- OCR worker 是否累积；
- 前台服务时限；
- WakeLock 生命周期；
- 每轮状态是否完全清空。

---

## 16. 必须补的测试

### 16.1 纯 JVM 状态机测试

覆盖：

1. `Cleared -> reroll -> invalidate -> title -> next run`；
2. `FailedMaxRetry -> reroll -> failure page invalidation -> title -> next run`；
3. reroll 失败不得点击客户端；
4. reroll 成功但未确认会话失效，不得启动下一轮；
5. 通关后的旧黎明界主页禁止点击“进入黎明界”；
6. 目标由公会 A 切换到公会 B 后，opening roster policy 同步更新；
7. CLEARS 模式失败不增加完成数；
8. ATTEMPTS 模式失败增加完成轮数；
9. 重启恢复 checkpoint 不重复计数；
10. 同一 run outcome 重复上报保持幂等。

### 16.2 Android/实机测试

至少验证：

1. Android 15 一次授权连续完成 3 局；
2. 三局期间 `createVirtualDisplay()` 只调用一次；
3. PCR 返回标题不影响截图；
4. PCR 被重启不影响截图；
5. `LabyrinthRerollService` 结束不停止 Capture Service；
6. RecognitionSession stop/start 不停止 Capture Service；
7. 通关后点主页/冒险可以稳定触发回标题；
8. 失败页选定的按钮可以稳定触发回标题；
9. 屏幕旋转/尺寸变化会明确暂停整个 Batch，并提示重新授权，而不是继续错误识别；
10. MediaProjection 被系统撤销时 Batch 转 `PAUSED/FAILED_CAPTURE`，绝不继续盲点。

---

## 17. 日志与调试包

最终无人值守模式必须给每一轮一个唯一 ID。

推荐日志前缀：

```text
batch=20260916-001 goal=gourmet run=007 stage=RUNNING ...
batch=20260916-001 goal=gourmet run=007 outcome=CLEARED
batch=20260916-001 goal=gourmet run=007 stage=REROLLING
batch=20260916-001 goal=gourmet run=008 stage=RETURNING_TO_TITLE
```

调试包建议额外导出：

- Batch checkpoint；
- 每轮 outcome；
- 每轮开始/结束时间；
- enterId；
- 公会、难度；
- 最后完成节点；
- 失败战斗类型；
- retry count；
- 触发 reroll 原因；
- session invalidation 使用的动作；
- 标题页重新出现耗时；
- Capture session startedAt 与是否发生 onStop。

这样以后出现“跑了 17 局，第 18 局莫名停了”，不需要依赖上一份已经丢失的 log 猜原因。

---

## 18. 安全停止条件

以下情况必须停止/暂停整个 Batch，不能自动扩大动作范围：

- MediaProjection `onStop()`；
- 捕获尺寸变化；
- Accessibility 断开；
- 当前前台包长期不是游戏；
- reroll API 返回未知不可恢复错误；
- 验证码；
- 回标题超时；
- 新一轮入口无法与 reroll checkpoint 对齐；
- 连续出现未知页面；
- run checkpoint 与视觉状态矛盾；
- 当前公会/难度与 Batch 目标不一致；
- 服务端 enterId 与预期 checkpoint 不一致。

原则：

> 多局无人值守的目标是“在已知流程内自动恢复”，不是“未知状态下更激进地点击”。

---

## 19. 当前未完成项清单

截至本文基线，最终全自动还缺：

- [x] **Capture lifetime 与 Run lifetime 解耦**（`stop(releaseCapture = false)`；reroll 交接路径不再停截图；单测覆盖）。2026-09-17 实机第一次通关后又发现通关路径 `markRunCleared → finishFromPlanner → stop()` 仍用默认 `releaseCapture = true` 关掉了截图，随后会话失效步骤因无截图被拒。已把所有由运行逻辑发起的停止（终态页、被拒点击、规划器停止）统一走 `stopFromRunLogic`，批次拥有该局时不释放截图；只有用户主动停止才释放；
- [x] BatchController 顶层多局状态机（`labyrinth/batch/`，纯 Kotlin，§16.1 十条 JVM 用例全部通过）；
- [x] Batch 目标配置与 UI（自动执行页「批量自动执行」卡片：五个公会各一个次数输入框，留空跳过；每行可切换「按通关计 / 按开局计」；按列表顺序执行）；
- [x] Batch checkpoint 持久化（SharedPreferences JSON，`AndroidLabyrinthBatchCheckpointStore`；含 `lastSessionInvalidationAction`）；
- [x] 单局明确 `Cleared/FailedMaxRetry` 终态事件（`runTerminalListener`；用户停止 → `UserStopped`，其余 → `FatalUnknown`）；
- [x] 通关后旧会话失效执行器：`AnchorTriggerSessionExpiryTerminator`。**不再有任何固定坐标点击**（2026-09-17 用户指出固定点底栏「主页」在其他页面也会落下去）。触发点由 `SessionExpiryFrameTracker.sessionInvalidationTrigger` 按当前识别页面给出：黎明界主页 → 底栏「我的主页」标签模板 `dawn_home/dawn_home_my_home_tab.png`（锚框 85,945,175,125，得分 ≥0.70 才算命中；最初用的是已选中的「冒险」标签，2026-09-17 实测点它不联网、不触发返回标题）；战斗失败页 → 结束 → 撤退（无报酬） → 确认 三步链，每步按 `LabyrinthBattleEndConfirmationDetector` 识别到的对话框阶段给按钮；其余页面 → null，终止器等待直到超时，一次都不点。录像回归夹具断言该模板在 t10.5 主页 ≥0.90、在结算链其余 9 帧 <0.50。不点「进入黎明界」，符合第 5.1 节。首次实机暴露出 `GameSessionResetWorkflow` 的一个短路：TERMINATING 阶段看到旧黎明界主页就直接 READY，终止器的结果被忽略。已改为 TERMINATING / RELAUNCHING 期间不判 READY，并抽出纯函数 `gameSessionResetReadyDecision` 加测试；
- [x] 失败页会话失效触发：2026-09-17 实机（批次 20260917-031313 run001）暴露两处空缺。(1) 策略里「刷开局」关闭时，EX 达到重试上限走的是交互模式的「保留在失败页」停止，`pendingTerminalOutcome` 为空，批次收到 `FatalUnknown` 直接 FAILED，游戏停在 战斗失败。已加纯函数 `labyrinthBatchOwnedRunEndsAtRetryLimit`：批次拥有本局时到达上限一律走 `finishFromPlannerAndRequestReroll`（FAILED_MAX_RETRY，保留截图），策略开关只管交互单局。(2) 战斗失败页没有底栏，主页标签点不到。`SessionExpiryFrameTracker` 现在暴露失败页识别到的「重新挑战」按钮中心（仅当前帧为 BATTLE_FAILED 时非空），`AnchorTriggerSessionExpiryTerminator` 新增 `pageTriggerPoint`：非空时代替主页标签，每次点击前重新读取，页面一变就回到主页标签。「重新挑战」向服务端发起新战斗请求，服务端已是新 enter，请求被拒即弹会话失效提示，不会提交失败结算（对应 §5.2「只保留一个触发按钮」）。**失败页上「重新挑战」是否确实触发失效弹窗待实机验证**；
- [x] reroll 完成 → GameSessionResetWorkflow → RecognitionSession 重启的正式接线（`LabyrinthBatchPorts` 三个端口）；
- [x] 批次首轮视为初次执行：`GameSessionResetWorkflow` 在 PENDING 阶段先只看不动（最多 6 s）识别客户端当前页面。标题 / 加载 / 公告 / 主页 / 冒险页（`gameSessionResetSkipsTermination`）尚未载入黎明界状态，直接进入入口导航，不触发返回标题；黎明界主页 / 地图 / 失败页 / 未识别才走会话失效。PENDING 期间看到黎明界主页也不判 READY（2026-09-17 用户：开始批量执行应视为初次执行，可从黎明界之前的登录流程发起）；
- [x] EX 身份探测按「每次到达挑战页」计预算：2026-09-17 调试包 152039，首次挑战页 15:13:58 进入编组打输，「重新挑战」15:16:05 回到挑战页，探测计时仍是首次的，10 s 预算早已用尽，0.4 s 后即以「未建立可信身份」停机，OCR 一次都没读到「好朋友X」（名称框 520,550,420,65 与实机文字位置核对无误）。已加 `labyrinthExIdentityProbeRestarts`：从任何其他已识别页面回到挑战页且身份未定时，重置计时与详情探测次数；UNKNOWN 帧不算离开；
- [x] 战斗失败后重刷复用了当前开局：`LabyrinthRerollWorkflow` 读到服务端现有开局且它符合目标路线时，一律 `resumedExisting` 返回成功，不看 `retireExisting`（该开关只撤退**不匹配**的开局，交互模式下用户已坐在完美开局上时保留它是对的）。刚打输的完美开局同样「匹配」，于是失败重刷把同一个 enterId 原样交回（0 分 0 秒）。新增 `LabyrinthRerollConfig.abandonExisting`：为真时跳过路线读取直接撤退；失败重刷（`startAfterBattleFailureReroll`）与批次（`rerollForBatch`）强制为真，交互刷开局保持原样；
- [x] Boss 多队第三队不再因缺 T 留空：`bossMultiTeamSearch` 只在安全容量内组队，合格一号位不够时后面的槽位直接空着，Boss 剩一丝血也判失败（2026-09-17 用户反馈）。现在安全队之后用剩余角色组「输出补刀队」（`bestSupplementFormation`，关闭首位坦克门槛）填满请求的队数，`LabyrinthTeamPlan.safeTeamCount` 标记安全队数量；第一队仍必须 T 领队，第 2/3 队（`allowSupplementLead`）允许无 T 领队；
- [x] 失败页触发改为撤退链：2026-09-17 实测「重新挑战」只回到 EX 挑战页、不联网，要重新编组到最后「进入战斗」才发请求。改走 结束 → 撤退（无报酬） → 确认：确认才向服务端撤退，旧 enter 被拒即弹会话失效。新增 `LabyrinthBattleEndConfirmationDetector`（结构判定：蓝标题条 + 底部按钮排布，三键=CHOICE、宽取消+确认=RETREAT_CONFIRM），三张用户截图入夹具 `test/resources/battle/ex_*_20260917.png`。终止器触发上限提到 6 次。首次实机（批次 195521）：点「结束」后「结束确认」的蓝标题条被 `SESSION_ERROR_TITLE` 判成失效弹窗，终止器去点「返回标题」坐标，落在「撤退」上，随后卡在确认页等标题。已改为识别到结束确认对话框时不算失效弹窗。第二次实机（调试包 152012）：三步全部点对，失效弹窗确实出现，但会话重置流程的帧处理器在弹窗后面的地图上跑了完整节点扫描（一帧 6.9 s），追踪器 10 s 没收到帧，终止器 5 s 预算到期判超时。已改为重置流程的处理器关闭节点扫描（`nodeScanRequested = false`），点击触发后等弹窗的预算单独 12 s（`popupAfterTapTimeoutMillis`，含服务端往返）。弹窗 → 返回标题 → 标题页这一段**仍待实机验证**；
- [x] 拉比林斯开局赠送角色：该公会在三个自选角色之外自动加入「菈比莉斯塔」(1068)，以单独的角色加入弹窗出现。`LabyrinthOpeningRosterConfig.grantedCharacters` 记录赠送角色，三选确认写入角色池时一并写入（`labyrinthGuildGrantedRosterMatches`），不依赖该弹窗的头像识别；弹窗本身按通用角色加入页关闭；
- [x] 一个节点方块被同时判给两个 blockId，结果点错节点（2026-09-20 调试包 020234）。路线要去 连结#30402，实际进了事件，报「节点连结#30402进入页面不匹配：识别为EVENT_CHOICE」。拓扑已经把 (820,220) 这一格按行序绑给 事件#30403、把 (810,650) 绑给 事件#30401，也就是说 30402 只能是中间那格没被检测到；而分类器把上面那格读成「连结 0.664」（日志里同时记了 TYPE_CONFLICT：expected 事件 / detected 连结），路线正好想要连结，于是这一格又被判给了 30402 —— 同一个 rect 上挂了两个 blockId，规划器挑了它想要的那个，点下去进了事件。两处收口：(1) 语义修复不再采用已被拓扑以 ≥0.70 置信度绑定的 rect——凭「它的类型正好是路线想要的」来推翻拓扑是循环论证；(2) 合并阶段，遗留的按类型映射不得占用拓扑已认领的 rect。现在这种帧上 30402 干脆没有映射，保留 TYPE_CONFLICT 继续看下一帧，而不是点下去；
- [x] 事件页点了却没反应，然后永久卡住（2026-09-20 调试包 133934）。600 帧全是 EVENT_CHOICE，横跨 323 秒，`actionCount` 始终停在 271 ——一次动作都没再发。事件 OCR 是「2315 · 稳定3 · 可信」，推荐是「23151 我还没吃够呢☆ · 可执行」，界面却一直显示「事件推荐已生成，等待按钮稳定」。这条消息只有在 `eventChoiceCommitted == true` 时才可能出现（`actionSafe` 为真时 `safetyNote` 必为 null，而 `actionSafe` 为真本该直接产出点击计划），也就是说点击早就发出去了、游戏没收到，而这个标志只在页面切换时清除，页面恰恰就是那个不变的东西。现在提交带时限：超过 `EVENT_CHOICE_COMMIT_RETRY_MILLIS`（6 s）页面仍停在事件页就重新武装并重点，最多 3 次，之后以明确理由停机，而不是无限等待。重点是安全的：事件页只接受一次选择，若点击其实生效了只是画面慢，重试计划成型时页面已经变了；
- [x] 通关后停在最终 RESULT 页不动（2026-09-20 调试包 110329）。区域5 已走 36/36，整整 325 秒、`actionCount` 卡在 440，界面报「未知或低置信度页面，动作已禁用」。RESULT 横幅匹配到 0.995，但页面分是 `min(横幅, 关闭按钮)`，而「加入的角色」这一版的按钮是**下一步**、模板是**关闭**（同一个药丸底框、字不同），按钮只有 0.60，把整页拖到 0.602；RESULT 是盖在地图上的模态，底下地图的头部和侧边按钮仍可见，NODE_MAP_VIEW 得 0.529。两者相差 0.073，差 0.08 的歧义边界 **0.007**，于是判为 UNKNOWN、禁用动作、永久停住。与 2026-09-17「获得道具」那次是同一类问题，修法也沿用同一条原则：RESULT 横幅是全屏独一份的大图，达到 0.92 即可单独认定该帧属于结算页，绕过歧义边界。没有伪造「下一步」模板——按钮本来就随子页面变化，`min(横幅, 按钮)` 要求一个不一定存在的按钮，本身就是错的；
- [x] 激活光晕是呼吸动画，判定阈值卡在它的振幅里（2026-09-20 调试包 142732）。路线目标 EX战斗#40301 就在画面中央、亮着极难的紫光，14 帧节点识别**每一帧都找到了它**，但只有 3 帧判为可点击。紫光四项指标随动画同起同落，而四个阈值（0.06/0.06/0.05/0.30）每一个都落在这个节点的亮度区间之内：

  | 指标 | 未点亮的节点 | 这个亮着的节点 | 旧阈值 |
  |---|---|---|---|
  | score | 0.00–0.02 | 0.04–0.18 | 0.06 |
  | sideScore | 0.00 | 0.04–0.14 | 0.06 |
  | lowerScore | 0.00–0.03 | 0.04–0.21 | 0.05 |
  | rowCoverage | 0.00 | 0.28–0.53 | 0.30 |

  点击还要求**连续** `NODE_CLICK_STABLE_FRAMES`(3) 帧可点击，而节点页单帧耗时 2.2 s、光晕周期比这短，于是 90 秒里出现过 5 次带 rect 的决策，却没有一次连上 3 帧——一次都没点。亮与不亮之间本来有很宽的间隔，阈值只是站错了边；现在挪到两组之间（0.035/0.035/0.030/0.22），四项仍需同时成立。判定抽成 `labyrinthNodePurpleActivation()`，回归测试直接用日志里那 14 组真实数值；
- [x] 1.0.9（2026-09-20）。修复通关后停在最终 RESULT 页不动；修复激活光晕呼吸动画导致节点点不下去；
- [x] 1.0.8（2026-09-20）。本版包含：同一局第二次遇到同一个 EX 的识别死锁、一个节点方块被同时判给两个 blockId 导致点错节点、事件页提交后永久卡死、以及诊断包截图与分数的配对说明。版本号只在 `build.gradle.kts` 一处声明，`AppVersionTest` 盯住它，避免只改 gradle 而忘了同步发布动作；
- [x] 正式版本号 1.0.7（2026-09-20）。`versionName`/`versionCode` 由占位的 `0.1.0-dev`/`1` 改为 `1.0.7`/`10007`（major×10000+minor×100+patch，保证名字变化时编号仍单调）。版本来自唯一来源 `AppVersion`，出现在：进程启动日志首行（`LandosolToolbox: 黎明界助手启动：版本 1.0.7 (10007)`）、诊断包 `README.txt`、`logs/logcat.txt` 首行、`state/latest.json` 与 `state/history.ndjson` 每条记录的 `appVersion`/`appVersionCode` 字段、网页面板标题与顶栏、「黎明界」页面标题下方。动机是 2026-09-19 的 185837 调试包：它描述的行为在本仓库任何提交里都不存在，却无法判断出自哪个构建；
- [x] 同一局里第二次遇到同一个 EX 就卡死（2026-09-20 调试包 002652）。美空在这一局出现两次：第一次正常识别并打完，第二次连续 18 帧都显示「正在识别特殊EX右侧本体：美空」——名字每帧都读对，却始终不可信。魔物详情的名字行两次渲染像素完全一致，`RelicOcrScheduler` 指纹命中缓存，第二次一次 OCR 都不会再发；而「两次独立读取」是在 resolver 里按 `evidenceId` 数的，离开挑战页时清零，回来后只看到缓存里那个恒定的 id，加到 1 就不动了，等一次调度器按设计永远不会安排的读取。与 173956 的槽位抢占不是同一条，`ABANDONED_SLOT_MILLIS` 那个修复在这里不适用。现在计数下沉到调度器：`Read.confirmations` 在文本与上次一致时累加，缓存命中天然带着已确认次数返回；两次读取不一致时额外补一次读取（只补一次），避免等一致的调用方永久等待。resolver 删掉两套 `evidenceId` 计数器，改为 `confirmations >= 2`；
- [x] 迷宫大师开局赠送遗物弹窗「迷宫遗物效果」：与「获得道具」同壳、同关闭按钮，仅标题与说明不同，此前判为 NODE_MAP_VIEW 不会关闭。新增 `relic_effect_title/instruction` 模板，分类器把两套标题任一命中都算 ITEM_REWARD；两张用户截图入夹具（`test/resources/labyrinth/*-20260917.png`）；
- [x] EX 第二次失败换奶兜底不再阻塞：失败队五人全是有效效果角色时，此前拒绝一切换出并停在失败页。现在有效角色「最后换、不是不换」：优先换非有效的最低分，没有就换有效角色里最低分的一名；连奶都没有时回退普通 fallback 搜索，不再返回不可用；
- [x] 事件后「无法获得报酬」单按钮确认弹窗：没有自己的页面状态，判为 UNKNOWN 后无人点击。新增 `labyrinthGenericConfirmDialogRect`：UNKNOWN 帧上「确认」按钮模板（复用日期变更弹窗的同款按钮，≥0.85）命中、且排除日期变更 / 会话失效 / 移动确认 / 结束确认四类同壳弹窗时，点它（动作 `CONFIRM_GENERIC_DIALOG`）。用户截图合成到 1080p 入夹具 `event-no-reward-confirm-20260917.png`。首次实机（2026-09-18）：真机帧上蓝标题条与「错误提示」模板得分 0.64，会话阻塞判定抢先，等「返回标题」等了 255 次动作；页面又被地图判成 NODE_SELECTION，只认 UNKNOWN 的规则够不着。已改为：会话阻塞判定先排除识别到确认弹窗的帧；确认弹窗允许出现在地图类页面（UNKNOWN / NODE_SELECTION / NODE_MAP_VIEW / EVENT_CHOICE / EVENT_ANIMATION）上并优先于页面分支；区分依据改用按钮（返回标题为蓝、确认为浅色），不再要求标题条低分。真机帧入夹具 `event-no-reward-confirm-live-20260918.png`；
- [x] 事件自由选角支持选 2 名：选角数量不可读，但「去邀请」只有选够才亮。改为：亮了就确认（不论几名）；选完一名 2.5 s 后按钮仍识别为灰色则再选一名，上限 3 名（`labyrinthEventFreeRoleNeedsAnotherPick`）。确认时把全部已选角色计入待获得列表；
- [x] 有效效果角色为 0 时扫描阻塞：切到「有效效果」筛选后列表为空、显示「未搜索到该角色」，扫描一直等卡片和滚动反馈（2026-09-18）。新增模板 `battle_team_selection/roster_empty_notice.png`（锚框 802,527,297,38），`labyrinthEffectiveFilterIsEmpty`：有效效果筛选 + 画面稳定 + 无卡片 + 提示命中 ≥0.80 时直接视为 0 名对策角色，恢复全部筛选并开始编组；
- [x] 商店退出确认弹窗被当成节点移动确认（2026-09-18 调试包 161911）：弹窗淡入那一帧页面判为 UNKNOWN，但商店标题/关闭按钮仍是 1.0。通用双按钮检测器命中后，「孤立弹窗收养」路径把它认作路线上唯一剩余后继 Boss#30701 的移动确认（该节点从未被点击过），随后等 Boss 进入页、看到商店页，判为进入页不匹配并停机。取消路径本来就有商店背景防护，收养路径没有；已给 `labyrinthCanRecoverOrphanNodeMoveConfirmation` 加 `shopBackgroundVisible`，可见商店界面时一律不收养；
- [x] 商店内每次购买都报「检测到账号会话失效」：购买确认/完成/退出确认三个弹窗共用蓝标题条，正是断线重连模板裁切的来源，弹窗之间的过渡帧判为 UNKNOWN 时会话阻塞判定就命中。已改为：商店界面可见且未识别到「返回标题」按钮时不算会话阻塞（没按钮本来也无从处理）；商店内真正的会话失效仍会显示该按钮，照常处理；
- [x] 路线要去商店却反复点 Boss 平台（2026-09-19 调试包 222537）。协议下一节点是商店#30601，画面上唯一识别到的是本区域的 Boss 平台。Boss 平台走的是独立的特殊节点锚点，裁剪框 500×350，与普通节点的 280×350 不是一回事；而一个只有 1 个节点的逻辑列配上 1 个检测，天然满足 FULL_COLUMN，拓扑置信度给到 0.990。有序拓扑覆盖规则因此把「期望商店、识别 Boss」这个冲突当成模板噪声放行，于是照着 Boss 平台的位置点下去，等一个永远不会出现的移动确认弹窗，直到人工滑动才真正点到商店。已规定只要冲突任一侧是 Boss 就不允许任何覆盖：该规则本来是为「连结/遗物」这类外观相近的普通节点图标设计的，Boss 平台是完全不同的地图资产，把它读成商店不是噪声而是相机位置判断错了，必须停下来而不是硬点；
- [x] 遗物优先级可自定义（2026-09-19）。策略设置新增「遗物优先级」分组，可按顺序排列加速/会心/守备/强化/弱体，留空沿用内置顺序。顺序影响两处：主追印记从「当前层数最高的未满印记」改为「排序最高的未满印记」，以及候选打分里按名次加一个小权重。有意不影响的部分：能一次跨到 15 层的选项仍然优先，已满 15 层的印记仍然不选——这两条是实际收益而非偏好；
- [x] 后期变慢的来源（2026-09-19 实测，非角色数量）。把全部调试包按页面统计单帧识别耗时，NODE_SELECTION 均值 3511 ms、p95 7647 ms，其余页面都在 1 秒以内；再按搜索模式拆开：`full` 占节点帧的 51%、均值 6215 ms、约 2468 个窗口，`directed` 1200 ms/548 窗口，`tracked` 206 ms。也就是说成本几乎全在兜底全扫描，而不是角色变多。另外用 8→32 人的角色池直接压测编组规划器：单队 0→109 ms，Boss 三队 1→250 ms（桌面），确实随人数增长但不是瓶颈。已做的优化：同一帧内按矩形缓存节点分类结果。相邻锚点各自扫 ±320 参考像素，比锚点间距还大，同一个矩形会被多个锚点重复提出，此前每次重复都要付一次 2400 采样的完整分类；结果只取决于（帧、矩形、模板集），本帧内全部固定，所以缓存不改变任何判定。实测窗口数 2730→2491、耗时 1151→965 ms，约省 15%。真正的大头仍是「少掉进 full 模式」，这由前面几条节点识别修复来改善；
- [x] 角色池里没有合格一号位时不再拒绝编组（2026-09-19，按要求）。原先「当前角色池中没有满足生存资格的一号位；不再仅按掩护者职阶强行上T」会直接让第一队建议不可用，可这一战还是得打，结果是有队不发、卡在编组页。现在回落到 Boss 后续队位从 2026-09-17 起就在用的无T阵容：照常组出当前最优的五人队上场，并在理由里写明「按无T阵容上场」，`safeBossTeamCount` 记 0，不会把不达生存线的掩护者悄悄当成合格一号位。失败重试路径同样回落，否则第一次的兜底会在重试时又卡住；已失败过的阵容仍然不会被重复提交。这条兜底只在角色池确实一个合格一号位都没有时触发——池子里有T却组不出队（站位未知、人数不足）仍然报各自原本的具体原因；
- [x] 「移动确认弹窗多次取消无效，请手动关闭后继续」，而且一次取消都没点（2026-09-19 反馈，无日志）。取消次数上限 3 次是为了「按不动的弹窗就别一直按」，但那个计数器只在会话出错重走登录时才清零，等于变成了整局上限：一局里前三个误触弹窗即使每次都成功取消，第四个也会直接走到「多次取消无效」分支，连点都不点。已改为弹窗连续消失 3 帧后归还预算——画面清空本身就证明上一次取消生效了；要求连续多帧是为了不把淡出的单帧或反复闪烁当成证明。弹窗真的按不动时的原有行为不变；
- [x] 批次刚开游戏就报 CLIENT_INVALIDATION_FAILED（2026-09-19 调试包 192858）。这一轮开始时游戏还在启动，抓到的每一帧都是竖屏 1080×1920 的桌面，全部锚点 0 分。识别回调在「游戏不在前台」时会直接返回、不读页面，所以预检读到的一直是初始值 UNKNOWN；而 UNKNOWN 的另一层含义是「黎明界页面没认出来」，那是需要失效旧会话的，两种情况分不开、后者赢了。于是终止器在游戏根本没画东西的屏幕上找了 5 秒的「黎明界」触发锚点，超时后整批停在 CLIENT_INVALIDATION_FAILED。已加入「是否真的看到过客户端」这一独立信号：预检前先等客户端到前台（最多 20 秒，冷启动够用），仍然没看到就跳过失效步骤并拉起客户端，交给入口导航——没有客户端在屏幕上，本来就没有旧会话可失效；
- [x] 开局角色可自定义（2026-09-19）。策略设置新增「开局」分组，可按公会改三个必选槽位；每槽可以排多个候选，程序按顺序取第一个能在列表里找到的，全部找不到时照旧停机不随机补人。公会附赠角色由游戏决定，不可编辑。配置存在 `LabyrinthStrategySettings.openingRosters`（公会 ID → 三个槽位的角色 ID 列表），随策略一起持久化；校验不过的方案整条忽略、回落到内置方案，避免半套开局被静默执行。未配置的公会保持内置方案不变；
- [x] 咲恋救济院内置开局改为 女仆（铃莓 1025）／花女仆（铃莓·春日 1308）／水电（咲恋·夏日 1103）（2026-09-19，按要求）。三个昵称都由随包别名表唯一确定。原先该公会第三槽带 6 个备选，现在三槽各一名；`LabyrinthDecisionPolicyTest` 里验证「第2顺位替代」的用例改用仍有备选的王宫骑士团；
- [x] 筛选后剩下的唯一一张卡识别不出来（2026-09-19 调试包 192229）。卡片完整、明亮、没有遮挡，但识别器连槽位都没检测到。网格检测先按像素测出卡片所在的列，再按列间距把它外推成一整排 8 列供几何对齐用；问题是行投影也拿这排外推列当证据，而行得分取的是「最强的两列」的平均。编组页的「用角色名搜索」输入框正好在扫描视口里，横跨好几个外推列，于是它把行投影的峰值抬到 1.0，阈值 0.45 就压过了唯一那张真卡的 0.477，整帧一行都测不出来。已改为只让真正测到的列参与行投影，外推列仍只用于几何。同一帧现在能正确定位并识别出该角色（七七香·万圣节），多卡帧不受影响；
- [x] 有效效果筛选只剩一名角色时的滚动死循环（2026-09-19 调试包 192229）。与之前修过的「零角色」不是同一条：那次靠「未搜索到该角色」提示短路，而这次筛选下确实有一张卡片，只是角色识别器解析不出它，`visibleCharacters` 为空但提示模板不在。于是走到滚动探测，而探测的结束条件是比较滑动前后的头像签名——没有任何头像就没有签名，`unchangedProbes` 永远加不上去。同时这份短列表的滚动条滑块占满整条轨道，`canScroll=false`，边界证据也不可信，每一帧都落到探测分支。结果是每 17 秒把列表倒回顶部一次，一直到会话结束。已给探测加上空视口预算：识别器认为画面已稳定、却连一张卡都解析不出的完整滑动累计 4 次后结束扫描（零个对策角色本身就是答案）。只统计真正完成的滑动，不统计两次滑动之间的重复帧，也不统计未稳定帧，所以加载慢的列表不会被提前截断；
- [x] 已建档的 EX 被报成「没有相关攻略」（2026-09-19 调试包 173956）。攻略表里有流夏的暗影，名字也确实被 OCR 读对了，界面一直显示「正在识别第3只魔物：流夏的暗影」。卡在可信度上：EX 身份要两次独立 OCR 读到同一结果才算可信，而第二次永远不会发生。`RelicOcrScheduler` 有一条公平规则，某个槽位读过一次后，要等所有还没读过的槽位各读一次才轮得到它第二次。挑战页刚出现的几帧里结构检测还没判定是多目标，于是创建了单目标名称槽；判定为多目标之后那条分支再也不跑，那个槽永远停在「没读过」，永久压着详情槽。结果是名字读到了、可信度上不去、超时后报「详情在限定时间内未匹配已知EX」。已把这条优先权改成有时效的：只有仍在被请求的槽位才保留优先权，被放弃超过 1.5 秒的不再阻塞别人；
- [x] 事件后的「无法获得报酬」弹窗仍然不处理（2026-09-18 调试包 201322）。识别其实一直是好的：该帧里确认按钮模板打到 0.985，`labyrinthGenericConfirmDialogRect` 也能正确返回按钮位置。问题在分派：弹窗画在地图之上，页面分类仍然是 NODE_SELECTION，而 `processFrameResult` 对 NODE_SELECTION 帧一律交给节点搜索，只有 `handlePostEntryPage` 里才有按这个按钮的代码。于是弹窗开着，程序在后面滑地图找 普通战斗#40501，滑了 279 次。已在分派处加一条：识别到单按钮确认弹窗时该帧归弹窗处理，模态挡住了一切，背后既点不到也滑不动；
- [x] 节点明明在画面里却识别不到（2026-09-16 调试包 174016）。不是模板不够好，是扫描网格有洞。节点模板的得分峰很窄：在 `node.event.inactive.bottom` 上实测，节点真实位置得分 0.72，偏 8 px 掉到 0.52，偏 28 px 只剩 0.26，而接受线是 0.60——搜索框必须落在节点 ±5 px 内才算得出来。横向偏移表却在 160 到 320 之间一步跨 80 参考像素，局部精修只能走约 ±17 px，于是存在这样的横向条带：节点完整、明亮、没有遮挡地立在画面中央，但每一个被打过分的搜索框都离它 40 px，永远识别不到；地图停在哪个条带由上一次滑动决定，所以现象看起来时有时无。已把偏移表的空洞补到最大 34（补入 136/187/213/267/293 及其镜像）。该帧的识别数从 3 个升到 5 个，两个此前完全看不见的事件节点恢复，原本就识别到的发光遗物节点不受影响，也没有新增误识别。补密只用在没有相机预测的兜底扫描上：定向搜索本来就对准目标且有 800 窗口预算，仍用粗网格，连续两帧未命中会自动落到用密网格的模式。兜底全扫描的窗口数增加约 20%；
- [x] 节点滑丢（2026-09-18）。三处改动：(1) 扫描滑动位移改为按列间距计算（0.8 个列距，1080p 下 432 px），此前是屏宽的 40%（768 px），而实测列间距只有约 513 px（拓扑列 x=533/1057/1559），一次滑动跨 1.4 列，节点可能整个穿过视口不被扫到，扫描器因此报 `geometryReliable=false`、`uncertainSwipes`；(2) 手势末端静止 220 ms 再抬手（`AutomationAction.Swipe.holdMillis`，用 `continueStroke` 续一段 1 px 的尾巴），此前匀速直线走完即抬手，末速 1700 px/s，游戏按甩动处理，地图继续惯性滑行，实际位移不可预测；(3) 局部微调设上限并升级：微调方向是 B/F/F/B 循环，净位移为零，重复再多轮也不可能看到新画面，实机日志里对同一个节点微调了 90 次和 170 次。现在 8 次（两个完整循环）后重新武装整段扫描，最多 3 轮后以明确诊断信息停机；
- [x] App 侧游戏服会话被客户端登录踢掉：客户端每轮返回标题重登都会使 App 的 API 会话失效，下一次读取返回 REJECTED「连接中断。回到标题界面。」。刷开局路径本就会删会话重登重试；「读取当前开局」此前直接落入待验证状态并要求手动「重新验证」，现改为同样删会话、重新登录、重读 top（`labyrinthReadRetriesWithFreshLogin`，上限 3 次）；
- [~] Run-scoped 状态统一 reset：依赖 `stop()` 已有的清理；未单独审计第 13 节清单；
- [ ] Capture lifetime 与 Run lifetime 的自动化回归测试；
- [ ] Android 15 连续多轮实机长测；
- [~] 批量日志与调试包字段：会话重置阶段现已把每帧镜像到网页调试面板（此前该阶段面板停更，因为面板只接单局会话的帧）；终止器每一步写 `LabyrinthReset` 日志。checkpoint 仍未接入调试包导出；
- [x] 连结奖励链未被当作进入凭证（已修复 + 回归测试）；
- [x] 容错期间 pending 转移被页面切换清掉（已修复 + 回归测试）；
- [x] 语义修复误绑单个候选导致点错节点（已修复 + 回归测试）；
- [ ] 节点完成账本（上述三例只是补住已知破口，账本仍未实现）。

---

## 20. 最终架构图

```text
┌─────────────────────────────────────────────┐
│                 用户一次设置                │
│ 公会目标 / 次数 / 难度 / 失败策略            │
└───────────────────┬─────────────────────────┘
                    │
                    v
┌─────────────────────────────────────────────┐
│       MediaProjectionCaptureService         │
│ Android 15：整个 Batch 只授权/创建一次       │
└───────────────────┬─────────────────────────┘
                    │ frames
                    v
┌─────────────────────────────────────────────┐
│             LabyrinthBatchController        │
│                                             │
│  goals / checkpoint / current run / outcome │
└───────┬────────────────┬────────────────────┘
        │                │
        │ start run      │ reroll
        v                v
┌────────────────┐  ┌─────────────────────────┐
│ Recognition    │  │ LabyrinthRerollWorkflow │
│ Session        │  │ + RerollService         │
│                │  │                         │
│ 单局游戏流程    │  │ 服务端刷开局             │
└───────┬────────┘  └────────────┬────────────┘
        │ terminal event          │ success
        └──────────────┬──────────┘
                       v
             ┌───────────────────────┐
             │ GameSessionReset      │
             │ Planner / Executor    │
             │                       │
             │ 使旧客户端会话失效     │
             │ → 标题 → 主页 → 冒险   │
             │ → 黎明界               │
             └───────────┬───────────┘
                         │
                         └──────> 下一 Run
```

---

## 21. 验收标准

“最终全自动”完成的定义不是单局再次通关，而是满足以下全部条件：

1. Android 15 上只进行一次 MediaProjection 授权；
2. 同一批次只创建一次 VirtualDisplay；
3. 用户设置至少两个不同公会、多个目标轮次后可以离开不管；
4. 单局正常通关后自动刷下一开局并回标题；
5. 单局战斗达到失败阈值后自动放弃并刷下一开局；
6. 两条路径都能重新进入标题→主页→冒险→黎明界流程；
7. 下一局不会继承上一局节点、角色、编组或重试状态；
8. 目标次数统计正确且可恢复；
9. 未知状态不会盲点；
10. 连续至少 20 轮测试无人工干预、无 Capture 重授权、无跨局状态污染；
11. 出错后调试包可以明确定位停在哪个 batch / goal / run / stage；
12. 全部目标完成后才主动结束 Capture Service 并给出批次汇总。

达到以上条件后，项目才从“单局黎明界自动化”正式变成“可长期无人值守的黎明界批量执行器”。

