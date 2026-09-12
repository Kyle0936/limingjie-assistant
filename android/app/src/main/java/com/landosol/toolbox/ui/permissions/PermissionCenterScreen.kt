package com.landosol.toolbox.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.permissions.PermissionCenterState

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PermissionCenterScreen(
    state: PermissionCenterState,
    onBack: (() -> Unit)? = null,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与权限") },
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
                Text(
                    "无障碍、任务通知和截图授权满足后即可运行。状态及暂停、继续、停止入口位于通知栏，不再需要悬浮窗权限。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                PermissionCard(
                    title = "无障碍服务",
                    enabled = state.accessibilityEnabled && state.accessibilityConnected,
                    statusText = when {
                        !state.accessibilityEnabled -> "未授权"
                        state.accessibilityConnected -> "已授权且已连接"
                        else -> "系统已授权，但服务未连接"
                    },
                    description = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                        "模拟器重启后系统可能保留开关却没有重新绑定服务。请进入系统设置，将本应用的无障碍服务关闭后重新开启。"
                    } else {
                        "用于执行用户明确启动的点击、滑动和返回动作。"
                    },
                    actionLabel = if (state.accessibilityEnabled && !state.accessibilityConnected) {
                        "前往重新连接"
                    } else {
                        "打开系统设置"
                    },
                    onAction = onOpenAccessibilitySettings,
                )
            }
            item {
                PermissionCard(
                    title = "通知",
                    enabled = state.notificationsEnabled,
                    description = "显示任务状态与详细提示；展开通知可暂停、继续或停止。需开启“自动化任务状态与控制”频道。",
                    actionLabel = "开启或设置通知",
                    onAction = onRequestNotifications,
                )
            }
            item {
                PermissionCard(
                    title = "屏幕捕获",
                    enabled = state.captureSessionActive,
                    description = "由 Android 系统弹窗授权，服务限流读取屏幕帧；停止后释放投影和 ImageReader。",
                    actionLabel = if (state.captureSessionActive) "停止屏幕捕获" else "请求截图授权",
                    onAction = if (state.captureSessionActive) onStopCapture else onStartCapture,
                )
            }
            item {
                Text(
                    when {
                        state.canStartVisualAutomation -> "当前可以启动视觉自动化。"
                        state.accessibilityEnabled && !state.accessibilityConnected ->
                            "当前不能启动：无障碍开关虽然已开启，但服务尚未连接。"
                        else -> "当前不能启动视觉自动化，请先完成上面的权限和截图授权。"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    enabled: Boolean,
    statusText: String = if (enabled) "已授权" else "未授权",
    description: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(statusText, style = MaterialTheme.typography.labelMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            actionLabel?.let {
                Button(onClick = onAction) { Text(it) }
            }
        }
    }
}
