package com.landosol.toolbox.protocol.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BilibiliNativeLoginCoordinator(
    private val sdkCoordinator: BilibiliLoginCoordinator,
    private val sdkGateway: BilibiliSdkGateway,
    private val sessionStore: SdkSessionStore,
    private val gameGateway: BilibiliGameGateway,
    private val gameSessionRegistry: GameSessionRegistry,
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<Long, PendingCaptcha>()

    suspend fun start(material: AccountLoginMaterial): NativeLoginResult = mutex.withLock {
        pending.remove(material.accountId)
        sdkCoordinator.cancel(material.accountId)
        if (material.server == GameServer.CN_CHANNEL) {
            val directSession = SdkSession(
                uid = material.loginId,
                accessKey = material.password,
                server = GameServer.CN_CHANNEL,
            )
            return@withLock loginGame(material, directSession)
        }
        val cached = sessionStore.read(material.credentialKey)
        if (cached != null) {
            when (val result = gameGateway.loginAndLoadProfile(cached, material.loginId)) {
                is GameLoginResult.SessionRejected -> {
                    sessionStore.delete(material.credentialKey)
                    loginSdk(material)
                }
                else -> handleGameResult(material, cached, result)
            }
        } else {
            loginSdk(material)
        }
    }

    suspend fun completeCaptcha(accountId: Long, proof: CaptchaProof): NativeLoginResult = mutex.withLock {
        val attempt = pending[accountId]
            ?: return@withLock NativeLoginResult.Failure(LoginFailureKind.InvalidState, "登录验证已失效，请重新开始")
        if (proof.validate.isBlank() || proof.validate.length > MAX_CAPTCHA_TOKEN_LENGTH) {
            return@withLock NativeLoginResult.Failure(LoginFailureKind.InvalidState, "验证码结果无效")
        }
        when (attempt) {
            is PendingCaptcha.Sdk -> {
                when (val result = sdkCoordinator.completeCaptcha(accountId, proof)) {
                    is AccountLoginResult.Success -> {
                        val session = sessionStore.read(attempt.material.credentialKey)
                            ?: return@withLock failure(LoginFailureKind.Protocol, "SDK 会话保存失败", attempt.material)
                        pending.remove(accountId)
                        loginGame(attempt.material, session)
                    }
                    is AccountLoginResult.CaptchaRequired -> {
                        pending.remove(accountId)
                        failure(LoginFailureKind.Rejected, "SDK 验证未通过，请重新开始", attempt.material)
                    }
                    is AccountLoginResult.Failure -> {
                        pending.remove(accountId)
                        NativeLoginResult.Failure(result.kind, result.message)
                    }
                }
            }
            is PendingCaptcha.GameRisk -> {
                val solution = CaptchaSolution(
                    challenge = attempt.challenge.challenge,
                    gtUserId = attempt.challenge.gtUserId,
                    validate = proof.validate,
                )
                pending.remove(accountId)
                loginGame(attempt.material, attempt.session, solution)
            }
        }
    }

    suspend fun cancel(accountId: Long) {
        mutex.withLock {
            pending.remove(accountId)
            sdkCoordinator.cancel(accountId)
        }
    }

    private suspend fun loginSdk(material: AccountLoginMaterial): NativeLoginResult {
        return when (val result = sdkCoordinator.start(material)) {
            is AccountLoginResult.Success -> {
                val session = sessionStore.read(material.credentialKey)
                    ?: return failure(LoginFailureKind.Protocol, "SDK 会话保存失败", material)
                loginGame(material, session)
            }
            is AccountLoginResult.CaptchaRequired -> {
                pending[material.accountId] = PendingCaptcha.Sdk(material, result.challenge)
                NativeLoginResult.CaptchaRequired(result.challenge)
            }
            is AccountLoginResult.Failure -> NativeLoginResult.Failure(result.kind, result.message)
        }
    }

    private suspend fun loginGame(
        material: AccountLoginMaterial,
        session: SdkSession,
        captcha: CaptchaSolution? = null,
    ): NativeLoginResult = handleGameResult(
        material,
        session,
        gameGateway.loginAndLoadProfile(session, material.loginId, captcha),
    )

    private suspend fun handleGameResult(
        material: AccountLoginMaterial,
        session: SdkSession,
        result: GameLoginResult,
    ): NativeLoginResult = when (result) {
        is GameLoginResult.Success -> {
            gameSessionRegistry.save(material.accountId, result.session)
            NativeLoginResult.Success(result.profile)
        }
        GameLoginResult.RiskRequired -> requestGameCaptcha(material, session)
        is GameLoginResult.SessionRejected -> failure(LoginFailureKind.Rejected, result.message, material)
        is GameLoginResult.Rejected -> failure(LoginFailureKind.Rejected, result.message, material)
        is GameLoginResult.Maintenance -> failure(LoginFailureKind.Rejected, result.message, material)
        is GameLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, result.message, material)
        is GameLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, result.message, material)
    }

    private suspend fun requestGameCaptcha(
        material: AccountLoginMaterial,
        session: SdkSession,
    ): NativeLoginResult = if (material.server == GameServer.CN_CHANNEL) {
        failure(
            LoginFailureKind.Rejected,
            "渠道服登录触发游戏风控；请先在渠道客户端重新登录，再重新提取 login_id/token 后重试",
            material,
        )
    } else when (val captcha = sdkGateway.startCaptcha()) {
        is SdkCaptchaResult.Ready -> {
            pending[material.accountId] = PendingCaptcha.GameRisk(material, session, captcha.challenge)
            NativeLoginResult.CaptchaRequired(captcha.challenge)
        }
        is SdkCaptchaResult.NetworkFailure -> failure(LoginFailureKind.Network, captcha.message, material)
        is SdkCaptchaResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, captcha.message, material)
        is SdkCaptchaResult.Rejected -> failure(LoginFailureKind.Rejected, captcha.message, material)
    }

    private fun failure(
        kind: LoginFailureKind,
        message: String,
        material: AccountLoginMaterial,
    ): NativeLoginResult.Failure = NativeLoginResult.Failure(
        kind,
        message
            .replace(material.loginId, "<redacted>")
            .replace(material.password, "<redacted>")
            .ifBlank { "登录失败" }
            .take(MAX_MESSAGE_LENGTH),
    )

    private sealed interface PendingCaptcha {
        val material: AccountLoginMaterial
        val challenge: CaptchaChallenge

        data class Sdk(
            override val material: AccountLoginMaterial,
            override val challenge: CaptchaChallenge,
        ) : PendingCaptcha

        data class GameRisk(
            override val material: AccountLoginMaterial,
            val session: SdkSession,
            override val challenge: CaptchaChallenge,
        ) : PendingCaptcha
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 200
        const val MAX_CAPTCHA_TOKEN_LENGTH = 4096
    }
}
