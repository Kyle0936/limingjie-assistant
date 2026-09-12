package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper

/**
 * Role-reward cards are large, stationary portraits with a visible name row.  Do not inherit the
 * deliberately permissive full-roster icon floor here: a barely-separated icon match is useful as
 * an OCR candidate, but it is not safe enough to drive an irreversible three-choice tap by itself.
 */
internal const val ROLE_REWARD_STRONG_ICON_CONFIDENCE = 0.50
internal const val ROLE_REWARD_STRONG_ICON_RIVAL_MARGIN = 0.10

internal fun labyrinthRoleRewardIdentityIsActionSafe(match: LabyrinthCharacterMatch): Boolean =
    match.trusted && (
        match.nameAssisted ||
            (match.confidence >= ROLE_REWARD_STRONG_ICON_CONFIDENCE &&
                match.rivalMargin >= ROLE_REWARD_STRONG_ICON_RIVAL_MARGIN)
        )

/** Weak-but-trusted icon matches still deserve the page's name OCR second pass. */
internal fun labyrinthRoleRewardNeedsNameVerification(match: LabyrinthCharacterMatch): Boolean =
    !match.nameAssisted && !labyrinthRoleRewardIdentityIsActionSafe(match)

sealed interface LabyrinthRoleRewardChoiceDecision {
    data class Select(
        val characterId: String,
        val displayName: String,
        val buttonRect: EntryPixelRect,
        val explanation: List<String>,
        /** False means this is a visible recommendation only; live mode must not tap it. */
        val actionSafe: Boolean = true,
        val safetyNote: String? = null,
    ) : LabyrinthRoleRewardChoiceDecision

    data class Wait(
        val reason: String,
    ) : LabyrinthRoleRewardChoiceDecision
}

/**
 * Safety adapter between visual recognition and role scoring. It permits a tap only when all
 * three different candidates are trusted. Incomplete scoring data is advisory, not a tap gate.
 */
