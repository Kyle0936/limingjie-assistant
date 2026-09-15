# Boss 多队：束外残余池搜索（2026-09-15）

## 现象

多队模式对第三层 Boss 冰霜魔狼，24 人角色池按现有门槛有 6 名合格一号位，`safeCapacity=3`，但每轮推荐都是"本轮安全计划1队"，实际只编第一队就开战。调试包 `limingjie-debug-20260915-175902.zip`。

## 根因

`bossMultiTeamSearch` 只在 `rankedBattleFormations` 的 Top120 束内找互不重叠的两队或三队。冰霜魔狼是三目标，评分偏向同一批 AOE/DOT 核心，束内 120 个阵容全部共用这批人，找不到不相交组合，于是错误降级为 `FIRST_ATTEMPT_ONE_TEAM`。

不能靠加宽束解决：同一日志里已有 `Clamp target GC heap from 195MB to 192MB` 和多次 `Waiting for a blocking GC Alloc`，束里每一项都是完整的 `LabyrinthTeamEvaluation`。

## 处理

束内找不到不相交组合时，退到残余池搜索 `residualPoolTeams`：

- 从束里按不同一号位去重，最多取 6 个第一队种子；
- 每个种子固定后，在剩余角色池上重新跑 `bestBattleFormation` 得到第二队，再在剩余池上得到第三队；被排除的失败队签名跳过；
- 只有凑满目标队数的种子参与比较，取总分最高的一组；
- 仍旧先试 3 队再试 2 队，两者都失败才降级到单队。

每个后续队只保留一个评估结果，不扩大束宽，内存开销与原来同量级。第一队不变时的现有行为不受影响：束内本来就能找到不相交组合的情况仍走原路径，结果一致。

## 验证

- 生产数据回归 `production 24 role frost wolf roster plans at least two boss teams`：24 人池、冰霜魔狼、防御印记 4 层、纯物理/法术偏好，断言 `plannedBossTeamCount >= 2`，剩余池能规划出不重叠的第二队，且规划器自身返回的多队成员两两不相交。
- 原有多队测试（安全容量取最小值、两名一号位时降级 2 队、放宽门槛）全部保留。
- 实机第三层 Boss 多队编组尚未用新版本验证。
