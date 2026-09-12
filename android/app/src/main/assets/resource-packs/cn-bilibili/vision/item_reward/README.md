# 获得道具界面模板

用于识别连结节点的属性角色印记三选一完成后出现的“获得道具”弹窗。

- `item_reward_title.png`：弹窗标题“获得道具”
- `item_reward_instruction.png`：弹窗说明“获得了以下道具。”
- `item_reward_close.png`：底部“关闭”按钮

运行时必须同时命中标题、说明和关闭按钮，才会将页面分类为 `ITEM_REWARD`。自动化模式只在该页面执行一次关闭按钮动作，识别模式不会点击。
