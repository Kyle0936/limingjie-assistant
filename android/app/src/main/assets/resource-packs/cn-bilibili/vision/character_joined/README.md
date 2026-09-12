# 角色加入通用页面

页面本身继续使用通用的“角色加入”标题、说明和关闭按钮模板。

Production 的角色身份识别已迁移到 `icons.json` 全角色头像模板和共享
`LabyrinthCharacterIconMatcher`。当前页面只提供角色头像 ROI；识别结果通过统一的
confidence + rival margin 安全门槛后才允许写入本局角色池。

以下角色名称模板仅作为 **legacy regression fixture** 保留，不再承担 production identity：

- `character_1209_name.png`：伊莉亚(新年)
- `character_1257_name.png`：花凛(炼金术师)
- `character_1139_name.png`：纺希(万圣节)

因此角色加入页不再只支持这三个旧样本。头像识别不可信时不会猜测角色；关闭动作仍由通用
关闭按钮锚点控制，角色池还会由稳定的战斗编组页做 append-only 自愈补全。
