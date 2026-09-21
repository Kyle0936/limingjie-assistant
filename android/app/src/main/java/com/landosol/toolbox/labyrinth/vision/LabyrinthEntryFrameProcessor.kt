package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.node.FinalBossPlatformLocator
import com.landosol.toolbox.labyrinth.node.FinalBossPlatformMatch

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeClassifier
import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

object EntryAnchorId {
    const val TITLE_LOGO = "entry.title.logo"
    const val TITLE_TAP_PROMPT = "entry.title.tap_prompt"
    const val LOADING_NOTICE = "entry.loading.notice"
    const val LOADING_NOTICE_STANDARD = "entry.loading.notice_standard"
    const val LOADING_PROGRESS = "entry.loading.progress"
    const val DATA_CONNECTING = "entry.data.connecting"
    const val LOADING_INDICATOR = "entry.loading.indicator"
    const val ANNOUNCEMENT_HEADER = "entry.announcement.header"
    const val ANNOUNCEMENT_CLOSE = "entry.announcement.close"
    const val HOME_LEVEL = "entry.home.level"
    const val HOME_ADVENTURE = "home.adventure"
    const val ADVENTURE_HEADER = "entry.adventure.header"
    const val ADVENTURE_HEADER_STANDARD = "entry.adventure.header_standard"
    const val LABYRINTH_ENTRY = "entry.adventure.labyrinth"
    const val DAWN_START = "entry.dawn.start"
    const val DAWN_START_STANDARD = "entry.dawn.start_standard"
    const val DAWN_ACTIVE = "entry.dawn.active"
    const val DAWN_ACTIVE_STANDARD = "entry.dawn.active_standard"
    const val DAWN_TICKET_LABEL = "entry.dawn.ticket_label"
    const val DAWN_TICKET_LABEL_STANDARD = "entry.dawn.ticket_label_standard"
    const val SELECTION_HEADER = "entry.selection.header"
    const val SELECTION_HEADER_STANDARD = "entry.selection.header_standard"
    const val SELECTION_COUNT_NONE = "entry.selection.count_none"
    const val SELECTION_COUNT_NONE_STANDARD = "entry.selection.count_none_standard"
    const val SELECTION_COUNT_COMPLETE = "entry.selection.count_complete"
    const val SELECTION_COUNT_COMPLETE_STANDARD = "entry.selection.count_complete_standard"
    const val INVITE_DISABLED = "entry.selection.invite_disabled"
    const val INVITE_DISABLED_STANDARD = "entry.selection.invite_disabled_standard"
    const val INVITE_ENABLED = "entry.selection.invite_enabled"
    const val INVITE_ENABLED_STANDARD = "entry.selection.invite_enabled_standard"
    const val ROLE_REWARD_TITLE = "entry.selection.role_reward.title"
    const val ROLE_REWARD_INSTRUCTION = "entry.selection.role_reward.instruction"
    const val ROLE_REWARD_SELECT_BUTTON = "entry.selection.role_reward.select_button"
    const val EVENT_CHOICE_TITLE = "entry.event_choice.title"
    const val EVENT_CHOICE_INSTRUCTION = "entry.event_choice.instruction"
    const val EVENT_CHOICE_OPTION = "entry.event_choice.option"
    const val EVENT_SINGLE_CHOICE_TRIGGER_TITLE = "entry.event_choice.single.trigger_title"
    const val EVENT_SINGLE_CHOICE_SELECT_BUTTON = "entry.event_choice.single.select_button"
    const val EVENT_ANIMATION_TITLE = "entry.event_animation.title"
    const val EVENT_ANIMATION_SKIP = "entry.event_animation.skip"
    const val LINK_CHOICE_TITLE = "entry.link_choice.title"
    const val LINK_CHOICE_INSTRUCTION = "entry.link_choice.instruction"
    const val LINK_CHOICE_REMAINING = "entry.link_choice.remaining"
    const val LINK_CHOICE_CARD_SIGNATURE = "entry.link_choice.card_signature"
    const val RELIC_CHOICE_INSTRUCTION = "entry.relic_choice.instruction"
    const val ITEM_REWARD_TITLE = "entry.item_reward.title"
    const val ITEM_REWARD_INSTRUCTION = "entry.item_reward.instruction"
    const val ITEM_REWARD_CLOSE = "entry.item_reward.close"
    const val SHOP_TITLE = "entry.shop.title"
    const val SHOP_INSTRUCTION = "entry.shop.instruction"
    const val SHOP_CLOSE = "entry.shop.close"
    const val SHOP_BUY_BUTTON = "entry.shop.buy_button"
    const val SHOP_REFRESH_BUTTON = "entry.shop.refresh_button"
    const val SHOP_PURCHASE_CONFIRMATION_TITLE = "entry.shop.purchase_confirmation.title"
    const val SHOP_PURCHASE_COMPLETE_TITLE = "entry.shop.purchase_complete.title"
    const val SHOP_EXIT_CONFIRMATION_TITLE = "entry.shop.exit_confirmation.title"
    const val SHOP_EXIT_CONFIRM_BUTTON = "entry.shop.exit_confirm_button"
    const val JOINED_HEADER = "entry.joined.header"
    const val JOINED_HEADER_STANDARD = "entry.joined.header_standard"
    const val JOINED_CLOSE = "entry.joined.close"
    const val JOINED_CLOSE_STANDARD = "entry.joined.close_standard"
    const val NODE_HEADER = "entry.node.header"
    const val NODE_HEADER_STANDARD = "entry.node.header_standard"
    const val NODE_CHARACTERS = "entry.node.characters"
    const val NODE_CHARACTERS_STANDARD = "entry.node.characters_standard"
    const val NODE_RELICS = "entry.node.relics"
    const val NODE_RELICS_STANDARD = "entry.node.relics_standard"
    const val NODE_RETREAT = "entry.node.retreat"
    const val NODE_RETREAT_STANDARD = "entry.node.retreat_standard"
    const val NODE_RETURN = "entry.node.return"
    const val NODE_RETURN_STANDARD = "entry.node.return_standard"
    const val BATTLE_CHALLENGE_HEADER_NORMAL = "entry.battle_challenge.header.normal"
    const val BATTLE_CHALLENGE_SUFFIX_NORMAL = "entry.battle_challenge.suffix.normal"
    const val BATTLE_CHALLENGE_SUFFIX_EXTREME = "entry.battle_challenge.suffix.extreme"
    const val BATTLE_CHALLENGE_BUTTON = "entry.battle_challenge.button"
    const val BATTLE_CHALLENGE_VIEW_MAP_BUTTON = "entry.battle_challenge.view_map_button"
    const val BATTLE_TEAM_HEADER = "entry.battle_team.header"
    const val BATTLE_TEAM_FILTER_BAR = "entry.battle_team.filter_bar"
    const val BATTLE_TEAM_CURRENT_MEMBERS_SUMMARY = "entry.battle_team.current_members_summary"
    const val BATTLE_TEAM_CANCEL_BUTTON = "entry.battle_team.cancel_button"
    const val BATTLE_TEAM_START_BUTTON = "entry.battle_team.start_button"
    const val SESSION_ERROR_TITLE = "session.error.title"
    const val SESSION_RETURN_TITLE = "session.return.title"
    const val SESSION_DATE_CHANGE_TITLE = "session.date_change.title"
    const val SESSION_DATE_CHANGE_CONFIRM = "session.date_change.confirm"
    const val BATTLE_IN_PROGRESS_MENU_BUTTON = "battle.in_progress.menu_button"
    const val BATTLE_IN_PROGRESS_AUTO_BUTTON = "battle.in_progress.auto_button"
    const val BATTLE_RESULT_REWARD_BANNER = "battle.result.reward_banner"
    const val BATTLE_RESULT_NEXT_BUTTON = "battle.result.next_button"
    const val BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON = "battle.result.boss_summary.next_button"
    const val RUN_RESULT_LOGO = "run.result.logo"
    const val RUN_RESULT_CLOSE_BUTTON = "run.result.close_button"
    const val RUN_CLEAR_CONGRATULATIONS_TITLE = "run.clear.congratulations.title"
    const val RUN_CLEAR_CHARACTER_SUMMARY_TITLE = "run.clear.character_summary.title"
    const val RUN_CLEAR_NEXT_BUTTON = "run.clear.next_button"
    const val RUN_CLEAR_REWARD_ANIMATION_TITLE = "run.clear.reward_animation.title"
    const val RUN_CLEAR_CHEST_ANIMATION_TITLE = "run.clear.chest_animation.title"
    const val RUN_CLEAR_CHEST_RESULT_TITLE = "run.clear.chest_result.title"
    const val RUN_CLEAR_CHEST_CONFIRM_BUTTON = "run.clear.chest_confirm_button"
    /**
     * 黎明界主页底栏最左的「我的主页」标签；会话失效触发点，只在识别到时点击。
     * 2026-09-17 实测：点当前已选中的「冒险」标签不联网，不会触发返回标题；「我的主页」会。
     */
    const val DAWN_HOME_MY_HOME_TAB = "dawn.home.my_home_tab"
    /** 主页面板顶部的「迷宫遗物效果」标题（迷宫大师开局赠送遗物弹窗）。 */
    const val RELIC_EFFECT_TITLE = "entry.relic_effect.title"
    const val RELIC_EFFECT_INSTRUCTION = "entry.relic_effect.instruction"

