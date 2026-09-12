package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.MainDispatcherRule
import com.landosol.toolbox.automation.AutomationSessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClanBattleViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sample can be loaded and validated without starting automation`() {
        val viewModel = ClanBattleViewModel()

        viewModel.loadSample()
        viewModel.validate()

        assertTrue(viewModel.uiState.value.isValid)
        assertTrue(viewModel.uiState.value.eventCount > 0)
    }

    @Test
    fun `invalid axis reports parse issue`() {
        val viewModel = ClanBattleViewModel()

        viewModel.setAxisText("轴类型=顺序\n[轴]\n0:99 | 点击=角色1")
        viewModel.validate()

        assertFalse(viewModel.uiState.value.isValid)
        assertTrue(viewModel.uiState.value.issues.isNotEmpty())
    }

    @Test
    fun `valid switch axis is configured into recognition session`() = runTest(mainDispatcherRule.dispatcher) {
        val session = ClanBattleRecognitionSession(AutomationSessionManager())
        val viewModel = ClanBattleViewModel(session)

        viewModel.setAxisText(SWITCH_AXIS)
        viewModel.validate()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isValid)
        assertEquals("开关测试轴", session.state.value.axisName)
        assertEquals("已配置到仅识别会话（Dry Run）", viewModel.uiState.value.axisConfigMessage)
    }

    @Test
    fun `editing text clears previously configured axis`() = runTest(mainDispatcherRule.dispatcher) {
        val session = ClanBattleRecognitionSession(AutomationSessionManager())
        val viewModel = ClanBattleViewModel(session)
        viewModel.setAxisText(SWITCH_AXIS)
        viewModel.validate()
        advanceUntilIdle()

        viewModel.setAxisText("轴类型=开关")
        advanceUntilIdle()

        assertNull(session.state.value.axisName)
        assertFalse(viewModel.uiState.value.validated)
    }

    private companion object {
        val SWITCH_AXIS = """
            轴类型=开关
            轴名称=开关测试轴
            [轴开局] | AUTO=开 | SET=开,关,开,关,开
            1:20 | AUTO=关 | SET=关,关,开,开,开
        """.trimIndent()
    }
}
