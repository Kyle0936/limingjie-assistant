# 事件节点移动与选项识别

## 2026-09-13 修正

- 复用已标定的“选择”按钮模板，增加双选项左右位置。当“触发事件”标题和选择按钮均有强证据时，事件弹层优先于仍可见的背景地图，避免继续搜索、点击地图节点。
- 节点点击后，移动确认弹窗出现前的 UNKNOWN、地图预览或加载帧会在 6 秒窗口内保留待确认节点；确认后的过渡也适用。窗口从实际点击完成时开始计时，不再包含截图识别耗时。
- 移动确认继续要求已有自动节点点击、地图兼容页面和连续稳定的弹窗。点击前采集的旧帧不参与确认按钮稳定性判断。
- 检测到移动确认弹窗时不执行事件 OCR，避免右侧蓝色“确认”被当成事件“选择”。
- 事件选项文字区域的下边界由画面高度的 74% 扩至 78%，包含奖励末行；选择按钮坐标保持原有标定。
- OCR 缓存绑定裁剪区域，5 秒后重新读取；每次请求使用独立编号。离开页面或请求超时后的旧回调不会覆盖、取消新请求。未匹配到当前画面缓存时重新累计稳定帧。

## 回归素材

`app/src/test/resources/labyrinth/` 中的两个 `20260913` PNG 来自本次用户截图。回归检查移动弹窗与事件页面的区分、原尺寸及 1280×720/1920×1080 的确认落点、双选项蓝色按钮证据、完整奖励行的裁剪范围和对应事件目录文本匹配。另覆盖节点过渡保留期限及 OCR 过期回调。

在 `android` 目录运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*LabyrinthEventFlowRegressionTest' --tests '*LabyrinthEventRecognitionTest' --tests '*LabyrinthEntryRecognitionPolicyTest' --tests '*LabyrinthPageRuntimePolicyTest' --tests '*LabyrinthEventChoicePlannerTest' --tests '*LabyrinthEntryPageClassifierTest' :app:assembleDebug
```

这些 JVM 测试验证像素判定、裁剪、目录文本匹配和状态策略。目录文本测试使用截图转录文本，不代表已在设备上验证 ML Kit OCR 或完整游戏操作流程。
