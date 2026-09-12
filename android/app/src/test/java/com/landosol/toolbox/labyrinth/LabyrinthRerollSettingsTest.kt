package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.account.AccountCaptchaState
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRerollSettingsTest {
    @Test
    fun `new defaults opt into until found and explicit retreat confirmation workflow`() {
        val defaults = LabyrinthRerollSettings()
        assertTrue(defaults.rerollUntilFound)
        assertTrue(defaults.retireExisting)
        assertNull(defaults.validationError())
        assertEquals(5, LabyrinthRerollOptions.guilds.size)
    }

    @Test
    fun `saved settings survive store recreation and are isolated per account`() {
        val disk = mutableMapOf<String, String>()
        fun store() = JsonLabyrinthRerollSettingsStore(disk::get) { key, text -> disk[key] = text }
        val custom = LabyrinthRerollSettings(guildId = 2, difficulty = 3, perfectStart = false,
            maxAttempts = "7", rerollUntilFound = false, retireExisting = false,
            thirdBlockChoice = LabyrinthThirdBlockChoice.EVENT, area3BossIds = emptySet())
        store().save(12, custom)
        assertEquals(custom, store().load(12))
        assertEquals(LabyrinthRerollSettings(), store().load(13))
        store().save(13, LabyrinthRerollSettings())
        assertEquals(custom, store().load(12))
    }

    @Test
    fun `cancelled draft does not mutate saved configuration`() {
        val disk = mutableMapOf<String, String>()
        val store = JsonLabyrinthRerollSettingsStore(disk::get) { key, text -> disk[key] = text }
        store.save(1, LabyrinthRerollSettings(retireExisting = false))
        val saved = store.load(1)
        val draft = saved.copy(retireExisting = true, difficulty = 1)
        assertFalse(store.load(1).retireExisting)
        store.save(1, draft)
        assertEquals(draft, store.load(1))
    }

    @Test
    fun `invalid and corrupted settings are rejected without overwriting saved data`() {
        val disk = mutableMapOf("account.2" to "broken json")
        val store = JsonLabyrinthRerollSettingsStore(disk::get) { key, text -> disk[key] = text }
        assertEquals(LabyrinthRerollSettings(), store.load(2))
        for (bad in listOf(LabyrinthRerollSettings(guildId = 999), LabyrinthRerollSettings(maxAttempts = ""),
            LabyrinthRerollSettings(difficulty = 0), LabyrinthRerollSettings(area3BossIds = setOf(-1)))) {
            assertNotNull(bad.validationError())
            assertTrue(runCatching { store.save(2, bad) }.isFailure)
        }
        assertEquals("broken json", disk["account.2"])
    }

    @Test
    fun `task snapshot stays frozen when draft sets or settings change`() {
        val ids = LabyrinthRerollOptions.defaultArea3BossIds.toMutableSet()
        val settings = LabyrinthRerollSettings(area3BossIds = ids)
        val config = settings.toConfig(99)
        ids.clear()
        assertEquals(99L, config.accountId)
        assertEquals(LabyrinthRerollOptions.defaultArea3BossIds, config.routePolicy.area3BossIds)
        assertTrue(config.retireExisting)
        assertTrue(config.rerollUntilFound)
    }

    @Test
    fun `notification reports progress elapsed captcha and completion without credentials`() {
        val running = LabyrinthUiState(isWorking = true, progress = "28/不限 · 检查路线", startedAtMillis = 1000)
        val text = labyrinthRerollStatusText(running, 62000)
        assertTrue(text.contains("1分1秒"))
        assertTrue(text.contains("28/不限"))
        val completed = running.copy(isWorking = false, progress = null, message = "已保留目标开局")
        assertTrue(labyrinthRerollStatusText(completed, 62000).contains("已保留目标开局"))
        assertTrue(labyrinthRerollStatusText(running, 0).contains("0分0秒"))
        val captcha = running.copy(captcha = AccountCaptchaState(1, "private-alias",
            CaptchaChallenge("private-gt", "private-challenge", "private-user", "private-type")))
        val captchaText = labyrinthRerollStatusText(captcha, 62000)
        assertTrue(captchaText.contains("需要验证码"))
        assertFalse(captchaText.contains("private-"))
    }
}
