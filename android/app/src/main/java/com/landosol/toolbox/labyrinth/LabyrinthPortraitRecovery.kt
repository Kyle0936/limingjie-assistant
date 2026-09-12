package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** Short-lived reward evidence survives loading, but never authorizes a global UNKNOWN tap. */
internal class LabyrinthPortraitRecovery {
    private var evidenceAt: Long? = null
    private var unknownSince: Long? = null
    private var lastAttemptAt: Long? = null
    private var attempts = 0

    fun observe(page: LabyrinthEntryPageState, rewardEvidence: Boolean, now: Long) {
        when {
            rewardEvidence -> {
                evidenceAt = now
                unknownSince = null
                // Repeated reward frames must not replenish the click budget.
            }
            page == LabyrinthEntryPageState.UNKNOWN -> {
                if (unknownSince == null) unknownSince = now
            }
            page == LabyrinthEntryPageState.GAME_LOADING_PROGRESS ||
                page == LabyrinthEntryPageState.PRE_HOME_DATA_LOADING -> unknownSince = null
            else -> reset()
        }
    }

    fun canAttempt(now: Long, protectedFrame: Boolean): Boolean {
        val evidence = evidenceAt ?: return false
        val unknown = unknownSince ?: return false
        return !protectedFrame && attempts < 3 && now - evidence in 0..30_000L &&
            now - unknown >= 2_000L &&
            (lastAttemptAt?.let { now - it >= 2_000L } ?: true)
    }

    fun recordAttempt(now: Long) {
        attempts++
        lastAttemptAt = now
    }

    fun reset() {
        evidenceAt = null
        unknownSince = null
        lastAttemptAt = null
        attempts = 0
    }
}

// A partial match is enough to veto recovery; normal page handling retains its own thresholds.
internal fun labyrinthPortraitRecoveryBlocked(result: LabyrinthEntryFrameResult): Boolean =
    result.relicDetailObservation != null || result.nodeMoveConfirmation != null ||
        result.shopObservation != null || labyrinthHasBattleControls(result) ||
        PORTRAIT_RECOVERY_PROTECTED_ANCHORS.any {
            result.observation.anchorScores[it] >= 0.45 ||
                (result.anchorMatches[it]?.score ?: 0.0) >= 0.45
        }

internal fun labyrinthHasBattleControls(result: LabyrinthEntryFrameResult): Boolean =
    BATTLE_CONTROL_ANCHORS.any {
        result.observation.anchorScores[it] >= 0.45 ||
            (result.anchorMatches[it]?.score ?: 0.0) >= 0.45
    }

private val BATTLE_CONTROL_ANCHORS = listOf(
    EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON,
    EntryAnchorId.BATTLE_IN_PROGRESS_AUTO_BUTTON,
)

private val PORTRAIT_RECOVERY_PROTECTED_ANCHORS = listOf(
    EntryAnchorId.LOADING_NOTICE, EntryAnchorId.LOADING_NOTICE_STANDARD,
    EntryAnchorId.LOADING_PROGRESS, EntryAnchorId.DATA_CONNECTING, EntryAnchorId.LOADING_INDICATOR,
    EntryAnchorId.SESSION_ERROR_TITLE, EntryAnchorId.SESSION_RETURN_TITLE,
    EntryAnchorId.SESSION_DATE_CHANGE_TITLE, EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM,
    EntryAnchorId.SHOP_TITLE, EntryAnchorId.SHOP_BUY_BUTTON, EntryAnchorId.SHOP_REFRESH_BUTTON,
    EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE, EntryAnchorId.SHOP_PURCHASE_COMPLETE_TITLE,
    EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE, EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON,
    EntryAnchorId.SELECTION_HEADER, EntryAnchorId.SELECTION_HEADER_STANDARD,
    EntryAnchorId.ROLE_REWARD_TITLE, EntryAnchorId.ROLE_REWARD_SELECT_BUTTON,
    EntryAnchorId.LINK_CHOICE_TITLE, EntryAnchorId.LINK_CHOICE_INSTRUCTION,
    EntryAnchorId.EVENT_CHOICE_TITLE, EntryAnchorId.EVENT_CHOICE_INSTRUCTION,
    EntryAnchorId.RELIC_CHOICE_INSTRUCTION,
    EntryAnchorId.BATTLE_TEAM_HEADER, EntryAnchorId.BATTLE_TEAM_START_BUTTON,
    EntryAnchorId.JOINED_HEADER, EntryAnchorId.JOINED_HEADER_STANDARD,
    EntryAnchorId.JOINED_CLOSE, EntryAnchorId.JOINED_CLOSE_STANDARD,
    EntryAnchorId.ITEM_REWARD_TITLE, EntryAnchorId.ITEM_REWARD_CLOSE,
    EntryAnchorId.NODE_RETREAT, EntryAnchorId.NODE_RETREAT_STANDARD,
    EntryAnchorId.NODE_RETURN, EntryAnchorId.NODE_RETURN_STANDARD,
)