    val required = setOf(
        TITLE_LOGO,
        TITLE_TAP_PROMPT,
        LOADING_NOTICE,
        LOADING_NOTICE_STANDARD,
        LOADING_PROGRESS,
        DATA_CONNECTING,
        LOADING_INDICATOR,
        ANNOUNCEMENT_HEADER,
        ANNOUNCEMENT_CLOSE,
        HOME_LEVEL,
        HOME_ADVENTURE,
        ADVENTURE_HEADER,
        ADVENTURE_HEADER_STANDARD,
        LABYRINTH_ENTRY,
        DAWN_START,
        DAWN_START_STANDARD,
        DAWN_ACTIVE,
        DAWN_ACTIVE_STANDARD,
        DAWN_TICKET_LABEL,
        DAWN_TICKET_LABEL_STANDARD,
        SELECTION_HEADER,
        SELECTION_HEADER_STANDARD,
        SELECTION_COUNT_NONE,
        SELECTION_COUNT_NONE_STANDARD,
        SELECTION_COUNT_COMPLETE,
        SELECTION_COUNT_COMPLETE_STANDARD,
        INVITE_DISABLED,
        INVITE_DISABLED_STANDARD,
        INVITE_ENABLED,
        INVITE_ENABLED_STANDARD,
        ROLE_REWARD_TITLE,
        ROLE_REWARD_INSTRUCTION,
        ROLE_REWARD_SELECT_BUTTON,
        LINK_CHOICE_TITLE,
        LINK_CHOICE_INSTRUCTION,
        LINK_CHOICE_REMAINING,
        LINK_CHOICE_CARD_SIGNATURE,
        RELIC_CHOICE_INSTRUCTION,
        ITEM_REWARD_TITLE,
        ITEM_REWARD_INSTRUCTION,
        ITEM_REWARD_CLOSE,
        SHOP_TITLE,
        SHOP_INSTRUCTION,
        SHOP_CLOSE,
        SHOP_BUY_BUTTON,
        SHOP_REFRESH_BUTTON,
        SHOP_PURCHASE_CONFIRMATION_TITLE,
        SHOP_PURCHASE_COMPLETE_TITLE,
        SHOP_EXIT_CONFIRMATION_TITLE,
        SHOP_EXIT_CONFIRM_BUTTON,
        JOINED_HEADER,
        JOINED_HEADER_STANDARD,
        JOINED_CLOSE,
        JOINED_CLOSE_STANDARD,
        NODE_HEADER,
        NODE_HEADER_STANDARD,
        NODE_CHARACTERS,
        NODE_CHARACTERS_STANDARD,
        NODE_RELICS,
        NODE_RELICS_STANDARD,
        NODE_RETREAT,
        NODE_RETREAT_STANDARD,
        NODE_RETURN,
        NODE_RETURN_STANDARD,
        BATTLE_CHALLENGE_HEADER_NORMAL,
        BATTLE_CHALLENGE_SUFFIX_NORMAL,
        BATTLE_CHALLENGE_SUFFIX_EXTREME,
        BATTLE_CHALLENGE_BUTTON,
        BATTLE_CHALLENGE_VIEW_MAP_BUTTON,
        BATTLE_TEAM_HEADER,
        BATTLE_TEAM_FILTER_BAR,
        BATTLE_TEAM_CURRENT_MEMBERS_SUMMARY,
        BATTLE_TEAM_CANCEL_BUTTON,
        BATTLE_TEAM_START_BUTTON,
        SESSION_ERROR_TITLE,
        SESSION_RETURN_TITLE,
        SESSION_DATE_CHANGE_TITLE,
        SESSION_DATE_CHANGE_CONFIRM,
        BATTLE_IN_PROGRESS_MENU_BUTTON,
        BATTLE_IN_PROGRESS_AUTO_BUTTON,
        BATTLE_RESULT_REWARD_BANNER,
        BATTLE_RESULT_NEXT_BUTTON,
        RUN_RESULT_LOGO,
        RUN_RESULT_CLOSE_BUTTON,
    )

    /** Optional anchors are loaded only when a resource pack provides them. */
    val optional = setOf(
        EVENT_CHOICE_TITLE,
        EVENT_CHOICE_INSTRUCTION,
        EVENT_CHOICE_OPTION,
        EVENT_SINGLE_CHOICE_TRIGGER_TITLE,
        EVENT_SINGLE_CHOICE_SELECT_BUTTON,
        EVENT_ANIMATION_TITLE,
        EVENT_ANIMATION_SKIP,
        RUN_CLEAR_CONGRATULATIONS_TITLE,
        RUN_CLEAR_CHARACTER_SUMMARY_TITLE,
        RUN_CLEAR_NEXT_BUTTON,
        RUN_CLEAR_REWARD_ANIMATION_TITLE,
        RUN_CLEAR_CHEST_ANIMATION_TITLE,
        RUN_CLEAR_CHEST_RESULT_TITLE,
        RUN_CLEAR_CHEST_CONFIRM_BUTTON,
        BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON,
    )
}

