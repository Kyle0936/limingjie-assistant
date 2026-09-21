package com.landosol.toolbox.labyrinth

/**
 * Semantic identity of a post-entry tap. The human-readable label attached to a dispatch is for
 * the overlay and the debug trace only; every commit-side transition keys on this enum.
 *
 * Phase two of the revised plan: before this, [LabyrinthEntryRecognitionSession] decided which
 * state to advance by comparing the dispatched label against Chinese UI copy such as
 * `label.startsWith("选择角色：")`. Changing a message silently broke the chain it belonged to.
 */
internal enum class LabyrinthPostEntryActionKind {
    /** Generic tap with no commit semantics (single-choice event structure, plain closes). */
    GENERIC,

    // Role reward
    SELECT_ROLE_REWARD,
    /** One safe dismissal of the full-screen presentation immediately after a role choice. */
    SKIP_ROLE_REWARD_PRESENTATION,
    CLOSE_CHARACTER_JOINED,
    ADVANCE_CHARACTER_ACQUISITION,
    COLLAPSE_PORTRAIT,

    // Events
    SELECT_EVENT,
    ADVANCE_EVENT_ANIMATION,
    EVENT_FREE_ROLE_SELECT,
    EVENT_FREE_ROLE_CONFIRM,

    // Rewards
    SELECT_RELIC,
    SELECT_LINK_IMPRINT,
    CLOSE_ITEM_REWARD,

    // Shop
    SHOP_BUY_RELIC,
    SHOP_BUY_IMPRINT,
    SHOP_CONFIRM_RELIC,
    SHOP_CONFIRM_IMPRINT,
    SHOP_CLOSE_PURCHASE_COMPLETE,
    SHOP_REFRESH,
    SHOP_CONFIRM_REFRESH,
    SHOP_CLOSE,
    SHOP_CONFIRM_EXIT,

    // Battle
    BATTLE_START_CHALLENGE,
    BATTLE_RETRY,
    BATTLE_RETRY_SWITCH_MULTI,
    BATTLE_RESULT_NEXT,
    BOSS_SETTLEMENT_NEXT,

    // EX detail probe
    EX_PROBE_DETAIL,
    EX_CLOSE_DETAIL,

    // Final settlement
    CLOSE_FINAL_SCORE,
    CLOSE_FINAL_ITEM,
    ADVANCE_FINAL_SETTLEMENT,
}

/** A planned post-entry tap: what it means, what to show, and where to tap. */
internal data class LabyrinthPostEntryTapPlan(
    val kind: LabyrinthPostEntryActionKind,
    val label: String,
    val rect: com.landosol.toolbox.labyrinth.vision.EntryPixelRect,
)
