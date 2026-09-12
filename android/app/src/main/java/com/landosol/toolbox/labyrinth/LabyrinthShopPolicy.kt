package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind

sealed interface LabyrinthShopDecision {
    data class BuyRelic(
        val item: LabyrinthShopItemMatch,
        val relicDecision: LabyrinthRelicChoiceDecision,
    ) : LabyrinthShopDecision

    data class BuyRoleImprint(
        val item: LabyrinthShopItemMatch,
        val label: String,
        val reason: String,
    ) : LabyrinthShopDecision

    data class Refresh(val reason: String) : LabyrinthShopDecision
    data class Close(val reason: String) : LabyrinthShopDecision
    data class Wait(val reason: String) : LabyrinthShopDecision
}

/**
 * Shop policy for route execution.
 *
 * Rules requested by the live Dry Run:
 * - relics always outrank role-imprint goods;
 * - areas 1..4 never spend 300 coins on refresh;
 * - area 5 refreshes only after all three relics of the current stock cycle were purchased;
 * - when no relic can still be bought and another relic cycle cannot be refreshed, a safely
 *   identified purchasable role imprint may consume the remaining coins and enter role selection;
 * - an icon that still looks relic-like but failed the identity safety margin blocks refresh/
 *   close. Skipping an ambiguous relic is worse than waiting for another recognition frame.
 *
 * The visible row is evaluated again after every completed purchase. The game compacts later
 * goods into the open slot, so the next decision intentionally does not remember a physical slot.
 */