/**
 * INITIAL_CHARACTER_SELECTION is shared by the fresh opening roster and later role-reward
 * three-choice pages. The opening roster owns the frame whenever its own persistent shell is
 * visible, even if role-reward title/instruction templates spuriously match the same frame.
 */
internal fun labyrinthOpeningRosterOwnsInitialSelection(
    anchorScores: LabyrinthAnchorScores,
    minimumScore: Double = 0.45,
): Boolean {
    val header = maxOf(
        anchorScores[EntryAnchorId.SELECTION_HEADER],
        anchorScores[EntryAnchorId.SELECTION_HEADER_STANDARD],
    )
    val invite = maxOf(
        anchorScores[EntryAnchorId.INVITE_DISABLED],
        anchorScores[EntryAnchorId.INVITE_DISABLED_STANDARD],
        anchorScores[EntryAnchorId.INVITE_ENABLED],
        anchorScores[EntryAnchorId.INVITE_ENABLED_STANDARD],
    )
    val count = maxOf(
        anchorScores[EntryAnchorId.SELECTION_COUNT_NONE],
        anchorScores[EntryAnchorId.SELECTION_COUNT_NONE_STANDARD],
        anchorScores[EntryAnchorId.SELECTION_COUNT_COMPLETE],
        anchorScores[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD],
    )
    return header >= minimumScore && maxOf(invite, count) >= minimumScore
}

internal fun labyrinthRoleRewardOwnsInitialSelection(
    anchorScores: LabyrinthAnchorScores,
    minimumScore: Double = 0.45,
): Boolean = !labyrinthOpeningRosterOwnsInitialSelection(anchorScores, minimumScore) &&
    minOf(
        anchorScores[EntryAnchorId.ROLE_REWARD_TITLE],
        anchorScores[EntryAnchorId.ROLE_REWARD_INSTRUCTION],
    ) >= minimumScore

data class EntryReferenceSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

data class EntryReferenceRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0)
    }
}

enum class EntryReferenceMapping {
    COVER,
    FIT,
}

data class EntryPixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0)
    }
}

data class EntryAnchorDefinition(
    val id: String,
    val referenceSize: EntryReferenceSize,
    val referenceRect: EntryReferenceRect,
    val mapping: EntryReferenceMapping = EntryReferenceMapping.COVER,
)

data class LabyrinthEntryTemplateSet(val values: Map<String, PixelImage>) {
    init {
        require(values.keys.containsAll(EntryAnchorId.required)) {
            "Missing entry templates: ${EntryAnchorId.required - values.keys}"
        }
    }

    operator fun get(id: String): PixelImage = requireNotNull(values[id]) { "Missing entry template: $id" }
}

data class LabyrinthEntryFrameResult(
    val observation: LabyrinthEntryPageObservation,
    val matchedFeatures: List<String>,
    val elapsedMillis: Long,
    val anchorMatches: Map<String, EntryAnchorMatch> = emptyMap(),
    val nodeClassifications: List<NodeClassification> = emptyList(),
    val characterMatches: List<LabyrinthCharacterMatch> = emptyList(),
    val openingCharacterMatches: List<LabyrinthBattleCharacterMatch> = emptyList(),
    val openingCharacterSelection: LabyrinthBattleTeamObservation? = null,
    val battleTeamSelection: LabyrinthBattleTeamObservation? = null,
    val linkChoiceSelection: LabyrinthLinkChoiceObservation? = null,
    val relicChoiceSelection: LabyrinthRelicChoiceObservation? = null,
    val eventChoiceSelection: LabyrinthEventChoiceObservation? = null,
    val nodeRelicStackObservation: LabyrinthNodeRelicStackObservation? = null,
    val relicDetailObservation: LabyrinthRelicDetailObservation? = null,
    val shopObservation: LabyrinthShopObservation? = null,
    val nodeMoveConfirmation: LabyrinthNodeMoveConfirmationObservation? = null,
    val battleFailure: LabyrinthBattleFailureObservation? = null,
    /** 结束确认 dialog over the failure page; non-null only while such a dialog is open. */
    val battleEndConfirmation: LabyrinthBattleEndConfirmationObservation? = null,
    val exChallenge: LabyrinthExChallengeObservation? = null,
    val exEncounter: LabyrinthExEncounterObservation? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val stageMillis: Map<String, Long> = emptyMap(),
    val nodeSearchMode: String = "none",
    /** Candidate windows the node classifier scored on this frame; 0 when no node scan ran. */
    val nodeSearchWindowCount: Int = 0,
    /** Pixel-only map viewport signature, independent from successful node classification. */
    val nodeViewportSignature: String = "",
    val nodeViewportPixels: com.landosol.toolbox.labyrinth.node.NodeViewportPixels? = null,
    val finalBossPlatforms: List<FinalBossPlatformMatch> = emptyList(),
    val finalBossPlatformCandidates: List<FinalBossPlatformMatch> = emptyList(),
)

data class EntryAnchorMatch(
    val score: Double,
    val rect: EntryPixelRect,
)

