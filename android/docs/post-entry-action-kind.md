# 阶段二：状态机去文案化（2026-09-16）

## 现状

`LabyrinthEntryRecognitionSession` 的提交逻辑和阶段推进原先靠中文文案匹配来分派：17 处 `label.startsWith("选择角色：")`、`label == "推进最终结算动画"`、`when (label) { "关闭最终分数结果" -> ... }`。改一句 UI 提示词就会静默破坏对应的提交链，且没有任何测试能发现。

## 处理

- 新增 `LabyrinthPostEntryActionKind` 枚举，34 个成员，覆盖角色奖励、事件、遗物、商店、战斗、EX 详情探测、最终结算七组动作。
- 新增 `LabyrinthPostEntryTapPlan(kind, label, rect)` 替代原来的 `Pair<String, EntryPixelRect>`。页面计划表 26 个分支全部改为返回该类型。
- `dispatchPostEntryTap` 增加 `kind` 参数，8 个调用点全部传入。Executed 和 Rejected 分支里的所有条件改为按 `kind` 判断，`when (label)` 改为 `when (kind)`。
- 文案只剩两个用途：覆盖层显示和调试轨迹的 `actionLabel`。

## 验收

- 会话源码里不再存在 `label.startsWith`、`label ==`、`when (label)`。新增源码级单测 `LabyrinthPostEntryActionKindTest` 守住这一条，再有人加回去会直接失败。
- `LabyrinthRoleRewardCommitTest` 改为用随机文案 `random-label-9f3a` 配 `SELECT_ROLE_REWARD` 调用，验证入库不依赖文案。
- 行为不变：每处替换都是同一条件的等价改写，没有合并或拆分分支。

## 未做

- 节点点击、移动确认、编组动作、入口动作四条派发路径不经过 `dispatchPostEntryTap`，它们各自没有文案分派，本阶段不动。
- 审批壳（阶段三）现在可以直接在 `kind` 上声明每个阶段允许的动作集合。
