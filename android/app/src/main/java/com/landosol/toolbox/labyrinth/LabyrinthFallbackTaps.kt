package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * Every hard-coded post-entry tap that is used when no template anchor was matched.
 *
 * Coordinates are in the 1920x1080 reference space. Keeping them in one table lets the
 * approval gate (phase three of the revised plan) reject any fallback that leaves the region a
 * page is known to own, and lets a unit test prove each fallback is covered by the page's
 * declared anchor geometry today. This file only names the points; it does not change which
 * page uses which point.
 */
internal enum class LabyrinthFallbackTap(val x: Int, val y: Int) {
    /** Portrait/animation advance point shared by reward, event and unknown pages. */
    CENTER(960, 780),
    /** Standard single-button modal close (角色加入 / 获得道具). */
    MODAL_CLOSE(960, 870),
    /** Bottom-center button (最终分数关闭 / 结算动画). */
    BOTTOM_CENTER(960, 1000),
    /** Bottom-right primary button (战斗结算下一步 / 结算宝箱). */
    BOTTOM_RIGHT(1640, 990),
    /** 发起挑战 button on the challenge page. */
    CHALLENGE_START(1640, 920),
    /** Purchase-complete modal confirm. */
    SHOP_PURCHASE_COMPLETE_CLOSE(960, 746),
    /** Shop exit confirmation right button. */
    SHOP_EXIT_CONFIRM(1180, 745),
    /** Event unknown-page alternating left/right probe points. */
    EVENT_LEFT(160, 780),
    EVENT_RIGHT(1760, 780),
    ;

    fun rect(frameWidth: Int, frameHeight: Int): EntryPixelRect {
        val px = (x / 1920f * frameWidth).toInt().coerceIn(1, frameWidth - 2)
        val py = (y / 1080f * frameHeight).toInt().coerceIn(1, frameHeight - 2)
        return EntryPixelRect(left = px - 1, top = py - 1, width = 2, height = 2)
    }
}

/**
 * Which fallback taps a page may issue, and which declared anchor (if any) covers that point.
 *
 * `anchorIds` is the set of template anchors whose reference rectangle is expected to contain the
 * fallback point; an empty set means the tap is a blind probe that no anchor can vouch for. The
 * approval gate uses this to distinguish "anchor missed but the button is still there" from
 * "we are guessing".
 */
internal data class LabyrinthFallbackTapPolicy(
    val page: LabyrinthEntryPageState,
    val tap: LabyrinthFallbackTap,
    val anchorIds: Set<String>,
)

internal val LABYRINTH_FALLBACK_TAP_POLICIES: List<LabyrinthFallbackTapPolicy> = listOf(
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.CHARACTER_JOINED,
        LabyrinthFallbackTap.MODAL_CLOSE,
        setOf(EntryAnchorId.JOINED_CLOSE_STANDARD),
    ),
    // The declared ITEM_REWARD_CLOSE anchor spans y 895..1035 at 1920x1080, but the historical
    // fallback taps y=870, 25 px above it. Kept as-is so phase zero changes no behaviour; the gap
    // is recorded here for the approval gate to resolve (either move the tap or the anchor).
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthFallbackTap.MODAL_CLOSE,
        emptySet(),
    ),
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        LabyrinthFallbackTap.BOTTOM_CENTER,
        setOf(EntryAnchorId.RUN_RESULT_CLOSE_BUTTON),
    ),
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
        LabyrinthFallbackTap.SHOP_PURCHASE_COMPLETE_CLOSE,
        emptySet(),
    ),
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
        LabyrinthFallbackTap.SHOP_EXIT_CONFIRM,
        setOf(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON),
    ),
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.BATTLE_CHALLENGE,
        LabyrinthFallbackTap.CHALLENGE_START,
        setOf(EntryAnchorId.BATTLE_CHALLENGE_BUTTON),
    ),
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.BATTLE_RESULT,
        LabyrinthFallbackTap.BOTTOM_RIGHT,
        setOf(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON),
    ),
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.RUN_CLEAR_CHARACTER_SUMMARY,
        LabyrinthFallbackTap.BOTTOM_RIGHT,
        setOf(EntryAnchorId.RUN_CLEAR_NEXT_BUTTON),
    ),
    // 宝箱开封结果 is a centred modal whose 确认 sits bottom-centre (2026-09-17 screenshot); the
    // bottom-right point belongs to the character summary's 下一步 and misses this dialog.
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.RUN_CLEAR_CHEST_RESULT,
        LabyrinthFallbackTap.BOTTOM_CENTER,
        setOf(EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON),
    ),
    // Same situation as ITEM_REWARD: EVENT_ANIMATION_SKIP is declared at y 850..970 while the
    // historical fallback taps y=780, which is the generic animation-advance point rather than
    // the skip button. Recorded as blind until the gate decides which one is intended.
    LabyrinthFallbackTapPolicy(
        LabyrinthEntryPageState.EVENT_ANIMATION,
        LabyrinthFallbackTap.CENTER,
        emptySet(),
    ),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.RUN_CLEAR_REWARD_ANIMATION, LabyrinthFallbackTap.BOTTOM_CENTER, emptySet()),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.RUN_CLEAR_CONGRATULATIONS, LabyrinthFallbackTap.CENTER, emptySet()),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.RUN_CLEAR_CHEST_ANIMATION, LabyrinthFallbackTap.CENTER, emptySet()),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.UNKNOWN, LabyrinthFallbackTap.CENTER, emptySet()),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.UNKNOWN, LabyrinthFallbackTap.BOTTOM_RIGHT, emptySet()),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.UNKNOWN, LabyrinthFallbackTap.EVENT_LEFT, emptySet()),
    LabyrinthFallbackTapPolicy(LabyrinthEntryPageState.UNKNOWN, LabyrinthFallbackTap.EVENT_RIGHT, emptySet()),
)

/** Fallback taps a page is allowed to issue. Pages absent from the table may not fall back at all. */
internal fun labyrinthAllowedFallbackTaps(page: LabyrinthEntryPageState): Set<LabyrinthFallbackTap> =
    LABYRINTH_FALLBACK_TAP_POLICIES.filter { it.page == page }.mapTo(linkedSetOf()) { it.tap }