class LabyrinthShopPolicy(
    private val relicChoicePolicy: LabyrinthRelicChoicePolicy = LabyrinthRelicChoicePolicy(),
    private val finalArea: Int = 5,
    private val noPurchaseAreas: Set<Int> = setOf(4),
    private val relicsPerStockCycle: Int = 3,
    private val ambiguousRelicLikeConfidence: Double = 0.45,
    private val buyRelics: Boolean = true,
    private val refreshEnabled: Boolean = true,
) {
    fun decide(
        currentArea: Int?,
        items: List<LabyrinthShopItemMatch>,
        acquired: List<LabyrinthObservedRelic>,
        calibratedStacks: Map<LabyrinthRelicMark, Int> = emptyMap(),
        refreshAvailable: Boolean,
        lockedFocus: LabyrinthRelicMark? = null,
        relicPurchasesInCycle: Int = 0,
    ): LabyrinthShopDecision {
        if (!buyRelics) return LabyrinthShopDecision.Close("策略已关闭商店购买，退出商店")
        if (currentArea != null && currentArea in noPurchaseAreas) {
            return LabyrinthShopDecision.Close("区域${currentArea}按策略不购买商店商品，直接退出")
        }

        // Exact role identity is required before spending coins on an imprint. A coarse
        // ROLE_IMPRINT classification is still useful because it proves the slot is not a relic
        // and can recover an exhausted stock cycle after an app restart/reinstall.
        val purchasableRoleImprint = items.firstOrNull { item ->
            item.purchasable && item.kind == LabyrinthShopItemKind.ROLE_IMPRINT &&
                !item.roleImprintLabel.isNullOrBlank()
        }

        // The game exposes exactly three relic goods per stock cycle. In a continuous session the
        // counter below reaches 3 from purchase-complete commits. If the app is restarted while
        // still inside the shop, that volatile counter is lost; when all three active goods have
        // already compacted to role-imprints, the relic stock is nevertheless unambiguously
        // exhausted and the cycle can be recovered without re-identifying a nonexistent relic.
        val visibleStockExhausted = items.size == relicsPerStockCycle &&
            items.all { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT }
        val effectiveRelicPurchasesInCycle = if (visibleStockExhausted) {
            maxOf(relicPurchasesInCycle, relicsPerStockCycle)
        } else {
            relicPurchasesInCycle
        }

        // Completing all three relic purchases is the only point at which refresh outranks an
        // imprint. Keep converting coins into fresh relic stock for as long as refresh remains
        // actionable; only after that path is exhausted may an imprint consume the remainder.
        if (effectiveRelicPurchasesInCycle >= relicsPerStockCycle) {
            if (refreshEnabled && (currentArea ?: 0) >= finalArea && refreshAvailable) {
                return LabyrinthShopDecision.Refresh(
                    if (visibleStockExhausted && relicPurchasesInCycle < relicsPerStockCycle) {
                        "当前三个可购买位均为职能印记，恢复为本轮三个遗物已售罄；最终区域花300刷新下一轮遗物"
                    } else {
                        "本轮前三个遗物已买完，最终区域允许花300刷新下一轮遗物"
                    },
                )
            }
            if (purchasableRoleImprint != null) {
                return LabyrinthShopDecision.BuyRoleImprint(
                    item = purchasableRoleImprint,
                    label = purchasableRoleImprint.roleImprintLabel ?: "职能印记",
                    reason = "本轮三个遗物已买完且无法继续刷新，用剩余金币购买可确认印记",
                )
            }
            return LabyrinthShopDecision.Close(
                if ((currentArea ?: 0) >= finalArea) {
                    "本轮前三个遗物已买完且无法刷新，也没有可购买印记，结束商店"
                } else {
                    "区域${currentArea ?: "?"}本轮前三个遗物已买完，没有可购买印记，结束商店"
                },
            )
        }
        val purchasable = items.filter(LabyrinthShopItemMatch::purchasable)
        val recognizedRelicItems = purchasable.filter {
            it.kind != LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch?.recognized == true
        }
        if (recognizedRelicItems.isNotEmpty()) {
            val candidates = recognizedRelicItems.mapNotNull(LabyrinthShopItemMatch::relicMatch)
            val relicDecision = relicChoicePolicy.choose(
                acquired = acquired,
                candidates = candidates,
                calibratedStacks = calibratedStacks,
                currentArea = currentArea,
                lockedFocus = lockedFocus,
            )
            if (relicDecision != null) {
                val item = recognizedRelicItems.first { it.relicMatch?.relicId == relicDecision.choice.relicId }
                return LabyrinthShopDecision.BuyRelic(item, relicDecision)
            }
            return LabyrinthShopDecision.Wait("已识别到遗物，但属性/层数元数据不足，拒绝盲买")
        }

        val ambiguous = purchasable.firstOrNull { item ->
            if (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT) return@firstOrNull false
            val relic = item.relicMatch ?: return@firstOrNull false
            !relic.recognized && relic.confidence >= ambiguousRelicLikeConfidence
        }
        if (ambiguous != null) {
            val relic = ambiguous.relicMatch
            return LabyrinthShopDecision.Wait(
                "${ambiguous.slotId}疑似遗物(${"%.2f".format(relic?.confidence ?: 0.0)})但身份未过安全线",
            )
        }

        // No safely purchasable relic remains. Do not refresh a partially consumed stock just to
        // skip an affordable imprint: refresh is reserved for a completed three-relic cycle.
        if (purchasableRoleImprint != null) {
            return LabyrinthShopDecision.BuyRoleImprint(
                item = purchasableRoleImprint,
                label = purchasableRoleImprint.roleImprintLabel ?: "职能印记",
                reason = "当前没有可继续购买的遗物，购买可确认印记作为剩余金币兜底",
            )
        }

        return LabyrinthShopDecision.Close(
            when {
                !refreshEnabled -> "策略禁止刷新，当前无可确认遗物或可购买印记，结束商店"
                (currentArea ?: 0) >= finalArea && refreshAvailable ->
                    "当前轮尚未确认买满三个遗物，不提前刷新；且没有可购买印记，结束商店"
                (currentArea ?: 0) >= finalArea ->
                    "当前无可确认遗物、可购买印记或可用刷新，结束商店"
                else -> "区域${currentArea ?: "?"}当前无可确认遗物或可购买印记，结束商店"
            },
        )
    }
}
