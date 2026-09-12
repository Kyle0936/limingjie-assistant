# 连结印记三选一模板

本目录只存连结节点三选一的页级锚点和五种属性徽记模板：

- `link_choice_title.png`、`link_choice_instruction.png`、`link_choice_remaining.png`：识别“获得道具的机会 / 请选择要获得的连结印记”页面。
- `element_fire.png`、`element_dark.png`、`element_light.png`、`element_wind.png`、`element_water.png`：卡片右下角属性徽记。

运行时以 1920×1080 标准画布的相对位置映射到当前截图，属性识别读取徽记的色相和形状区域，不读取卡片标题 OCR。默认优先级为：火 > 暗 > 光 > 风 > 水。

模板来源为项目所有者提供的通关视频及当前模拟器只读截图；只读识别模式不会点击三选一按钮。
