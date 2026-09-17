package com.landosol.toolbox.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.account.AccountEditorState
import com.landosol.toolbox.account.AccountListItem
import com.landosol.toolbox.account.AccountUiState

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountScreen(
    state: AccountUiState,
    onBack: (() -> Unit)? = null,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onRequestDelete: (AccountListItem) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onEditorChange: ((AccountEditorState) -> AccountEditorState) -> Unit,
    onDismissEditor: () -> Unit,
    onSaveEditor: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号库") },
                navigationIcon = {
                    onBack?.let { back -> TextButton(onClick = back) { Text("返回") } }
                },
                actions = { TextButton(onClick = onAdd, enabled = !state.isWorking) { Text("新增") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "账号与密码使用 Android Keystore 加密，不写入 Room。选择当前账号后，黎明界功能会在需要时自动登录。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.message?.takeIf {
                state.editor == null && state.deleteCandidate == null
            }?.let { message ->
                item {
                    MessageCard(message = message, onDismiss = onDismissMessage)
                }
            }
            if (state.accounts.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("还没有账号")
                        Button(onClick = onAdd) { Text("新增账号") }
                    }
                }
            } else {
                items(state.accounts, key = { it.id }) { account ->
                    AccountCard(
                        account = account,
                        enabled = !state.isWorking,
                        onEdit = { onEdit(account.id) },
                        onSelect = { onSelect(account.id) },
                        onDelete = { onRequestDelete(account) },
                    )
                }
            }
            if (state.isWorking && state.editor == null && state.deleteCandidate == null) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        AccountEditorDialog(
            editor = editor,
            isWorking = state.isWorking,
            message = state.message,
            onChange = onEditorChange,
            onDismiss = onDismissEditor,
            onSave = onSaveEditor,
        )
    }

    state.deleteCandidate?.let { account ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除“${account.alias}”吗？")
                    Text("账号元数据和本地加密凭据都会删除，此操作不可撤销。")
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.isWorking) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete, enabled = !state.isWorking) { Text("取消") }
            },
        )
    }

}

@Composable
private fun AccountCard(
    account: AccountListItem,
    enabled: Boolean,
    onEdit: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(account.alias, style = MaterialTheme.typography.titleMedium)
                    Text(account.serverName, style = MaterialTheme.typography.bodyMedium)
                }
                if (account.isSelected) {
                    Text("当前账号", color = MaterialTheme.colorScheme.primary)
                }
            }
            account.gameUid?.let { Text("游戏 UID：$it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!account.isSelected) {
                    TextButton(onClick = onSelect, enabled = enabled) { Text("设为当前") }
                }
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
            }
        }
    }
}

@Composable
private fun AccountEditorDialog(
    editor: AccountEditorState,
    isWorking: Boolean,
    message: String?,
    onChange: ((AccountEditorState) -> AccountEditorState) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isEditing) "编辑账号" else "新增账号") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = "国服 Bilibili",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("服务器") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.alias,
                    onValueChange = { value -> onChange { it.copy(alias = value) } },
                    label = { Text("账号名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.loginId,
                    onValueChange = { value -> onChange { it.copy(loginId = value) } },
                    label = { Text("登录账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.password,
                    onValueChange = { value -> onChange { it.copy(password = value) } },
                    label = { Text(if (editor.isEditing) "新密码（留空则不修改）" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.gameUid,
                    onValueChange = { value -> onChange { it.copy(gameUid = value) } },
                    label = { Text("游戏 UID（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (isWorking) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isWorking) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") }
        },
    )
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