class LabyrinthRoleRewardChoicePlanner(
    private val profiles: Map<String, LabyrinthRoleProfile>,
    private val roleChoicePolicy: LabyrinthRoleChoicePolicy,
) {
    init {
        require(profiles.keys.all { it.isNotBlank() })
        require(profiles.all { (id, profile) -> id == profile.characterId })
    }

    fun decide(
        candidates: List<LabyrinthCharacterMatch>,
        acquiredCharacterIds: Set<String>,
        context: LabyrinthRoleDecisionContext,
        frameWidth: Int,
        frameHeight: Int,
    ): LabyrinthRoleRewardChoiceDecision {
        val bySlot = candidates.associateBy(LabyrinthCharacterMatch::slotId)
        val ordered = SLOT_IDS.mapNotNull(bySlot::get)
        if (ordered.size != SLOT_IDS.size) {
            return LabyrinthRoleRewardChoiceDecision.Wait("角色三选一画面尚未稳定识别三个槽位")
        }
        val weak = ordered.filter {
            it.characterId.isNullOrBlank() ||
                it.displayName.isNullOrBlank() ||
                !labyrinthRoleRewardIdentityIsActionSafe(it)
        }
        if (weak.isNotEmpty()) {
            return LabyrinthRoleRewardChoiceDecision.Wait(
                "角色头像识别未可信：${weak.joinToString { match ->
                    "${match.slotId}=${formatConfidence(match.confidence)}/差${formatConfidence(match.rivalMargin)}"
                }}",
            )
        }
        val candidateIds = ordered.map { canonicalLabyrinthRoleId(requireNotNull(it.characterId)) }
        if (candidateIds.distinct().size != SLOT_IDS.size) {
            return LabyrinthRoleRewardChoiceDecision.Wait("三个候选被识别成重复角色，拒绝点击")
        }
        val canonicalAcquiredIds = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).toSet()
        val knownProfiles = profiles.withKnownRoleIdentities(candidateIds + canonicalAcquiredIds,
            ordered.associate { canonicalLabyrinthRoleId(requireNotNull(it.characterId)) to requireNotNull(it.displayName) })
        val incompleteIds = (candidateIds + canonicalAcquiredIds).distinct()
            .filterNot { knownProfiles.getValue(it).isStrictDecisionReady }.sorted()
        val choice = roleChoicePolicy.chooseOneRole(
            candidateIds = candidateIds,
            acquiredCharacterIds = canonicalAcquiredIds,
            profiles = knownProfiles,
            context = context,
            requireStrictProfiles = false,
        )
        return when (choice) {
            is LabyrinthOneRoleDecision.Ready -> choice.toSelection(
                candidateIds = candidateIds,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                actionSafe = true,
                safetyNote = incompleteIds.takeIf { it.isNotEmpty() }?.let {
                    "部分角色资料不完整，按已有信息评分并允许自动选择：${it.joinToString()}"
                },
            )
            is LabyrinthOneRoleDecision.Unavailable -> LabyrinthRoleRewardChoiceDecision.Wait(choice.reason)
        }
    }

    /**
     * Some EVENT nodes open the full roster selector and allow exactly one free recruit.  The
     * screen shell is almost identical to the initial 3-character selector, so the session uses
     * route context to enter this method instead of the opening-roster policy.
     *
     * Only stable/trusted visible identities are considered.  Unlike the three-card reward page,
     * one weak sibling does not block a safely recognized candidate; this page can contain dozens
     * of cards and the action is anchored to the chosen card's own add button.
     */
    fun decideFreeVisibleRole(
        candidates: List<LabyrinthBattleCharacterMatch>,
        acquiredCharacterIds: Set<String>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthRoleRewardChoiceDecision {
        val trusted = candidates
            .asSequence()
            .filter { match ->
                match.trusted &&
                    !match.selected &&
                    !match.characterId.isNullOrBlank() &&
                    match.characterId !in acquiredCharacterIds
            }
            .distinctBy { it.characterId }
            .toList()
        if (trusted.isEmpty()) {
            return LabyrinthRoleRewardChoiceDecision.Wait("事件自由选角：当前视口没有可靠且未获得的角色")
        }

        // A stale/partial character catalog must not make the whole page unusable.  Ignore only
        // candidates with no scoring profile and choose among the identities we can audit.
        val scorable = trusted.filter { match -> match.characterId in profiles }
        if (scorable.isEmpty()) {
            return LabyrinthRoleRewardChoiceDecision.Wait("事件自由选角：当前可靠角色均缺少评分资料")
        }
        val ids = scorable.mapNotNull(LabyrinthBattleCharacterMatch::characterId)
        return when (
            val choice = roleChoicePolicy.chooseOneRole(
                candidateIds = ids,
                acquiredCharacterIds = acquiredCharacterIds,
                profiles = profiles,
                context = context,
                // Full-roster icons can include old/incomplete profiles.  Existing data still
                // produces an auditable ranking; only the chosen visual identity must be trusted.
                requireStrictProfiles = false,
            )
        ) {
            is LabyrinthOneRoleDecision.Ready -> {
                val match = scorable.firstOrNull { it.characterId == choice.chosen.characterId }
                    ?: return LabyrinthRoleRewardChoiceDecision.Wait("事件自由选角：推荐角色已离开当前视口")
                val card = match.screenRect
                val addRect = EntryPixelRect(
                    left = card.left + (card.width * 0.05f).toInt(),
                    top = card.top + (card.height * 0.72f).toInt(),
                    width = (card.width * 0.22f).toInt().coerceAtLeast(4),
                    height = (card.height * 0.24f).toInt().coerceAtLeast(4),
                )
                LabyrinthRoleRewardChoiceDecision.Select(
                    characterId = choice.chosen.characterId,
                    displayName = choice.chosen.displayName,
                    buttonRect = addRect,
                    explanation = choice.reasons,
                    actionSafe = true,
                    safetyNote = null,
                )
            }

            is LabyrinthOneRoleDecision.Unavailable ->
                LabyrinthRoleRewardChoiceDecision.Wait("事件自由选角：${choice.reason}")
        }
    }

    private fun LabyrinthOneRoleDecision.Ready.toSelection(
        candidateIds: List<String>,
        frameWidth: Int,
        frameHeight: Int,
        actionSafe: Boolean,
        safetyNote: String? = null,
    ): LabyrinthRoleRewardChoiceDecision {
        val slotIndex = candidateIds.indexOf(chosen.characterId)
        val buttonRect = ReferenceFitMapper.map(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = BUTTON_RECTS[slotIndex],
        ) ?: return LabyrinthRoleRewardChoiceDecision.Wait("角色选择按钮无法映射到当前画面")
        return LabyrinthRoleRewardChoiceDecision.Select(
            characterId = chosen.characterId,
            displayName = chosen.displayName,
            buttonRect = buttonRect,
            explanation = reasons,
            actionSafe = actionSafe,
            safetyNote = safetyNote,
        )
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val SLOT_IDS = listOf("role_reward_left", "role_reward_center", "role_reward_right")
        val BUTTON_RECTS = listOf(
            EntryReferenceRect(260, 810, 290, 155),
            EntryReferenceRect(815, 810, 290, 155),
            EntryReferenceRect(1370, 810, 290, 155),
        )
    }
}

private val COMBINED_MEMBER_TO_CANONICAL_ROLE = mapOf(
    "1183" to "1807",
    "1184" to "1807",
    "1204" to "1808",
    "1205" to "1808",
    "1206" to "1808",
    "1217" to "1809",
    "1218" to "1809",
    "1243" to "1810",
    "1244" to "1810",
    "1281" to "1811",
    "1282" to "1811",
)

internal fun canonicalLabyrinthRoleId(characterId: String): String =
    COMBINED_MEMBER_TO_CANONICAL_ROLE[characterId] ?: characterId

private fun formatConfidence(value: Double): String = "%.3f".format(value)
