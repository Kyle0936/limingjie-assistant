package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper
import com.landosol.toolbox.labyrinth.vision.labyrinthRoleRewardOwnsInitialSelection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes

/** Exactly one page owns page-local actions, plans and overlay boxes for each recognized frame. */
internal enum class LabyrinthPageUiOwner {
    NONE,
    NODE_SELECTION,
    NODE_MAP_VIEW,
    CHARACTER_SELECTION,
    CHARACTER_JOINED,
    BATTLE_TEAM_SELECTION,
    LINK_CHOICE,
    RELIC_CHOICE,
    EVENT_CHOICE,
    SHOP,
    BATTLE,
    RUN_RESULT,
}

/**
 * A movement modal may already be open even though the session lost/never created its pending
 * tap record (for example a manual tap while automation is searching).  Recovery must not turn
 * the generic two-button detector into a broad auto-confirm rule, so require both a very strong
 * modal observation and a route graph with exactly one reachable saved-route successor.
 */
internal fun labyrinthCanRecoverOrphanNodeMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    confirmationConfidence: Double,
    hasUniqueReachableRouteTarget: Boolean,
): Boolean {
    if (!hasUniqueReachableRouteTarget || confirmationConfidence < 0.85) return false
    return pageState in setOf(
        LabyrinthEntryPageState.NODE_SELECTION,
        LabyrinthEntryPageState.NODE_MAP_VIEW,
        LabyrinthEntryPageState.UNKNOWN,
    )
}

/**
 * A one-choice event has no strategic ambiguity.  Two independent current-frame anchors are
 * required before exposing its sole button so this cannot turn an unrelated UNKNOWN/map frame
 * into a blind click.
 */
internal fun labyrinthSingleChoiceEventButtonRect(
    result: LabyrinthEntryFrameResult,
    minimumScore: Double = 0.72,
): EntryPixelRect? {
    if (result.observation.state != LabyrinthEntryPageState.EVENT_CHOICE) return null
    val scores = result.observation.anchorScores
    if (scores[EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE] < minimumScore) return null
    val match = result.anchorMatches[EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON] ?: return null
    return match.rect.takeIf { match.score >= minimumScore }
}

/**
 * Direct single-target EX name OCR is intentionally given a short chance first.  If it still
 * cannot identify the encounter, opening the fixed monster Details button is a non-destructive
 * way to move identity OCR onto the much more stable detail-modal name row.  Multi-monster and
 * special-dual EX use their own structurally detected info buttons and never enter this path.
 */
internal fun labyrinthSingleExDetailProbeRect(
    pageState: LabyrinthEntryPageState,
    isEx: Boolean,
    encounterResolved: Boolean,
    multiMonsterLikely: Boolean,
    specialDualLikely: Boolean,
    elapsedMillis: Long,
    stableFrames: Int,
    attempts: Int,
    frameWidth: Int,
    frameHeight: Int,
    minimumWaitMillis: Long = 3_000L,
    minimumStableFrames: Int = 2,
    maxAttempts: Int = 2,
): EntryPixelRect? {
    if (pageState != LabyrinthEntryPageState.BATTLE_CHALLENGE || !isEx || encounterResolved) return null
    if (multiMonsterLikely || specialDualLikely) return null
    if (elapsedMillis < minimumWaitMillis || stableFrames < minimumStableFrames || attempts >= maxAttempts) return null
    if (frameWidth <= 0 || frameHeight <= 0) return null
    return ReferenceFitMapper.map(
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        referenceSize = EntryReferenceSize(1920, 1080),
        referenceRect = EntryReferenceRect(1395, 590, 155, 88),
    )
}

/**
 * Final safety gate for the challenge button.
 *
 * Challenge-title OCR is asynchronous. Without this gate an EVENT -> EX transition can expose a
 * BATTLE_CHALLENGE frame before "极难" has returned from OCR, allowing the generic challenge
 * branch to tap first. Boss nodes are already semantically identified by the route. Every other
 * challenge must either be positively classified as a non-EX title, or have a concrete EX guide
 * resolved before the challenge button may fire.
 */
