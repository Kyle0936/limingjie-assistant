package com.landosol.toolbox.protocol.bilibili

import com.landosol.toolbox.security.InMemorySdkSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliNativeLoginCoordinatorTest {
    private val material = AccountLoginMaterial(7L, "credential-key", "login-id", "password")

    @Test
    fun `expired cached SDK session is refreshed once`() = runTest {
        val store = InMemorySdkSessionStore().apply {
            save(material.credentialKey, SdkSession("old-uid", "old-access"))
        }
        val sdkGateway = FakeSdkGateway(
            loginResults = ArrayDeque(listOf(SdkLoginResult.Success(SdkSession("new-uid", "new-access")))),
        )
        val gameGateway = FakeGameGateway(
            results = ArrayDeque(
                listOf(
                    GameLoginResult.SessionRejected("会话已过期"),
                    success(123L, "玩家", 10),
                ),
            ),
        )
        val coordinator = BilibiliNativeLoginCoordinator(
            BilibiliLoginCoordinator(sdkGateway, store),
            sdkGateway,
            store,
            gameGateway,
            InMemoryGameSessionRegistry(),
        )

        val result = coordinator.start(material)

        assertTrue(result is NativeLoginResult.Success)
        assertEquals("new-uid", store.read(material.credentialKey)?.uid)
        assertEquals(1, sdkGateway.loginCount)
        assertEquals(2, gameGateway.loginCount)
    }

    @Test
    fun `game risk uses human captcha proof and resumes login`() = runTest {
        val store = InMemorySdkSessionStore().apply {
            save(material.credentialKey, SdkSession("uid", "access"))
        }
        val challenge = CaptchaChallenge("gt", "challenge", "gt-user", "1")
        val sdkGateway = FakeSdkGateway(
            captchaResults = ArrayDeque(listOf(SdkCaptchaResult.Ready(challenge))),
        )
        val gameGateway = FakeGameGateway(
            results = ArrayDeque(
                listOf(
                    GameLoginResult.RiskRequired,
                    success(456L, "验证玩家", 20),
                ),
            ),
        )
        val coordinator = BilibiliNativeLoginCoordinator(
            BilibiliLoginCoordinator(sdkGateway, store),
            sdkGateway,
            store,
            gameGateway,
            InMemoryGameSessionRegistry(),
        )

        val first = coordinator.start(material)
        val second = coordinator.completeCaptcha(material.accountId, CaptchaProof("human-validate"))

        assertEquals(NativeLoginResult.CaptchaRequired(challenge), first)
        assertTrue(second is NativeLoginResult.Success)
        assertEquals("human-validate", gameGateway.lastCaptcha?.validate)
        assertEquals("challenge", gameGateway.lastCaptcha?.challenge)
    }

    @Test
    fun `channel account bypasses bilibili sdk and uses stored token directly`() = runTest {
        val channelMaterial = AccountLoginMaterial(
            accountId = 8L,
            credentialKey = "channel-key",
            loginId = "1234567890123456789012",
            password = "channel-access-key",
            server = GameServer.CN_CHANNEL,
        )
        val store = InMemorySdkSessionStore()
        val sdkGateway = FakeSdkGateway()
        val gameGateway = FakeGameGateway(
            results = ArrayDeque(listOf(success(789L, "渠道玩家", 30))),
        )
        val coordinator = BilibiliNativeLoginCoordinator(
            BilibiliLoginCoordinator(sdkGateway, store),
            sdkGateway,
            store,
            gameGateway,
            InMemoryGameSessionRegistry(),
        )

        val result = coordinator.start(channelMaterial)

        assertTrue(result is NativeLoginResult.Success)
        assertEquals(0, sdkGateway.loginCount)
        assertEquals(1, gameGateway.loginCount)
        assertEquals("1234567890123456789012", gameGateway.lastSession?.uid)
        assertEquals("channel-access-key", gameGateway.lastSession?.accessKey)
        assertEquals(GameServer.CN_CHANNEL, gameGateway.lastSession?.server)
    }

    private class FakeSdkGateway(
        private val loginResults: ArrayDeque<SdkLoginResult> = ArrayDeque(),
        private val captchaResults: ArrayDeque<SdkCaptchaResult> = ArrayDeque(),
    ) : BilibiliSdkGateway {
        var loginCount = 0

        override suspend fun login(credentials: SdkLoginCredentials, captcha: CaptchaSolution?): SdkLoginResult {
            loginCount++
            return loginResults.removeFirstOrNull() ?: SdkLoginResult.Rejected(1, "unexpected SDK login")
        }

        override suspend fun startCaptcha(): SdkCaptchaResult =
            captchaResults.removeFirstOrNull() ?: SdkCaptchaResult.Rejected(1, "unexpected captcha")
    }

    private class FakeGameGateway(
        private val results: ArrayDeque<GameLoginResult>,
    ) : BilibiliGameGateway {
        var loginCount = 0
        var lastCaptcha: CaptchaSolution? = null
        var lastSession: SdkSession? = null

        override suspend fun loginAndLoadProfile(
            sdkSession: SdkSession,
            deviceSeed: String,
            captcha: CaptchaSolution?,
        ): GameLoginResult {
            loginCount++
            lastCaptcha = captcha
            lastSession = sdkSession
            return results.removeFirst()
        }
    }

    private companion object {
        fun success(viewerId: Long, name: String, level: Int): GameLoginResult.Success {
            val profile = GameAccountProfile(viewerId, name, level, "11.4.0")
            return GameLoginResult.Success(profile, FakeGameSession(profile))
        }
    }

    private class FakeGameSession(
        override val profile: GameAccountProfile,
    ) : BilibiliGameSession {
        override suspend fun request(
            path: String,
            fields: LinkedHashMap<String, Any?>,
        ): GameSessionResult = GameSessionResult.ProtocolFailure("not used")
    }
}