/** Measures every stable entry-flow anchor but never produces an action. */
class LabyrinthEntryFrameProcessor(
    private val templates: LabyrinthEntryTemplateSet,
    private val classifier: LabyrinthEntryPageClassifier = LabyrinthEntryPageClassifier(),
    private val matcher: GradientTemplateMatcher = GradientTemplateMatcher(),
    private val nodeClassifier: LabyrinthNodeClassifier = LabyrinthNodeClassifier(),
    private val nodeTemplates: NodeTemplateSet = NodeTemplateSet(emptyMap()),
    private val characterRecognizer: LabyrinthCharacterRecognizer = LabyrinthCharacterRecognizer(),
    private val battleTeamRecognizer: LabyrinthBattleTeamRecognizer = LabyrinthBattleTeamRecognizer(),
    private val linkChoiceRecognizer: LabyrinthLinkChoiceRecognizer = LabyrinthLinkChoiceRecognizer(),
    private val relicChoiceRecognizer: LabyrinthRelicRecognizer = LabyrinthRelicRecognizer(),
    private val shopRecognizer: LabyrinthShopRecognizer = LabyrinthShopRecognizer(),
    private val nodeMoveConfirmationDetector: LabyrinthNodeMoveConfirmationDetector =
        LabyrinthNodeMoveConfirmationDetector(),
    private val battleFailureDetector: LabyrinthBattleFailureDetector = LabyrinthBattleFailureDetector(),
    private val battleEndConfirmationDetector: LabyrinthBattleEndConfirmationDetector =
        LabyrinthBattleEndConfirmationDetector(),
    private val exChallengeDetector: LabyrinthExChallengeDetector = LabyrinthExChallengeDetector(),
    private val finalBossOnly: () -> Boolean = { false },
    private val nodeSearchHint: () -> NodeSearchHint? = { null },
    /**
     * Whether a map-node scan can be consumed right now. Before the route session exists the
     * session cannot bind or act on any classification, so the scan is pure waste; on a slow host
     * that wasted first frame took 49 s in the 2026-09-15 20:58 bundle.
     */
    private val nodeScanRequested: () -> Boolean = { true },
) {
    private val finalBossPlatformLocator = FinalBossPlatformLocator(
        nodeTemplates.templates["node.boss.platform"],
    )
    fun process(
        frame: PixelImage,
        deferRoleRewardPortraits: Boolean = false,
        skipJoinedCharacters: Boolean = false,
    ): LabyrinthEntryFrameResult {
        if (frame.width <= frame.height) return unsupportedOrientation(frame)
        lateinit var observation: LabyrinthEntryPageObservation
        var matchedFeatures = emptyList<String>()
        var anchorMatches = emptyMap<String, EntryAnchorMatch>()
        var nodeClassifications = emptyList<NodeClassification>()
        var finalBossPlatforms = emptyList<FinalBossPlatformMatch>()
        var finalBossPlatformCandidates = emptyList<FinalBossPlatformMatch>()
        var characterMatches = emptyList<LabyrinthCharacterMatch>()
        var openingCharacterMatches = emptyList<LabyrinthBattleCharacterMatch>()
        var openingCharacterSelection: LabyrinthBattleTeamObservation? = null
        var battleTeamSelection: LabyrinthBattleTeamObservation? = null
        var linkChoiceSelection: LabyrinthLinkChoiceObservation? = null
        var relicChoiceSelection: LabyrinthRelicChoiceObservation? = null
        var shopObservation: LabyrinthShopObservation? = null
        var nodeMoveConfirmation: LabyrinthNodeMoveConfirmationObservation? = null
        var battleFailure: LabyrinthBattleFailureObservation? = null
        var battleEndConfirmation: LabyrinthBattleEndConfirmationObservation? = null
        var exChallenge: LabyrinthExChallengeObservation? = null
        var nodesNanos = 0L
        var bossPlatformNanos = 0L
        var nodeSearchMode = "none"
        var nodeSearchWindowCount = 0
        var nodeViewportSignature = ""
        var nodeViewportPixels: com.landosol.toolbox.labyrinth.node.NodeViewportPixels? = null
        val elapsedNanos = measureNanoTime {
            // The entry classifier scores many independent fixed ROIs in one frame.  Prepare
            // luminance and gradients once so every anchor reads the same compact cache instead
            // of recalculating neighbouring pixels for every sampled template point.
            matcher.prepareFrame(frame)
            try {
                val measurements = DEFINITIONS_BY_ID.mapValues { (id, definitions) ->
                val template = templates.values[id]
                    ?: return@mapValues null
                definitions.mapNotNull { definition ->
                    val mapped = when (definition.mapping) {
                        EntryReferenceMapping.COVER -> ReferenceCoverMapper.map(
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            referenceSize = definition.referenceSize,
                            referenceRect = definition.referenceRect,
                        )
                        EntryReferenceMapping.FIT -> ReferenceFitMapper.map(
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            referenceSize = definition.referenceSize,
                            referenceRect = definition.referenceRect,
                        )
                    }
                    mapped?.let { rect ->
                        EntryAnchorMatch(
                            score = matcher.score(frame, rect, template),
                            rect = rect,
                        )
                    }
                }.maxByOrNull(EntryAnchorMatch::score)
            }
            val scores = measurements.mapValues { (_, measurement) -> measurement?.score ?: 0.0 }
            anchorMatches = measurements.mapNotNull { (id, measurement) ->
                measurement?.let { id to it }
            }.toMap()
            val anchorScores = LabyrinthAnchorScores(scores)
            val classified = classifier.classify(anchorScores)
            battleFailure = battleFailureDetector.detect(frame)
            // The dialog dims the page behind it; only probe when the page still looks like a
            // failure page (or classified as nothing), never over a recognised unrelated page.
            battleEndConfirmation = if (battleFailure != null || classified.state == LabyrinthEntryPageState.UNKNOWN) {
                battleEndConfirmationDetector.detect(frame)
            } else {
                null
            }
            observation = battleFailure?.let { failure ->
                classified.copy(
                    state = LabyrinthEntryPageState.BATTLE_FAILED,
                    confidence = failure.confidence,
                    stateScores = classified.stateScores +
                        (LabyrinthEntryPageState.BATTLE_FAILED to failure.confidence),
                    reason = null,
                )
            } ?: classified
            exChallenge = exChallengeDetector.detect(frame, observation.state)
            nodeMoveConfirmation = nodeMoveConfirmationDetector.detect(frame)
            if (
                observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
                    labyrinthRoleRewardOwnsInitialSelection(
                        anchorScores = anchorScores,
                        minimumScore = MATCHED_FEATURE_MIN_SCORE,
                    )
            ) {
                characterMatches = if (deferRoleRewardPortraits) characterRecognizer.roleRewardSlots(frame)
                    else characterRecognizer.recognizeRoleRewardChoices(frame)
            } else if (observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
                openingCharacterSelection = battleTeamRecognizer.recognizeOpening(frame)
                openingCharacterMatches = openingCharacterSelection?.visibleCharacters.orEmpty()
            }
            if (
                observation.state == LabyrinthEntryPageState.CHARACTER_JOINED &&
                    maxOf(
                        anchorScores[EntryAnchorId.JOINED_HEADER],
                        anchorScores[EntryAnchorId.JOINED_HEADER_STANDARD],
                    ) >= MATCHED_FEATURE_MIN_SCORE &&
                    maxOf(
                        anchorScores[EntryAnchorId.JOINED_CLOSE],
                        anchorScores[EntryAnchorId.JOINED_CLOSE_STANDARD],
                    ) >= MATCHED_FEATURE_MIN_SCORE
            ) {
                val shopBackground = listOf(EntryAnchorId.SHOP_TITLE, EntryAnchorId.SHOP_INSTRUCTION,
                    EntryAnchorId.SHOP_REFRESH_BUTTON, EntryAnchorId.SHOP_CLOSE)
                    .count { anchorScores[it] >= 0.65 } >= 2
                if (!skipJoinedCharacters || shopBackground) {
                    characterMatches = characterRecognizer.recognizeJoinedCharacters(frame)
                }
            }
            if (
                observation.state == LabyrinthEntryPageState.NODE_SELECTION &&
                    // A movement dialog leaves the map visible behind it, so the generic page
                    // classifier can still report NODE_SELECTION. The dialog has priority and
                    // does not need the expensive map-node classification pass.
                    nodeMoveConfirmation == null &&
                    nodeTemplates.templates.isNotEmpty() &&
                    nodeScanRequested()
            ) {
                nodeViewportSignature = nodeClassifier.viewportSignatureKey(frame)
                nodeViewportPixels = com.landosol.toolbox.labyrinth.node.NodeViewportPixels.sample(frame)
                if (finalBossOnly()) {
                    // Route already proves the only destination. Animated scenery must not force
                    // an expensive ordinary-node scan; do not reuse its old viewport evidence.
                    nodeClassifier.resetTracking()
                    nodeSearchMode = "final-boss-platform"
                } else {
                    nodesNanos = measureNanoTime {
                        nodeClassifications = nodeClassifier.classifyMapNodes(
                            frame = frame,
                            templates = nodeTemplates,
                            searchHint = nodeSearchHint(),
                        )
                    }
                    nodeSearchMode = nodeClassifier.lastSearchMode
                    nodeSearchWindowCount = nodeClassifier.lastSearchWindowCount
                }
                bossPlatformNanos = measureNanoTime {
                    finalBossPlatforms = finalBossPlatformLocator.locate(frame)
                    finalBossPlatformCandidates = finalBossPlatformLocator.lastCandidates
                }
            } else {
                nodeClassifier.resetTracking()
            }
            if (observation.state == LabyrinthEntryPageState.BATTLE_TEAM_SELECTION) {
                battleTeamSelection = battleTeamRecognizer.recognize(frame)
            } else if (
                observation.state != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION ||
                    openingCharacterSelection == null
            ) {
                battleTeamRecognizer.reset()
            }
            if (observation.state == LabyrinthEntryPageState.LINK_CHOICE) {
                linkChoiceSelection = linkChoiceRecognizer.recognize(frame)
            }
            if (observation.state == LabyrinthEntryPageState.RELIC_CHOICE) {
                relicChoiceSelection = relicChoiceRecognizer.recognize(frame)
            }
            if (
                observation.state == LabyrinthEntryPageState.SHOP ||
                    observation.state == LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION ||
                    observation.state == LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE ||
                    observation.state == LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION
            ) {
                shopObservation = shopRecognizer.recognize(
                    frame = frame,
                    dialogState = observation.state.toShopDialogState(),
                )
            }
            matchedFeatures = scores.entries
                .filter { it.value >= MATCHED_FEATURE_MIN_SCORE }
                .sortedByDescending(Map.Entry<String, Double>::value)
                .map(Map.Entry<String, Double>::key)
                .let { features ->
                    if (battleFailure != null) listOf("battle.failure.visual") + features else features
                }
            } finally {
                // Do not retain a full frame when a recognizer aborts midway through a pass.
                matcher.clearPreparedFrame()
            }
        }
        return LabyrinthEntryFrameResult(
            observation = observation,
            matchedFeatures = matchedFeatures,
            elapsedMillis = elapsedNanos / 1_000_000,
            stageMillis = mapOf("nodes" to nodesNanos / 1_000_000,
                "bossPlatform" to bossPlatformNanos / 1_000_000,
                "pageAndOther" to (elapsedNanos - nodesNanos - bossPlatformNanos) / 1_000_000),
            nodeSearchMode = nodeSearchMode,
            nodeSearchWindowCount = nodeSearchWindowCount,
            nodeViewportSignature = nodeViewportSignature,
            nodeViewportPixels = nodeViewportPixels,
            anchorMatches = anchorMatches,
            nodeClassifications = nodeClassifications,
            finalBossPlatforms = finalBossPlatforms,
            finalBossPlatformCandidates = finalBossPlatformCandidates,
            characterMatches = characterMatches,
            openingCharacterMatches = openingCharacterMatches,
            openingCharacterSelection = openingCharacterSelection,
            battleTeamSelection = battleTeamSelection,
            linkChoiceSelection = linkChoiceSelection,
            relicChoiceSelection = relicChoiceSelection,
            shopObservation = shopObservation,
            nodeMoveConfirmation = nodeMoveConfirmation,
            battleFailure = battleFailure,
            battleEndConfirmation = battleEndConfirmation,
            exChallenge = exChallenge,
            frameWidth = frame.width,
            frameHeight = frame.height,
        )
    }

    private fun unsupportedOrientation(frame: PixelImage): LabyrinthEntryFrameResult {
        val scores = LabyrinthAnchorScores(EntryAnchorId.required.associateWith { 0.0 })
        return LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.UNKNOWN,
                confidence = 0.0,
                stateScores = emptyMap(),
                anchorScores = scores,
                reason = "entry-frame-not-landscape",
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 0,
            anchorMatches = emptyMap(),
            shopObservation = null,
            frameWidth = frame.width,
            frameHeight = frame.height,
        )
    }

    internal companion object {
        const val MATCHED_FEATURE_MIN_SCORE = 0.45
        val WIDE_REFERENCE = EntryReferenceSize(2780, 1264)
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)

        fun wide(id: String, x: Int, y: Int, width: Int, height: Int) =
            EntryAnchorDefinition(id, WIDE_REFERENCE, EntryReferenceRect(x, y, width, height))

        fun standard(id: String, x: Int, y: Int, width: Int, height: Int) =
            EntryAnchorDefinition(
                id,
                STANDARD_REFERENCE,
                EntryReferenceRect(x, y, width, height),
                EntryReferenceMapping.FIT,
            )

        val DEFINITIONS = listOf(
            wide(EntryAnchorId.TITLE_LOGO, 952, 754, 847, 323),
            wide(EntryAnchorId.TITLE_TAP_PROMPT, 1152, 1129, 478, 70),
            standard(EntryAnchorId.TITLE_LOGO, 586, 644, 723, 276),
            standard(EntryAnchorId.TITLE_TAP_PROMPT, 757, 965, 408, 59),
            wide(EntryAnchorId.LOADING_NOTICE, 1089, 1118, 455, 43),
            standard(EntryAnchorId.LOADING_NOTICE_STANDARD, 704, 947, 410, 45),
            wide(EntryAnchorId.LOADING_PROGRESS, 897, 1160, 997, 35),
            wide(EntryAnchorId.DATA_CONNECTING, 2142, 55, 426, 65),
            wide(EntryAnchorId.LOADING_INDICATOR, 2194, 1115, 178, 72),
            wide(EntryAnchorId.ANNOUNCEMENT_HEADER, 311, 61, 2157, 175),
            wide(EntryAnchorId.ANNOUNCEMENT_CLOSE, 1162, 1059, 475, 132),
            standard(EntryAnchorId.HOME_LEVEL, 40, 53, 99, 35),
            standard(EntryAnchorId.HOME_ADVENTURE, 1013, 940, 129, 134),
            wide(EntryAnchorId.HOME_LEVEL, 68, 62, 114, 40),
            wide(EntryAnchorId.HOME_ADVENTURE, 1453, 1102, 148, 154),
            wide(EntryAnchorId.ADVENTURE_HEADER, 120, 15, 420, 115),
            standard(EntryAnchorId.ADVENTURE_HEADER_STANDARD, 0, 0, 294, 110),
            wide(EntryAnchorId.ADVENTURE_HEADER_STANDARD, 23, 2, 338, 126),
            wide(EntryAnchorId.LABYRINTH_ENTRY, 2304, 988, 223, 56),
            standard(EntryAnchorId.LABYRINTH_ENTRY, 1640, 840, 190, 55),
            wide(EntryAnchorId.LABYRINTH_ENTRY, 2431, 988, 223, 56),
            wide(EntryAnchorId.DAWN_START, 1694, 673, 140, 68),
            standard(EntryAnchorId.DAWN_START_STANDARD, 1118, 578, 130, 58),
            wide(EntryAnchorId.DAWN_START, 1826, 677, 127, 62),
            wide(EntryAnchorId.DAWN_ACTIVE, 1698, 619, 118, 48),
            standard(EntryAnchorId.DAWN_ACTIVE_STANDARD, 1114, 528, 126, 45),
            wide(EntryAnchorId.DAWN_ACTIVE, 1828, 620, 110, 45),
            wide(EntryAnchorId.DAWN_TICKET_LABEL, 1540, 765, 314, 47),
            standard(EntryAnchorId.DAWN_TICKET_LABEL_STANDARD, 989, 655, 266, 45),
            wide(EntryAnchorId.DAWN_TICKET_LABEL, 1672, 767, 285, 43),
            wide(EntryAnchorId.SELECTION_HEADER, 325, 58, 2249, 86),
            standard(EntryAnchorId.SELECTION_HEADER_STANDARD, 50, 55, 1820, 65),
            wide(EntryAnchorId.SELECTION_COUNT_NONE, 2065, 158, 376, 56),
            standard(EntryAnchorId.SELECTION_COUNT_NONE_STANDARD, 1534, 137, 321, 50),
            wide(EntryAnchorId.SELECTION_COUNT_COMPLETE, 2063, 161, 372, 47),
            standard(EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD, 1534, 137, 321, 50),
            wide(EntryAnchorId.INVITE_DISABLED, 1941, 1052, 482, 117),
            standard(EntryAnchorId.INVITE_DISABLED_STANDARD, 1427, 899, 415, 95),
            wide(EntryAnchorId.INVITE_ENABLED, 1938, 1051, 467, 119),
            standard(EntryAnchorId.INVITE_ENABLED_STANDARD, 1427, 899, 415, 95),
            standard(EntryAnchorId.ROLE_REWARD_TITLE, 680, 35, 580, 120),
            standard(EntryAnchorId.ROLE_REWARD_INSTRUCTION, 680, 150, 570, 75),
            standard(EntryAnchorId.ROLE_REWARD_SELECT_BUTTON, 260, 810, 290, 155),
            standard(EntryAnchorId.ROLE_REWARD_SELECT_BUTTON, 815, 810, 290, 155),
            standard(EntryAnchorId.ROLE_REWARD_SELECT_BUTTON, 1370, 810, 290, 155),
            standard(EntryAnchorId.EVENT_CHOICE_TITLE, 635, 40, 650, 110),
            standard(EntryAnchorId.EVENT_CHOICE_INSTRUCTION, 650, 135, 620, 90),
            standard(EntryAnchorId.EVENT_CHOICE_OPTION, 260, 810, 1400, 155),
            // Current one-choice event popup (for example "发现了钓鱼地点！").  Keep these
            // deliberately tight so the underlying node-map controls cannot impersonate the
            // event layer while its translucent backdrop remains visible.
            standard(EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE, 720, 35, 480, 120),
            standard(EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON, 820, 805, 295, 145),
            // The same select-button artwork is used by the two-choice event overlay.
            standard(EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON, 545, 805, 295, 145),
            standard(EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON, 1100, 805, 295, 145),
            standard(EntryAnchorId.EVENT_ANIMATION_TITLE, 650, 80, 620, 120),
            standard(EntryAnchorId.EVENT_ANIMATION_SKIP, 760, 850, 400, 120),
            standard(EntryAnchorId.LINK_CHOICE_TITLE, 635, 40, 650, 110),
            standard(EntryAnchorId.LINK_CHOICE_INSTRUCTION, 650, 135, 620, 90),
            standard(EntryAnchorId.LINK_CHOICE_REMAINING, 845, 210, 230, 70),
            standard(EntryAnchorId.LINK_CHOICE_CARD_SIGNATURE, 330, 385, 160, 190),
            standard(EntryAnchorId.RELIC_CHOICE_INSTRUCTION, 650, 135, 620, 90),
            standard(EntryAnchorId.ITEM_REWARD_TITLE, 760, 50, 400, 75),
            standard(EntryAnchorId.ITEM_REWARD_INSTRUCTION, 800, 130, 360, 70),
            standard(EntryAnchorId.ITEM_REWARD_CLOSE, 745, 895, 430, 140),
            standard(EntryAnchorId.SHOP_TITLE, 850, 50, 220, 75),
            standard(EntryAnchorId.SHOP_INSTRUCTION, 700, 130, 520, 75),
            standard(EntryAnchorId.SHOP_CLOSE, 1420, 900, 430, 115),
            standard(EntryAnchorId.SHOP_BUY_BUTTON, 350, 480, 290, 90),
            standard(EntryAnchorId.SHOP_REFRESH_BUTTON, 500, 930, 350, 85),
            // Older recorded shop footage places this modal title at y=125, while the
            // current 1920x1080 client renders the same title 8 px lower. Keep both fixed
            // candidates instead of widening the ROI (which would rescale the template).
            standard(EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE, 750, 125, 420, 75),
            standard(EntryAnchorId.SHOP_PURCHASE_CONFIRMATION_TITLE, 750, 133, 420, 75),
            standard(EntryAnchorId.SHOP_PURCHASE_COMPLETE_TITLE, 750, 245, 420, 75),
            standard(EntryAnchorId.SHOP_EXIT_CONFIRMATION_TITLE, 750, 240, 420, 75),
            standard(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON, 980, 706, 400, 80),
            // Current purchase-confirmation modal renders the same blue "确认" button lower.
            // Keep both fixed geometries and let the stronger template match win.
            standard(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON, 979, 832, 400, 80),
            wide(EntryAnchorId.JOINED_HEADER, 1282, 176, 212, 62),
            standard(EntryAnchorId.JOINED_HEADER_STANDARD, 855, 145, 210, 55),
            wide(EntryAnchorId.JOINED_CLOSE, 1326, 988, 122, 61),
            standard(EntryAnchorId.JOINED_CLOSE_STANDARD, 900, 840, 120, 60),
            wide(EntryAnchorId.NODE_HEADER, 183, 41, 393, 73),
            standard(EntryAnchorId.NODE_HEADER_STANDARD, 120, 25, 295, 70),
            wide(EntryAnchorId.NODE_CHARACTERS, 216, 1100, 72, 125),
            standard(EntryAnchorId.NODE_CHARACTERS_STANDARD, 37, 927, 115, 126),
            wide(EntryAnchorId.NODE_RELICS, 354, 1108, 123, 111),
            standard(EntryAnchorId.NODE_RELICS_STANDARD, 170, 927, 135, 126),
            wide(EntryAnchorId.NODE_RETREAT, 1815, 1109, 247, 76),
            standard(EntryAnchorId.NODE_RETREAT_STANDARD, 1200, 928, 323, 95),
            wide(EntryAnchorId.NODE_RETURN, 2222, 1100, 242, 84),
            standard(EntryAnchorId.NODE_RETURN_STANDARD, 1550, 928, 320, 95),
            standard(EntryAnchorId.BATTLE_CHALLENGE_HEADER_NORMAL, 60, 60, 620, 100),
            standard(EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_NORMAL, 305, 85, 135, 60),
            standard(EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_EXTREME, 305, 85, 135, 60),
            standard(EntryAnchorId.BATTLE_CHALLENGE_VIEW_MAP_BUTTON, 975, 850, 440, 125),
            standard(EntryAnchorId.BATTLE_CHALLENGE_BUTTON, 1420, 850, 440, 125),
            standard(EntryAnchorId.BATTLE_TEAM_HEADER, 40, 35, 1840, 90),
            standard(EntryAnchorId.BATTLE_TEAM_FILTER_BAR, 70, 140, 1040, 80),
            standard(EntryAnchorId.BATTLE_TEAM_CURRENT_MEMBERS_SUMMARY, 75, 748, 1100, 60),
            standard(EntryAnchorId.BATTLE_TEAM_CANCEL_BUTTON, 1285, 850, 270, 110),
            standard(EntryAnchorId.BATTLE_TEAM_START_BUTTON, 1565, 850, 270, 110),
            standard(EntryAnchorId.SESSION_ERROR_TITLE, 485, 259, 948, 66),
            standard(EntryAnchorId.SESSION_RETURN_TITLE, 756, 692, 410, 94),
            standard(EntryAnchorId.SESSION_DATE_CHANGE_TITLE, 790, 258, 340, 70),
            // Current client renders the same date-change title 3 px right/down. Keep both
            // fixed ROIs instead of lowering the semantic threshold: a tiny translation can
            // otherwise collapse the direct template score even though the popup is identical.
            standard(EntryAnchorId.SESSION_DATE_CHANGE_TITLE, 793, 261, 340, 70),
            standard(EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM, 750, 690, 415, 102),
            // Same current-client drift for the date-change confirmation button.
            standard(EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM, 753, 691, 415, 102),
            standard(EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON, 1700, 36, 192, 50),
            standard(EntryAnchorId.BATTLE_IN_PROGRESS_AUTO_BUTTON, 1786, 782, 106, 108),
            standard(EntryAnchorId.BATTLE_RESULT_REWARD_BANNER, 700, 270, 540, 82),
            standard(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON, 1452, 946, 388, 92),
            // Current 1920x1080 client Boss settlement pages place the same "下一步" button
            // about 40 px left and 20 px lower, with a slightly wider frame. Keep both fixed
            // candidates instead of widening one ROI so the template is not distorted.
            standard(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON, 1412, 966, 414, 92),
            // Boss three-team WIN summary uses a visually different blue Next button from the
            // ordinary battle result. Keep a dedicated template/anchor instead of weakening the
            // generic result threshold.
            standard(EntryAnchorId.BATTLE_RESULT_BOSS_SUMMARY_NEXT_BUTTON, 1412, 966, 414, 92),
            standard(EntryAnchorId.RUN_RESULT_LOGO, 768, 68, 380, 104),
            standard(EntryAnchorId.RUN_RESULT_CLOSE_BUTTON, 752, 948, 416, 92),
            standard(EntryAnchorId.RUN_CLEAR_CONGRATULATIONS_TITLE, 430, 280, 1060, 330),
            standard(EntryAnchorId.RUN_CLEAR_CHARACTER_SUMMARY_TITLE, 650, 80, 620, 110),
            standard(EntryAnchorId.RUN_CLEAR_NEXT_BUTTON, 1452, 946, 388, 92),
            standard(EntryAnchorId.RUN_CLEAR_REWARD_ANIMATION_TITLE, 650, 80, 620, 120),
            standard(EntryAnchorId.RUN_CLEAR_CHEST_ANIMATION_TITLE, 650, 80, 620, 120),
            // 宝箱开封结果 title bar: blue band y 60..112 on the 2026-09-17 recording; crop matches this rect exactly.
            standard(EntryAnchorId.RUN_CLEAR_CHEST_RESULT_TITLE, 650, 52, 620, 60),
            // 宝箱开封结果 is a centred modal; its 确认 sits bottom-centre like 获得道具's 关闭.
            standard(EntryAnchorId.RUN_CLEAR_CHEST_CONFIRM_BUTTON, 745, 910, 430, 110),
            // 我的主页 tab on the labyrinth home bottom bar (2026-09-17 recording t10.5). The
            // selected 冒险 tab is not used: tapping the active tab makes no server request.
            standard(EntryAnchorId.DAWN_HOME_MY_HOME_TAB, 85, 945, 175, 125),
            // 迷宫遗物效果 popup (迷宫大师 opening relic). Same modal shell as 获得道具, but the
            // title and the two-line instruction differ; the close button is shared.
            standard(EntryAnchorId.RELIC_EFFECT_TITLE, 760, 50, 400, 75),
            standard(EntryAnchorId.RELIC_EFFECT_INSTRUCTION, 770, 150, 380, 65),
        )
        val DEFINITIONS_BY_ID = DEFINITIONS.groupBy(EntryAnchorDefinition::id)
    }
}

