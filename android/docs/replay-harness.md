# 回放基础与帧级追踪（阶段零，2026-09-14）

对应《黎明界_审批壳_识别与配队优化实施方案_修订版》阶段零。目标是让后续每个阶段都能用同一份历史数据对比前后行为，而不是重新打游戏。本阶段不改变任何自动化决策。

## 历史记录新增字段

调试面板的 `state/history.ndjson` 每条记录在原有字段之上追加：

| 字段 | 含义 |
|---|---|
| `nodeSearchWindowCount` | 节点分类器本帧实际评分的候选窗口数；未跑节点扫描时为 0 |
| `archivedFrame` | 逐帧归档开启时，本帧在 `frames/` 下的文件名；否则为 null |
| `trace.expectedPages` | 当前阶段允许的页面集合；审批壳落地前恒为全集 |
| `trace.actionLabel` | 本帧派发的动作文案；无则 null |
| `trace.actionRejectReason` | 本帧动作被扣住的原因（稳定帧不足、冷却中、无计划、目标越界等）；无则 null |
| `trace.nodeTargetBlockId` / `nodeTargetColumn` | 路线当前目标节点及其逻辑列 |
| `trace.nodeTrackerReliable` / `nodeTrackerWorldLeft` | 视口跟踪器本帧的几何可靠性与拟合偏移 |
| `trace.nodeTargetExpectedX` | 跟踪器预测的目标列屏幕 x |

`trace` 只在 `LabyrinthEntryRecognitionSessionState.frameTrace` 上累积，每帧开头重置。任何决策代码都不读取它。

派发路径的追踪钩子覆盖：入口决策器、路线阶段点击、节点点击、移动确认、编组动作、有效效果扫描、会话恢复。拒绝原因目前只在路线阶段点击与节点点击两条路径上记录，其余路径的拒绝理由待阶段三统一到审批表时补齐。

## 逐帧归档

面板顶栏新增「逐帧归档」开关，默认关闭。开启后每个识别帧写一张缩放到 960 宽、质量 60 的 JPEG 到应用缓存目录 `labyrinth-frame-archive/`，按序号加帧号命名，与历史记录同样保留最近 600 张。下载日志 ZIP 时这些图片进入 `frames/`，历史记录里的 `archivedFrame` 指向它们。每次重新开启会清空旧归档，避免一个 ZIP 混入两段会话。

接口：`/api/archive` 查询、`/api/archive/on`、`/api/archive/off`。

## 离线回放

`app/src/test/.../labyrinth/replay/LabyrinthReplayHarness.kt` 用与 App 相同的 `LabyrinthEntryFrameProcessor`（入口锚点加节点模板，从 assets 目录读取）重跑存储帧，不接任何动作执行器。

- `replayScreenshots(files)`：对一组截图逐帧识别。
- `replayBundle(dir)`：读取解压后的调试包，按 `archivedFrame` 找到 `frames/` 里的图片重跑，并与记录的页面比较。
- `summarize(frames)`：输出总帧数、未知帧数、页面不一致数、平均与 p95 耗时、节点搜索模式分布、窗口数分布。

`LabyrinthReplayHarnessTest` 默认对 `test/resources/labyrinth/` 下的 16 张 fixture 跑一遍并打印统计；设置环境变量 `LABYRINTH_REPLAY_BUNDLE` 指向一个开启归档后下载并解压的调试包，可以回放一整局。

当前 fixture 基线（本机，JVM 单线程）：

```text
totalFrames=15  unknownFrames=6  avgRecognitionMs=150  p95RecognitionMs=1071
nodeSearchModes={none=13, full=2}
nodeSearchWindows: n=2 min=2266 p50=2742 max=2742
```

两张节点页 fixture 各评分 2266 与 2742 个窗口。阶段一的目标是把这个数字压到几百。

## 兜底坐标表

`LabyrinthFallbackTaps.kt` 把路线阶段散落的约十处硬编码兜底点击收拢成 `LabyrinthFallbackTap` 枚举，并用 `LABYRINTH_FALLBACK_TAP_POLICIES` 声明每个页面允许哪些兜底点、该点是否落在某个已声明锚点的几何内。行为未变，只是命名。

单元测试断言每个标注了锚点的兜底点在 1920x1080、2400x1080、1600x720 三种尺寸下都落在锚点矩形内。核对时发现两处历史不一致，已在表里标为无锚点并留注释，留给审批壳决定改点还是改锚：

- 获得道具页兜底点 y=870，而关闭按钮锚点声明在 y 895 到 1035。
- 事件动画页兜底点是通用推进点 y=780，而跳过按钮锚点在 y 850 到 970。

## 两处小修

- 未配置路线时，入口决策器在通关结算链任一页面报告完成，会话不再直接停机，改为保持当前页并提示「未配置路线，保持在当前页不自动点击」。
- 删除零引用的 `LabyrinthScreenClassifier` 与 `LabyrinthScreenStage`；仍在用的 `LabyrinthAnchorScores` 移到独立文件。

## 验证

单元测试 829 项通过。逐帧归档与面板开关需要实机验证：开启后跑一段路线，下载 ZIP，确认 `frames/` 非空且 `archivedFrame` 与文件名对应，再用 `LABYRINTH_REPLAY_BUNDLE` 回放。