internal fun labyrinthBattleChallengeCanAutoStart(
    combatContext: LabyrinthCombatContext?,
    challengeDifficultyResolved: Boolean,
    extremeChallenge: Boolean,
    exEncounterResolved: Boolean,
): Boolean = when {
    combatContext?.kind == LabyrinthCombatKind.BOSS -> true
    combatContext?.kind == LabyrinthCombatKind.EX || extremeChallenge -> exEncounterResolved
    !challengeDifficultyResolved -> false
    else -> true
}

internal fun labyrinthPageUiOwner(page: LabyrinthEntryPageState): LabyrinthPageUiOwner = when (page) {
    LabyrinthEntryPageState.NODE_SELECTION -> LabyrinthPageUiOwner.NODE_SELECTION
    LabyrinthEntryPageState.NODE_MAP_VIEW -> LabyrinthPageUiOwner.NODE_MAP_VIEW
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> LabyrinthPageUiOwner.CHARACTER_SELECTION
    LabyrinthEntryPageState.CHARACTER_JOINED -> LabyrinthPageUiOwner.CHARACTER_JOINED
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION
    LabyrinthEntryPageState.LINK_CHOICE -> LabyrinthPageUiOwner.LINK_CHOICE
    LabyrinthEntryPageState.RELIC_CHOICE -> LabyrinthPageUiOwner.RELIC_CHOICE
    LabyrinthEntryPageState.EVENT_CHOICE,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    -> LabyrinthPageUiOwner.EVENT_CHOICE
    LabyrinthEntryPageState.SHOP,
    LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
    LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
    LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
    -> LabyrinthPageUiOwner.SHOP
    LabyrinthEntryPageState.BATTLE_CHALLENGE,
    LabyrinthEntryPageState.BATTLE_IN_PROGRESS,
    LabyrinthEntryPageState.BATTLE_FAILED,
    LabyrinthEntryPageState.BATTLE_RESULT,
    -> LabyrinthPageUiOwner.BATTLE
    LabyrinthEntryPageState.RUN_CLEAR_RESULT,
    LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS,
    LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
    LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION,
    LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
    -> LabyrinthPageUiOwner.RUN_RESULT
    else -> LabyrinthPageUiOwner.NONE
}

/**
 * The movement-confirmation detector intentionally looks for a generic two-button modal and can
 * also fire on unrelated dialogs such as SHOP_EXIT_CONFIRMATION. Page ownership must therefore
 * gate it before it is allowed to pre-empt the page-local action handler.
 *
 * A pending node transition may temporarily obscure the map enough for the page classifier to
 * return UNKNOWN, so keep that one bounded exception. Without a pending transition the generic
 * colour/shape detector has no semantic ownership at all: ordinary node-map controls can satisfy
 * its blue/light coverage test and would otherwise surface a false "manual confirmation" block.
 */
internal fun labyrinthNodeMoveConfirmationOwnsFrame(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
): Boolean {
    if (!hasPendingNodeTransition) return false
    return when (pageState) {
    LabyrinthEntryPageState.NODE_SELECTION,
    LabyrinthEntryPageState.NODE_MAP_VIEW,
    LabyrinthEntryPageState.UNKNOWN,
    -> true
    else -> false
    }
}

/**
 * Page-local state invalidation normally clears a pending map-node transition as soon as the
 * classifier leaves NODE_SELECTION.  A real movement-confirmation modal can itself make the page
 * classify as NODE_MAP_VIEW or UNKNOWN, though, so preserve the pending semantic transition for
 * that one frame family.  The generic two-button detector is not sufficient on its own: ownership
 * still requires an already-dispatched programmatic node transition and a map-compatible page.
 */
internal fun labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
    pageState: LabyrinthEntryPageState,
    hasPendingNodeTransition: Boolean,
    hasMoveConfirmation: Boolean,
): Boolean = hasMoveConfirmation && labyrinthNodeMoveConfirmationOwnsFrame(
    pageState = pageState,
    hasPendingNodeTransition = hasPendingNodeTransition,
)

/**
 * A programmatic shop purchase may render one or two UNKNOWN transition frames before the
 * confirmation modal becomes recognizable. Keep only the semantic relic-id transaction across
 * that bounded transition; page-local coordinates still remain invalidated normally.
 */