object ReferenceCoverMapper {
    fun map(
        frameWidth: Int,
        frameHeight: Int,
        referenceSize: EntryReferenceSize,
        referenceRect: EntryReferenceRect,
    ): EntryPixelRect? {
        require(frameWidth > 0 && frameHeight > 0)
        val scale = max(
            frameWidth.toDouble() / referenceSize.width,
            frameHeight.toDouble() / referenceSize.height,
        )
        val offsetX = (frameWidth - referenceSize.width * scale) / 2.0
        val offsetY = (frameHeight - referenceSize.height * scale) / 2.0
        val left = (offsetX + referenceRect.x * scale).roundToInt()
        val top = (offsetY + referenceRect.y * scale).roundToInt()
        val right = (offsetX + (referenceRect.x + referenceRect.width) * scale).roundToInt()
        val bottom = (offsetY + (referenceRect.y + referenceRect.height) * scale).roundToInt()
        if (left < 0 || top < 0 || right > frameWidth || bottom > frameHeight) return null
        if (right - left < 3 || bottom - top < 3) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }
}

/** Maps a reference canvas into the largest centered viewport that fits the frame. */
object ReferenceFitMapper {
    fun map(
        frameWidth: Int,
        frameHeight: Int,
        referenceSize: EntryReferenceSize,
        referenceRect: EntryReferenceRect,
    ): EntryPixelRect? {
        require(frameWidth > 0 && frameHeight > 0)
        val scale = minOf(
            frameWidth.toDouble() / referenceSize.width,
            frameHeight.toDouble() / referenceSize.height,
        )
        val offsetX = (frameWidth - referenceSize.width * scale) / 2.0
        val offsetY = (frameHeight - referenceSize.height * scale) / 2.0
        val left = (offsetX + referenceRect.x * scale).roundToInt()
        val top = (offsetY + referenceRect.y * scale).roundToInt()
        val right = (offsetX + (referenceRect.x + referenceRect.width) * scale).roundToInt()
        val bottom = (offsetY + (referenceRect.y + referenceRect.height) * scale).roundToInt()
        if (left < 0 || top < 0 || right > frameWidth || bottom > frameHeight) return null
        if (right - left < 3 || bottom - top < 3) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }
}

