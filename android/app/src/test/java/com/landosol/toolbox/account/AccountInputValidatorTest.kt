package com.landosol.toolbox.account

import com.landosol.toolbox.protocol.bilibili.GameServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountInputValidatorTest {
    @Test
    fun `new account can switch server while existing account stays on original server`() {
        val fresh = AccountEditorState()
        assertEquals(GameServer.CN_CHANNEL, fresh.selectServer(GameServer.CN_CHANNEL).server)

        val existing = AccountEditorState(id = 1L, server = GameServer.CN_BILIBILI)
        assertEquals(GameServer.CN_BILIBILI, existing.selectServer(GameServer.CN_CHANNEL).server)
    }

    @Test
    fun `normalizes valid account input`() {
        val result = AccountInputValidator.validate(
            alias = "  主账号  ",
            loginId = "  test@example.com ",
            password = "secret",
            gameUid = " 123456 ",
            passwordRequired = true,
        )

        assertTrue(result is AccountValidationResult.Valid)
        result as AccountValidationResult.Valid
        assertEquals("主账号", result.value.alias)
        assertEquals("test@example.com", result.value.loginId)
        assertEquals("123456", result.value.gameUid)
    }

    @Test
    fun `requires password for a new account`() {
        val result = AccountInputValidator.validate(
            alias = "账号",
            loginId = "tester",
            password = "",
            gameUid = "",
            passwordRequired = true,
        )

        assertEquals(AccountValidationResult.Invalid("请输入密码"), result)
    }

    @Test
    fun `sql control text remains ordinary bound data`() {
        val result = AccountInputValidator.validate(
            alias = "'; DROP TABLE accounts; --",
            loginId = "tester",
            password = "secret",
            gameUid = "",
            passwordRequired = true,
        )

        assertTrue(result is AccountValidationResult.Valid)
    }
}