internal fun labyrinthKeepsPlannedShopPurchaseAcrossPage(
    pageState: LabyrinthEntryPageState,
    plannedRelicId: String?,
    startedAt: Long,
    now: Long,
    timeoutMillis: Long,
): Boolean {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return false
    if (plannedRelicId.isNullOrBlank() || startedAt == Long.MIN_VALUE) return false
    return (now - startedAt).coerceAtLeast(0L) <= timeoutMillis
}

/**
 * Purchase/refresh animation frames can resemble the broad reconnect-title template while the
 * shop itself is temporarily classified UNKNOWN. Suppress only that short, semantically owned
 * transition; a genuine session error remains actionable again as soon as the guard expires.
 */
internal fun labyrinthShopTransitionSuppressesSessionBlock(
    pageState: LabyrinthEntryPageState,
    previousPageState: LabyrinthEntryPageState?,
    activeNodeType: Int?,
    lastActionAt: Long,
    now: Long,
    guardMillis: Long = 3_000L,
): Boolean {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return false
    if (activeNodeType != LabyrinthNodeTypes.SHOP) return false
    if (labyrinthPageUiOwner(previousPageState ?: LabyrinthEntryPageState.UNKNOWN) != LabyrinthPageUiOwner.SHOP) {
        return false
    }
    if (lastActionAt == Long.MIN_VALUE) return false
    return (now - lastActionAt).coerceAtLeast(0L) <= guardMillis
}

/**
 * Intermediate Boss victories (for example area 3 -> area 4) use a special WIN/result chain
 * that is not the final run-clear flow. On the current client those pages can be classified as
 * UNKNOWN while still exposing the same fixed bottom-right "下一步" button used by the ordinary
 * battle result page.
 *
 * This is deliberately narrower than a generic UNKNOWN fallback: only an active Boss combat
 * context may use it, and only a current-frame button match above the Boss-specific safety line
 * is accepted. Normal/EX battles and unrelated UNKNOWN pages keep their existing behavior.
 */
internal fun labyrinthBossSettlementNextButtonRect(
    pageState: LabyrinthEntryPageState,
    combatContext: LabyrinthCombatContext?,
    nextButtonMatch: com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch?,
    bossSummaryNextButtonMatch: com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch? = null,
    minimumScore: Double = 0.68,
): com.landosol.toolbox.labyrinth.vision.EntryPixelRect? {
    if (pageState != LabyrinthEntryPageState.UNKNOWN) return null
    if (combatContext?.kind != LabyrinthCombatKind.BOSS) return null
    val match = listOfNotNull(nextButtonMatch, bossSummaryNextButtonMatch)
        .maxByOrNull(com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch::score)
        ?: return null
    return match.rect.takeIf { match.score >= minimumScore }
}

internal fun labyrinthIsRoleRewardPage(
    result: LabyrinthEntryFrameResult,
    minimumAnchorScore: Double = ROLE_PAGE_ANCHOR_MIN_SCORE,
): Boolean = result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    labyrinthRoleRewardOwnsInitialSelection(
        anchorScores = result.observation.anchorScores,
        minimumScore = minimumAnchorScore,
    )

/**
 * A role-reward batch can present several selection pages back-to-back without changing the
 * page enum. Use the candidate identities, not the page state, to tell one selection round from
 * the next. Scores are intentionally excluded so recognition jitter cannot create a fake round.
 */
internal fun labyrinthRoleRewardSelectionSignature(
    result: LabyrinthEntryFrameResult,
): String? {
    if (!labyrinthIsRoleRewardPage(result)) return null
    if (result.characterMatches.isEmpty()) return null
    return result.characterMatches
        .sortedBy { it.slotId }
        .joinToString("|") { match ->
            val identity = match.characterId ?: match.suspectedCharacterId ?: "?"
            "${match.slotId}:$identity"
        }
}

/**
 * Boss/event settlements may first show several role-choice pages and only afterwards show all
 * corresponding CHARACTER_JOINED popups. Keep that whole sequence as one reward batch. An
 * explicit non-reward page is the terminal boundary; UNKNOWN is allowed only inside the already
 * armed batch so transient portrait/transition frames can still be advanced safely.
 */