/**
 * Gradient correlation ignores changing backgrounds and animated colors while retaining the
 * outlines of fixed labels and controls. Sampling bounds the cost of very wide templates.
 */
class GradientTemplateMatcher(
    private val maxSamples: Int = 25_000,
) {
    private data class TemplateStats(
        val points: IntArray,
        val gradients: DoubleArray,
        val mean: Double,
        val denominator: Double,
        val luminances: DoubleArray,
        val luminanceMean: Double,
        val luminanceDenominator: Double,
    )

    private val cache = IdentityHashMap<PixelImage, TemplateStats>()
    private data class FrameStats(val frame: PixelImage, val lights: ByteArray, val gradients: ShortArray)
    private var preparedFrame: FrameStats? = null

    /** Shared by the coarse, refinement and full node matchers for one bounded full scan. */
    fun prepareFrame(frame: PixelImage) {
        // Luminance is 0..255, gradient is 0..510. Compact storage is exact, not lossy.
        val lights = ByteArray(frame.width * frame.height) { luminance(frame.pixels[it]).toByte() }
        val gradients = ShortArray(lights.size)
        for (y in 1 until frame.height - 1) {
            for (x in 1 until frame.width - 1) {
                val at = y * frame.width + x
                gradients[at] = (abs((lights[at + 1].toInt() and 255) - (lights[at - 1].toInt() and 255)) +
                    abs((lights[at + frame.width].toInt() and 255) - (lights[at - frame.width].toInt() and 255))).toShort()
            }
        }
        preparedFrame = FrameStats(frame, lights, gradients)
    }

    fun sharePreparedFrame(other: GradientTemplateMatcher) { preparedFrame = other.preparedFrame }
    fun clearPreparedFrame() { preparedFrame = null }

    fun score(
        frame: PixelImage,
        rect: EntryPixelRect,
        template: PixelImage,
        minimumChannelScore: Double = 0.0,
    ): Double {
        if (rect.width < 3 || rect.height < 3 || template.width < 3 || template.height < 3) return 0.0
        val stats = stats(template)
        if (stats.points.isEmpty()) return 0.0
        val features = preparedFrame?.takeIf { it.frame === frame }
        var observedSum = 0.0
        var observedSquares = 0.0
        var gradientProduct = 0.0
        var luminanceSum = 0.0
        var luminanceSquares = 0.0
        var luminanceProduct = 0.0
        stats.points.indices.forEach { index ->
            val packed = stats.points[index]
            val templateX = packed ushr 16
            val templateY = packed and 0xffff
            val frameX = rect.left + map(templateX, template.width, rect.width)
            val frameY = rect.top + map(templateY, template.height, rect.height)
            val at = frameY * frame.width + frameX
            val gradient = features?.gradients?.get(at)?.toDouble() ?: gradient(frame, frameX, frameY, rect)
            val light = if (features != null) (features.lights[at].toInt() and 255).toDouble()
                else luminance(frame[frameX, frameY]).toDouble()
            observedSum += gradient
            observedSquares += gradient * gradient
            gradientProduct += gradient * (stats.gradients[index] - stats.mean)
            luminanceSum += light
            luminanceSquares += light * light
            luminanceProduct += light * (stats.luminances[index] - stats.luminanceMean)
        }
        val count = stats.points.size
        fun normalized(product: Double, sum: Double, squares: Double, denominator: Double): Double {
            val variance = squares - sum * sum / count
            return if (variance <= 0.0 || denominator <= 0.0) 0.0 else product / sqrt(variance * denominator)
        }
        val gradientScore = normalized(gradientProduct, observedSum, observedSquares, stats.denominator)
        val luminanceScore = normalized(luminanceProduct, luminanceSum, luminanceSquares, stats.luminanceDenominator)
        if (minOf(gradientScore, luminanceScore) < minimumChannelScore) return 0.0
        return maxOf(gradientScore, luminanceScore).coerceIn(0.0, 1.0)
    }

    @Synchronized
    private fun stats(template: PixelImage): TemplateStats = cache[template] ?: run {
        val step = max(1, ceil(sqrt(template.width.toDouble() * template.height / maxSamples)).toInt())
        val points = ArrayList<Int>()
        val gradients = ArrayList<Double>()
        val luminances = ArrayList<Double>()
        var y = 1
        while (y < template.height - 1) {
            var x = 1
            while (x < template.width - 1) {
                // Node templates are PNGs with transparent pixels outside the
                // node body. Ignore the mask boundary as well, otherwise the
                // artificial transparent-to-opaque edge becomes a false
                // feature and neighbouring nodes/routes leak into the score.
                if (isOpaqueInterior(template, x, y)) {
                    points += (x shl 16) or y
                    gradients += gradient(template, x, y, null)
                    luminances += luminance(template[x, y]).toDouble()
                }
                x += step
            }
            y += step
        }
        val values = gradients.toDoubleArray()
        val mean = values.average()
        val denominator = values.sumOf { value -> (value - mean) * (value - mean) }
        val luminanceValues = luminances.toDoubleArray()
        val luminanceMean = luminanceValues.average()
        val luminanceDenominator = luminanceValues.sumOf { value ->
            (value - luminanceMean) * (value - luminanceMean)
        }
        TemplateStats(
            points = points.toIntArray(),
            gradients = values,
            mean = mean,
            denominator = denominator,
            luminances = luminanceValues,
            luminanceMean = luminanceMean,
            luminanceDenominator = luminanceDenominator,
        ).also { cache[template] = it }
    }

    private fun gradient(image: PixelImage, x: Int, y: Int, bounds: EntryPixelRect?): Double {
        val minimumX = bounds?.left ?: 0
        val maximumX = bounds?.let { it.left + it.width - 1 } ?: (image.width - 1)
        val minimumY = bounds?.top ?: 0
        val maximumY = bounds?.let { it.top + it.height - 1 } ?: (image.height - 1)
        val left = (x - 1).coerceAtLeast(minimumX)
        val right = (x + 1).coerceAtMost(maximumX)
        val top = (y - 1).coerceAtLeast(minimumY)
        val bottom = (y + 1).coerceAtMost(maximumY)
        return abs(luminance(image[right, y]) - luminance(image[left, y])).toDouble() +
            abs(luminance(image[x, bottom]) - luminance(image[x, top])).toDouble()
    }

    private fun map(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value.toLong() * (targetSize - 1) / (sourceSize - 1)).toInt().coerceIn(1, targetSize - 2)

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000
    }

    private fun isOpaqueInterior(image: PixelImage, x: Int, y: Int): Boolean {
        return alpha(image[x, y]) >= MASK_ALPHA &&
            alpha(image[x - 1, y]) >= MASK_ALPHA &&
            alpha(image[x + 1, y]) >= MASK_ALPHA &&
            alpha(image[x, y - 1]) >= MASK_ALPHA &&
            alpha(image[x, y + 1]) >= MASK_ALPHA
    }

    private fun alpha(color: Int): Int = color ushr 24 and 0xff

    private companion object {
        const val MASK_ALPHA = 128
    }
}
