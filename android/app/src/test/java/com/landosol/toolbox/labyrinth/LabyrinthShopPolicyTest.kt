package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopPolicyTest {
    private val rect = EntryPixelRect(0, 0, 10, 10)

    @Test fun `purchase switch closes even with available relic and refresh`() {
        val decision = LabyrinthShopPolicy(buyRelics = false).decide(5,
            listOf(item("r1", "会心", 1, true)), emptyList(), refreshAvailable = true)
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test fun `refresh preference supports disabling or starting earlier`() {
        assertTrue(LabyrinthShopPolicy(refreshEnabled = false).decide(5, emptyList(), emptyList(),
            refreshAvailable = true) is LabyrinthShopDecision.Close)
        val policy = LabyrinthShopPolicy(finalArea = 3)
        assertTrue(policy.decide(2, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close)
        assertTrue(policy.decide(3, emptyList(), emptyList(), refreshAvailable = true,
            relicPurchasesInCycle = 3) is LabyrinthShopDecision.Refresh)
        assertTrue(policy.decide(null, emptyList(), emptyList(), refreshAvailable = true) is LabyrinthShopDecision.Close)
    }

    @Test fun `configured baseline deadline changes actual relic choice`() {
        val stacks = mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.CRITICAL to 4,
            LabyrinthRelicMark.DEFENSE to 3)
        val items = listOf(item("defense", "守备", 1, true), item("speed", "加速", 1, true))
        fun choose(end: Int) = (LabyrinthShopPolicy(LabyrinthStrategySettings(baselineLastArea = end).relicPolicy())
            .decide(3, items, emptyList(), stacks, false) as LabyrinthShopDecision.BuyRelic).relicDecision.choice.relicId
        assertEquals("defense", choose(3))
        assertEquals("speed", choose(2))
    }

    @Test
    fun `buys a recognized relic before considering refresh`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(item("r1", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `does not refresh before final area`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(item("role", null, null, recognized = false, confidence = 0.2)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `does not refresh an incomplete relic stock cycle`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(item("role", null, null, recognized = false, confidence = 0.2)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `ambiguous relic-like icon blocks refresh`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 5,
            items = listOf(item("maybe", "会心", 1, recognized = false, confidence = 0.70)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Wait)
    }

    @Test
    fun `disabled recognized relic is never purchased and does not block close or refresh`() {
        val disabled = item(
            "too-expensive",
            "会心",
            1,
            recognized = true,
            status = LabyrinthShopItemStatus.DISABLED,
        )
        assertTrue(
            LabyrinthShopPolicy().decide(
                currentArea = 3,
                items = listOf(disabled),
                acquired = emptyList(),
                refreshAvailable = false,
            ) is LabyrinthShopDecision.Close,
        )
        assertTrue(
            LabyrinthShopPolicy().decide(
                currentArea = 5,
                items = listOf(disabled),
                acquired = emptyList(),
                refreshAvailable = true,
            ) is LabyrinthShopDecision.Close,
        )
    }

    @Test
    fun `recognized relic outranks purchasable role imprint`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "破防型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(imprint, item("relic", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 2,
        )
        assertTrue(decision is LabyrinthShopDecision.BuyRelic)
    }

    @Test
    fun `completed three relic cycle refresh outranks role imprint`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "坦克型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(imprint),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 3,
        )
        assertTrue(decision is LabyrinthShopDecision.Refresh)
    }

    @Test
    fun `three visible imprints recover exhausted relic stock after session restart`() {
        val items = (1..3).map { index ->
            item(
                "imprint$index",
                "守备",
                1,
                recognized = false,
                confidence = 0.72,
                kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = if (index == 1) "减益型职能印记" else null,
            )
        }
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = items,
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 0,
        )
        assertTrue(decision is LabyrinthShopDecision.Refresh)
        assertTrue((decision as LabyrinthShopDecision.Refresh).reason.contains("恢复"))
    }

    @Test
    fun `unknown imprint class is never bought blindly when refresh is unavailable`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = null,
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(imprint),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `role imprint is bought only after no relic purchase or completed cycle refresh remains`() {
        val imprint = item(
            "imprint",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "增益型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 5,
            items = listOf(
                item("too-expensive", "会心", 1, recognized = true, status = LabyrinthShopItemStatus.DISABLED),
                imprint,
            ),
            acquired = emptyList(),
            refreshAvailable = false,
            relicPurchasesInCycle = 2,
        ) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("增益型职能印记", decision.label)
    }

    @Test
    fun `confirmed role imprint suppresses misleading ambiguous relic score`() {
        val imprint = item(
            "shop_item_3",
            "守备",
            1,
            recognized = false,
            confidence = 0.72,
            kind = LabyrinthShopItemKind.ROLE_IMPRINT,
            roleImprintLabel = "破防型职能印记",
        )
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 3,
            items = listOf(imprint),
            acquired = emptyList(),
            refreshAvailable = false,
        )
        assertTrue(decision is LabyrinthShopDecision.BuyRoleImprint)
    }

    @Test
    fun `area three closes after its first three relic purchases even when refill goods look relic-like`() {
        val policy = LabyrinthShopPolicy()
        val decision = policy.decide(
            currentArea = 3,
            items = listOf(
                item("role1", "守备", 1, recognized = false, confidence = 0.72),
                item("role2", "守备", 1, recognized = false, confidence = 0.72),
                item("role3", "守备", 1, recognized = false, confidence = 0.72),
            ),
            acquired = emptyList(),
            refreshAvailable = true,
            relicPurchasesInCycle = 3,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `area five refreshes only after all three relics in the stock cycle were purchased`() {
        val policy = LabyrinthShopPolicy()
        val ambiguous = listOf(item("role", "守备", 1, recognized = false, confidence = 0.72))
        assertTrue(
            policy.decide(
                currentArea = 5,
                items = ambiguous,
                acquired = emptyList(),
                refreshAvailable = true,
                relicPurchasesInCycle = 2,
            ) is LabyrinthShopDecision.Wait,
        )
        assertTrue(
            policy.decide(
                currentArea = 5,
                items = ambiguous,
                acquired = emptyList(),
                refreshAvailable = true,
                relicPurchasesInCycle = 3,
            ) is LabyrinthShopDecision.Refresh,
        )
    }

    @Test
    fun `area four never buys even a safely recognized relic`() {
        val decision = LabyrinthShopPolicy().decide(
            currentArea = 4,
            items = listOf(item("r1", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = true,
        )
        assertTrue(decision is LabyrinthShopDecision.Close)
    }

    @Test
    fun `re-evaluation is slot independent after shop compacts items`() {
        val policy = LabyrinthShopPolicy()
        val first = policy.decide(
            currentArea = 2,
            items = listOf(item("slot1", "会心", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = false,
        ) as LabyrinthShopDecision.BuyRelic
        val next = policy.decide(
            currentArea = 2,
            items = listOf(item("slot1", "守备", 1, recognized = true)),
            acquired = emptyList(),
            refreshAvailable = false,
        ) as LabyrinthShopDecision.BuyRelic
        assertEquals("会心", first.relicDecision.choice.attribute)
        assertEquals("守备", next.relicDecision.choice.attribute)
    }

    @Test
    fun `shop uses the same area aware priorities as reward choices`() {
        val stacks = mapOf(LabyrinthRelicMark.ACCELERATION to 8, LabyrinthRelicMark.CRITICAL to 4,
            LabyrinthRelicMark.DEFENSE to 3)
        fun choose(area: Int) = (LabyrinthShopPolicy().decide(area,
            listOf(item("defense", "守备", 1, true), item("speed", "加速", 1, true)),
            emptyList(), stacks, false) as LabyrinthShopDecision.BuyRelic).relicDecision.choice.relicId
        assertEquals("defense", choose(3))
        assertEquals("speed", choose(5))
    }

    private fun item(
        id: String,
        mark: String?,
        bonus: Int?,
        recognized: Boolean,
        confidence: Double = if (recognized) 0.9 else 0.2,
        status: LabyrinthShopItemStatus = LabyrinthShopItemStatus.AVAILABLE,
        kind: LabyrinthShopItemKind = if (recognized) LabyrinthShopItemKind.RELIC else LabyrinthShopItemKind.UNKNOWN,
        roleImprintLabel: String? = null,
    ): LabyrinthShopItemMatch {
        val relic = LabyrinthRelicMatch(
            slotId = id,
            relicId = if (recognized) id else null,
            displayName = if (recognized) id else null,
            attribute = if (recognized) mark else null,
            attributeBonus = if (recognized) bonus?.toString() else null,
            effect = null,
            confidence = confidence,
            screenRect = rect,
            iconRect = rect,
            suspectedRelicId = id,
            suspectedDisplayName = id,
            suspectedAttribute = mark,
            rivalMargin = if (recognized) 0.2 else 0.01,
        )
        return LabyrinthShopItemMatch(
            slotId = id,
            screenRect = rect,
            buyButtonRect = rect,
            buyButtonScore = 0.9,
            buyButtonEnabledEvidence = if (status == LabyrinthShopItemStatus.AVAILABLE) 1.0 else 0.0,
            status = status,
            relicMatch = relic,
            kind = kind,
            roleImprintLabel = roleImprintLabel,
        )
    }
}