internal fun labyrinthKeepsRoleRewardBatchOnPage(
    pageState: LabyrinthEntryPageState,
    isRoleRewardPage: Boolean,
): Boolean = when (pageState) {
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> isRoleRewardPage
    LabyrinthEntryPageState.UNKNOWN,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    LabyrinthEntryPageState.CHARACTER_JOINED,
    LabyrinthEntryPageState.ITEM_REWARD,
    -> true
    else -> false
}

/**
 * After resuming an already-active Dawn Realm run, the game may reopen directly on a pending
 * role-reward page instead of the node map. Those pages belong to route execution rather than
 * the opening invitation flow. Keep this deliberately narrow so a fresh run still retains the
 * original CHARACTER_JOINED opening-safety check.
 */
internal fun labyrinthResumedRunRewardPageOwnsRoute(
    result: LabyrinthEntryFrameResult,
): Boolean = when (result.observation.state) {
    LabyrinthEntryPageState.CHARACTER_JOINED -> true
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> labyrinthIsRoleRewardPage(result)
    else -> false
}

/** A real opening roster must reclaim the entry flow even after a premature map handoff. */
internal fun labyrinthShouldResumeOpeningSelection(
    entryPhaseComplete: Boolean,
    result: LabyrinthEntryFrameResult,
): Boolean = entryPhaseComplete &&
    result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    !labyrinthIsRoleRewardPage(result) &&
    maxOf(
        result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER],
        result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER_STANDARD],
    ) >= 0.85

/**
 * Returns only positive, trusted ownership evidence. Absence from a later viewport is never
 * represented here and therefore can never delete a previously known role.
 */
internal fun labyrinthRosterReconciliationMatches(
    result: LabyrinthEntryFrameResult,
): List<LabyrinthCharacterMatch> = when (result.observation.state) {
    LabyrinthEntryPageState.CHARACTER_JOINED -> result.characterMatches
        .filter { it.trusted && it.characterId != null && it.displayName != null }

    // The battle editor is an observation of the already-owned roster, not an acquisition
    // event. Appending its transient icon guesses permanently polluted the saved roster whenever
    // a seasonal variant briefly matched the wrong candidate.
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> emptyList()

    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> {
        if (labyrinthIsRoleRewardPage(result) || !openingRosterIsConfirmed(result)) {
            emptyList()
        } else {
            result.openingCharacterMatches
                .filter {
                    it.selected && it.trusted && it.characterId != null && it.displayName != null
                }
                .map(::toRosterMatch)
                .distinctBy { match -> canonicalLabyrinthRoleId(requireNotNull(match.characterId)) }
                .takeIf { it.size == REQUIRED_OPENING_ROSTER_SIZE }
                .orEmpty()
        }
    }

    else -> emptyList()
}
    .distinctBy { match -> canonicalLabyrinthRoleId(requireNotNull(match.characterId)) }

internal fun labyrinthRosterReplacesExisting(
    result: LabyrinthEntryFrameResult,
    matches: List<LabyrinthCharacterMatch>,
): Boolean = result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    matches.size == REQUIRED_OPENING_ROSTER_SIZE

private fun openingRosterIsConfirmed(result: LabyrinthEntryFrameResult): Boolean {
    val scores = result.observation.anchorScores
    val selectionComplete = maxOf(
        scores[EntryAnchorId.SELECTION_COUNT_COMPLETE],
        scores[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD],
    )
    val inviteEnabled = maxOf(
        scores[EntryAnchorId.INVITE_ENABLED],
        scores[EntryAnchorId.INVITE_ENABLED_STANDARD],
    )
    return minOf(selectionComplete, inviteEnabled) >= ROLE_PAGE_ANCHOR_MIN_SCORE
}

private fun toRosterMatch(match: LabyrinthBattleCharacterMatch) = LabyrinthCharacterMatch(
    slotId = match.slotId,
    characterId = match.characterId,
    displayName = match.displayName,
    confidence = match.confidence,
    screenRect = match.screenRect,
    iconVariant = match.iconVariant,
    trusted = match.trusted,
    rivalMargin = match.rivalMargin,
)

private const val ROLE_PAGE_ANCHOR_MIN_SCORE = 0.45
private const val REQUIRED_OPENING_ROSTER_SIZE = 3
