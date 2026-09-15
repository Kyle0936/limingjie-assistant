package com.landosol.toolbox.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditorState(
    val id: Long? = null,
    val alias: String = "",
    val loginId: String = "",
    val password: String = "",
    val gameUid: String = "",
    val server: GameServer = GameServer.CN_BILIBILI,
) {
    val isEditing: Boolean get() = id != null
}

data class AccountUiState(
    val accounts: List<AccountListItem> = emptyList(),
    val editor: AccountEditorState? = null,
    val deleteCandidate: AccountListItem? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

data class AccountCaptchaState(
    val accountId: Long,
    val accountAlias: String,
    val challenge: CaptchaChallenge,
    val error: String? = null,
)

private data class AccountChromeState(
    val editor: AccountEditorState? = null,
    val deleteCandidate: AccountListItem? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

class AccountViewModel(
    private val repository: AccountRepository,
) : ViewModel() {
    private val chrome = MutableStateFlow(AccountChromeState())

    val uiState = combine(repository.observeAccounts(), chrome) { accounts, chromeState ->
        AccountUiState(
            accounts = accounts,
            editor = chromeState.editor,
            deleteCandidate = chromeState.deleteCandidate,
            isWorking = chromeState.isWorking,
            message = chromeState.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AccountUiState(),
    )

    fun openNewAccount() {
        chrome.update { it.copy(editor = AccountEditorState(), message = null) }
    }

    fun openEditAccount(id: Long) {
        runOperation(closeEditorOnSuccess = false) {
            val value = repository.loadEditor(id)
            chrome.update {
                it.copy(
                    editor = AccountEditorState(
                        id = value.id,
                        alias = value.alias,
                        loginId = value.loginId,
                        password = "",
                        gameUid = value.gameUid,
                        server = value.server,
                    ),
                )
            }
        }
    }

    fun updateEditor(transform: (AccountEditorState) -> AccountEditorState) {
        chrome.update { current -> current.copy(editor = current.editor?.let(transform), message = null) }
    }

    fun dismissEditor() {
        if (!chrome.value.isWorking) chrome.update { it.copy(editor = null, message = null) }
    }

    fun saveEditor() {
        val editor = chrome.value.editor ?: return
        when (
            val validation = AccountInputValidator.validate(
                alias = editor.alias,
                loginId = editor.loginId,
                password = editor.password,
                gameUid = editor.gameUid,
                passwordRequired = !editor.isEditing,
                server = editor.server,
            )
        ) {
            is AccountValidationResult.Invalid -> chrome.update { it.copy(message = validation.message) }
            is AccountValidationResult.Valid -> runOperation(closeEditorOnSuccess = true) {
                if (editor.id == null) {
                    repository.create(validation.value)
                } else {
                    repository.update(editor.id, validation.value)
                }
            }
        }
    }

    fun selectAccount(id: Long) = runOperation { repository.select(id) }

    fun requestDelete(account: AccountListItem) {
        chrome.update { it.copy(deleteCandidate = account, message = null) }
    }

    fun dismissDelete() {
        if (!chrome.value.isWorking) chrome.update { it.copy(deleteCandidate = null) }
    }

    fun confirmDelete() {
        val account = chrome.value.deleteCandidate ?: return
        runOperation {
            repository.delete(account.id)
            chrome.update { it.copy(deleteCandidate = null) }
        }
    }

    fun dismissMessage() {
        chrome.update { it.copy(message = null) }
    }

    private fun runOperation(
        closeEditorOnSuccess: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        if (chrome.value.isWorking) return
        viewModelScope.launch {
            chrome.update { it.copy(isWorking = true, message = null) }
            runCatching { operation() }
                .onSuccess {
                    chrome.update { current ->
                        current.copy(
                            editor = if (closeEditorOnSuccess) null else current.editor,
                            isWorking = false,
                        )
                    }
                }
                .onFailure {
                    chrome.update { current ->
                        current.copy(
                            isWorking = false,
                            message = safeMessage(it),
                        )
                    }
                }
        }
    }

    private fun safeMessage(failure: Throwable): String = when {
        failure is IllegalArgumentException && !failure.message.isNullOrBlank() -> failure.message.orEmpty()
        failure.message?.contains("UNIQUE", ignoreCase = true) == true -> "同一服务器下已存在这个账号名称"
        else -> "操作失败，请稍后重试"
    }

    class Factory(
        private val repository: AccountRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AccountViewModel::class.java))
            return AccountViewModel(repository) as T
        }
    }
}
