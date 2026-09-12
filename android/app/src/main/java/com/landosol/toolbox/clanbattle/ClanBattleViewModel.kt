package com.landosol.toolbox.clanbattle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.landosol.toolbox.clanbattle.axis.AxisDocument
import com.landosol.toolbox.clanbattle.axis.AxisParser
import com.landosol.toolbox.clanbattle.axis.AxisType
import com.landosol.toolbox.clanbattle.axis.AxisValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClanBattleAxisIssue(val line: Int?, val message: String)

data class ClanBattleUiState(
    val axisText: String = "",
    val axisName: String? = null,
    val axisType: AxisType? = null,
    val eventCount: Int = 0,
    val issues: List<ClanBattleAxisIssue> = emptyList(),
    val validated: Boolean = false,
    val axisConfigMessage: String? = null,
) {
    val isValid: Boolean get() = validated && issues.isEmpty()
}

class ClanBattleViewModel(
    private val recognitionSession: ClanBattleRecognitionSession? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ClanBattleUiState())
    val uiState: StateFlow<ClanBattleUiState> = _uiState.asStateFlow()
    val recognitionState: StateFlow<ClanBattleRecognitionSessionState> =
        recognitionSession?.state ?: MutableStateFlow(ClanBattleRecognitionSessionState())
    private var lastValidatedAxis: AxisDocument? = null

    fun setAxisText(value: String) {
        lastValidatedAxis = null
        _uiState.update {
            it.copy(
                axisText = value,
                axisName = null,
                axisType = null,
                eventCount = 0,
                issues = emptyList(),
                validated = false,
                axisConfigMessage = null,
            )
        }
        clearConfiguredAxis()
    }

    fun loadSample() = setAxisText(SAMPLE_AXIS)

    fun startRecognition() {
        recognitionSession?.let { session -> viewModelScope.launch { session.start() } }
    }

    fun stopRecognition() {
        recognitionSession?.let { session -> viewModelScope.launch { session.stop() } }
    }

    fun confirmPauseFrame() {
        recognitionSession?.let { session -> viewModelScope.launch { session.confirmPauseFrame() } }
    }

    fun validate() {
        val text = _uiState.value.axisText
        if (text.isBlank()) {
            lastValidatedAxis = null
            clearConfiguredAxis()
            _uiState.update {
                it.copy(
                    validated = true,
                    issues = listOf(ClanBattleAxisIssue(null, "请先粘贴或载入轴文本")),
                    axisConfigMessage = null,
                )
            }
            return
        }
        runCatching { AxisParser.parse(text) }
            .onSuccess { document ->
                val validation = AxisValidator.validate(document)
                lastValidatedAxis = document.takeIf { validation.isValid }
                _uiState.update {
                    it.copy(
                        axisName = document.header["轴名称"].orEmpty().ifBlank { "未命名轴" },
                        axisType = document.type,
                        eventCount = document.events.size + document.switchNodes.size,
                        issues = validation.issues.map { issue -> ClanBattleAxisIssue(issue.line, issue.message) },
                        validated = true,
                        axisConfigMessage = null,
                    )
                }
                lastValidatedAxis?.let(::configureAxis) ?: clearConfiguredAxis()
            }
            .onFailure { error ->
                lastValidatedAxis = null
                clearConfiguredAxis()
                _uiState.update {
                    it.copy(
                        axisName = null,
                        axisType = null,
                        eventCount = 0,
                        issues = listOf(ClanBattleAxisIssue(null, error.message ?: "轴文本无法解析")),
                        validated = true,
                        axisConfigMessage = null,
                    )
                }
            }
    }

    private fun configureAxis(document: AxisDocument) {
        recognitionSession?.let { session ->
            viewModelScope.launch {
                val configured = session.configureAxis(document)
                _uiState.update {
                    it.copy(
                        axisConfigMessage = if (configured) {
                            "已配置到仅识别会话（Dry Run）"
                        } else {
                            "会话运行中，不能替换已配置轴；请先停止仅识别"
                        },
                    )
                }
            }
        }
    }

    private fun clearConfiguredAxis() {
        recognitionSession?.let { session ->
            viewModelScope.launch {
                if (!session.configureAxis(null)) {
                    _uiState.update {
                        it.copy(axisConfigMessage = "会话运行中，旧轴仍锁定；请停止仅识别后再编辑")
                    }
                }
            }
        }
    }

    class Factory(
        private val recognitionSession: ClanBattleRecognitionSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ClanBattleViewModel::class.java))
            return ClanBattleViewModel(recognitionSession) as T
        }
    }

    private companion object {
        val SAMPLE_AXIS = """
            轴类型=顺序
            轴名称=安全短轴样例
            点击间隔=100
            角色1=角色A
            角色2=角色B
            角色3=角色C
            角色4=角色D
            角色5=角色E

            [轴]
            1:16 | 点击=角色4 | 点击=角色5
            1:09 | 卡帧=角色1
            1:05 | UB后=角色5 | 点击=AUTO | 点击=角色4
            0:56 | UB后=BOSS | 点击=角色5 | 点击=角色2
        """.trimIndent()
    }
}
