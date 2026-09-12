package com.landosol.toolbox.ui.clanbattle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.clanbattle.ClanBattleUiState
import com.landosol.toolbox.clanbattle.ClanBattleRecognitionSessionState
import com.landosol.toolbox.clanbattle.axis.AxisType

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ClanBattleScreen(
    state: ClanBattleUiState,
    recognitionState: ClanBattleRecognitionSessionState,
    onBack: (() -> Unit)? = null,
    onTextChange: (String) -> Unit,
    onLoadSample: () -> Unit,
    onValidate: () -> Unit,
    onStartRecognition: () -> Unit,
    onStopRecognition: () -> Unit,
    onConfirmPauseFrame: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会战助手") },
                navigationIcon = {
                    onBack?.let { back -> TextButton(onClick = back) { Text("返回") } }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("当前提供轴校验和仅识别预览。真实会战运行入口仍关闭，不会自动点击游戏。")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("仅识别预览", style = MaterialTheme.typography.titleMedium)
                        Text("需要先在权限中心开启屏幕捕获；该模式固定使用 Dry Run，不执行动作。")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onStartRecognition,
                                enabled = !recognitionState.running,
                            ) { Text("开始仅识别") }
                            Button(
                                onClick = onStopRecognition,
                                enabled = recognitionState.running,
                            ) { Text("停止") }
                        }
                        Text("状态：${recognitionState.status.name}")
                        if (recognitionState.paused) Text("会话：已暂停（不会处理新帧）")
                        Text("已处理帧：${recognitionState.frameCount}")
                        recognitionState.axisName?.let { Text("已配置轴：$it") }
                        recognitionState.axisType?.let { type ->
                            Text("轴类型：${if (type == AxisType.SEQUENCE) "顺序轴" else "开关轴"}")
                        }
                        recognitionState.switchRuntime?.let { runtime ->
                            Text(
                                "开关轴节点：${runtime.nodeId ?: "暂无"} / " +
                                    "${runtime.triggerType ?: "--"} / ${runtime.runtimeState ?: "空闲"}",
                            )
                        }
                        recognitionState.switchReason?.let { Text("运行原因：$it") }
                        recognitionState.latestBossUbEvent?.let { event ->
                            val phase = if (event.early) "提前确认" else "完整确认"
                            val duration = event.holdDurationMillis?.let { "，停表 ${it / 1_000.0} 秒" }.orEmpty()
                            Text("最近 Boss UB：${event.heldClockSeconds} 秒，$phase$duration")
                        }
                        if (recognitionState.plannedIntents.isNotEmpty()) {
                            Text("计划动作（Dry Run）：")
                            recognitionState.plannedIntents.forEach { intent -> Text("· $intent") }
                        }
                        Text("Dry Run：只生成动作意图，不执行点击")
                        recognitionState.switchPauseFrameRole?.let { role ->
                            Button(onClick = onConfirmPauseFrame) {
                                Text("确认卡帧（Dry Run）：${role.name}")
                            }
                        }
                        recognitionState.message?.let { Text(it) }
                        recognitionState.lastObservation?.let { observation ->
                            Text("界面：${observation.screenKind.name} / ${"%.2f".format(observation.screenConfidence)}")
                            Text("时钟：${observation.filteredClock?.rawText ?: observation.clock?.rawText ?: "--:--"}")
                            Text(
                                "TP：" + observation.energy?.slots?.values?.joinToString(" / ") {
                                    "${(it.fillRatio * 100).toInt()}%"
                                }.orEmpty().ifBlank { "暂无" },
                            )
                            Text("控件可信：${observation.controls?.trustworthy == true}")
                            observation.menuButton?.let { menu ->
                                Text("菜单锚点：${if (menu.trustworthy) "可信" else "被遮挡"} / ${"%.3f".format(menu.score)}")
                            }
                            Text("动作安全门：${observation.actionSafe}（预览模式仍不会点击）")
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.axisText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("轴文本") },
                    minLines = 12,
                    maxLines = 20,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onLoadSample) { Text("载入安全样例") }
                    Button(onClick = onValidate) { Text("解析并校验") }
                }
            }
            state.axisConfigMessage?.let { message ->
                item { Text(message) }
            }
            if (state.validated) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(if (state.isValid) "校验通过" else "校验未通过", style = MaterialTheme.typography.titleMedium)
                            state.axisName?.let { Text("名称：$it") }
                            state.axisType?.let { type ->
                                Text("类型：${if (type == AxisType.SEQUENCE) "顺序轴" else "开关轴"}")
                            }
                            Text("节点数：${state.eventCount}")
                        }
                    }
                }
                items(state.issues) { issue ->
                    Text(
                        text = issue.line?.let { "第 $it 行：${issue.message}" } ?: issue.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
