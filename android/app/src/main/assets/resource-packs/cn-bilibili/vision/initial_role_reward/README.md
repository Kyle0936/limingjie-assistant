# “角色加入奖励”三选一模板

这组模板对应黎明界开局的另一种初始角色选择页面，来自模拟器现场截图：

- 截图：`通关视频/分析输出/ADB只读回归/当前识别状态_现场.png`
- 页面标题：`角色加入奖励！`
- 页面提示：`邀请选择的角色成为伙伴。`
- 当前状态：`还剩1次`，下方有三个`选择`按钮

| 文件 | 用途 | 原图裁剪区域（x,y,w,h） |
| --- | --- | --- |
| `initial_role_reward_title.png` | 页面标题 | 680,35,580,120 |
| `initial_role_reward_instruction.png` | 页面说明 | 680,150,570,75 |
| `initial_role_reward_select_button.png` | 三个候选角色共用的选择按钮 | 260,810,290,155 |
| `initial_role_reward_remaining_once.png` | `还剩1次`示例，仅作辅助素材 | 840,215,240,60 |

角色头像和角色名称不作为页面锚点，因为三名候选角色会变化。识别使用标题和页面说明作为主锚点，选择按钮作为辅助锚点。

## 角色身份识别

Production 已不再依赖少量固定角色名称截图判断三名候选身份。当前统一使用
`icons.json` 中的全角色头像模板和共享 `LabyrinthCharacterIconMatcher`，页面本身只负责
提供左/中/右三个候选头像 ROI；可信判定使用绝对匹配分与其他角色 rival margin。

下面这些角色名称模板仅保留为 **legacy regression fixture**，用于历史截图/回归诊断，
不再决定 production 的角色 identity：

- `characters/character_1335_name.png`：咲恋(新年)，角色 ID `1335`
- `characters/character_1233_name.png`：涅娅，角色 ID `1233`
- `characters/character_1256_name.png`：碧卡拉，角色 ID `1256`

因此真实随机三选一不再受这三个样本覆盖范围限制。若全角色头像 matcher 未达到可信条件，
仍会保持等待，不根据“最相似角色”盲选。
