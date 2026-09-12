package com.landosol.toolbox.account

data class NormalizedAccountInput(
    val alias: String,
    val loginId: String,
    val password: String,
    val gameUid: String?,
)

sealed interface AccountValidationResult {
    data class Valid(val value: NormalizedAccountInput) : AccountValidationResult
    data class Invalid(val message: String) : AccountValidationResult
}

object AccountInputValidator {
    fun validate(
        alias: String,
        loginId: String,
        password: String,
        gameUid: String,
        passwordRequired: Boolean,
    ): AccountValidationResult {
        val normalizedAlias = alias.trim()
        val normalizedLoginId = loginId.trim()
        val normalizedUid = gameUid.trim().ifEmpty { null }

        return when {
            normalizedAlias.isEmpty() -> AccountValidationResult.Invalid("请输入账号名称")
            normalizedAlias.length > 40 -> AccountValidationResult.Invalid("账号名称不能超过 40 个字符")
            normalizedAlias.any(Char::isISOControl) -> AccountValidationResult.Invalid("账号名称包含不可用字符")
            normalizedLoginId.isEmpty() -> AccountValidationResult.Invalid("请输入登录账号")
            normalizedLoginId.length > 128 -> AccountValidationResult.Invalid("登录账号不能超过 128 个字符")
            normalizedLoginId.any(Char::isISOControl) -> AccountValidationResult.Invalid("登录账号包含不可用字符")
            passwordRequired && password.isEmpty() -> AccountValidationResult.Invalid("请输入密码")
            password.length > 512 -> AccountValidationResult.Invalid("密码长度超出限制")
            normalizedUid != null && normalizedUid.length > 64 -> AccountValidationResult.Invalid("游戏 UID 不能超过 64 个字符")
            normalizedUid?.any(Char::isISOControl) == true -> AccountValidationResult.Invalid("游戏 UID 包含不可用字符")
            else -> AccountValidationResult.Valid(
                NormalizedAccountInput(
                    alias = normalizedAlias,
                    loginId = normalizedLoginId,
                    password = password,
                    gameUid = normalizedUid,
                ),
            )
        }
    }
}
