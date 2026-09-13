package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthPageRuntimePolicyTest {
    @Test
    fun `challenge button waits for difficulty and never bypasses unresolved EX guide`() {
        val normal = LabyrinthCombatContext(LabyrinthCombatKind.NORMAL)
        val ex = LabyrinthCombatContext(LabyrinthCombatKind.EX)
        val boss = LabyrinthCombatContext(LabyrinthCombatKind.BOSS)

        assertEquals(false, labyrinthBattleChallengeCanAutoStart(normal, false, false, false))
        assertTrue(labyrinthBattleChallengeCanAutoStart(normal, true, false, false))

        // EVENT -> EX race: even before combatContext is promoted, visible 极难 evidence blocks
        // the generic challenge path until the encounter guide has been resolved.
        assertEquals(false, labyrinthBattleChallengeCanAutoStart(null, true, true, false))
        assertTrue(labyrinthBattleChallengeCanAutoStart(ex, false, true, true))
        assertEquals(false, labyrinthBattleChallengeCanAutoStart(ex, true, true, false))

        // Route-known Bosses keep their existing direct challenge behavior.
        assertTrue(labyrinthBattleChallengeCanAutoStart(boss, false, false, false))
    }

    @Test
    fun `single-choice event needs both trigger and select anchors`() {
        val rect = EntryPixelRect(820, 805, 295, 145)
        val trusted = result(
            LabyrinthEntryPageState.EVENT_CHOICE,
            anchorScores = mapOf(
                EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to 0.94,
                EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to 0.96,
            ),
        ).copy(
            anchorMatches = mapOf(
                EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to EntryAnchorMatch(0.96, rect),
            ),
        )
        assertEquals(rect, labyrinthSingleChoiceEventButtonRect(trusted))

        val weakTitle = trusted.copy(
            observation = trusted.observation.copy(
                anchorScores = LabyrinthAnchorScores(
                    mapOf(
                        EntryAnchorId.EVENT_SINGLE_CHOICE_TRIGGER_TITLE to 0.60,
                        EntryAnchorId.EVENT_SINGLE_CHOICE_SELECT_BUTTON to 0.96,
                    ),
                ),
            ),
        )
        assertNull(labyrinthSingleChoiceEventButtonRect(weakTitle))
        assertNull(labyrinthSingleChoiceEventButtonRect(trusted.copy(anchorMatches = emptyMap())))
    }

    @Test
    fun `single EX detail fallback waits for OCR grace and excludes structured encounters`() {
        assertNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = false,
                multiMonsterLikely = false,
                specialDualLikely = false,
                elapsedMillis = 2_999L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
        val rect = requireNotNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = false,
                multiMonsterLikely = false,
                specialDualLikely = false,
                elapsedMillis = 3_000L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
        assertEquals(1395, rect.left)
        assertEquals(590, rect.top)
        assertEquals(155, rect.width)
        assertEquals(88, rect.height)

        assertNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = false,
                multiMonsterLikely = true,
                specialDualLikely = false,
                elapsedMillis = 5_000L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
        assertNull(
            labyrinthSingleExDetailProbeRect(
                pageState = LabyrinthEntryPageState.BATTLE_CHALLENGE,
                isEx = true,
                encounterResolved = true,
                multiMonsterLikely = false,
                specialDualLikely = false,
                elapsedMillis = 5_000L,
                stableFrames = 3,
                attempts = 0,
                frameWidth = 1920,
                frameHeight = 1080,
            ),
        )
    }

    @Test
    fun `recent shop animation unknown frame cannot masquerade as session failure`() {
        assertTrue(
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                previousPageState = LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE,
                activeNodeType = LabyrinthNodeTypes.SHOP,
                lastActionAt = 1_000L,
                now = 2_500L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                previousPageState = LabyrinthEntryPageState.SHOP,
                activeNodeType = LabyrinthNodeTypes.SHOP,
                lastActionAt = 1_000L,
                now = 4_500L,
            ),
        )
        assertEquals(
            false,
            labyrinthShopTransitionSuppressesSessionBlock(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                previousPageState = LabyrinthEntryPageState.SHOP,
                activeNodeType = LabyrinthNodeTypes.EVENT,
                lastActionAt = 1_000L,
                now = 2_000L,
            ),
        )
    }

    @Test
    fun `resumed run may hand pending role rewards directly to route execution`() {
        val joined = result(LabyrinthEntryPageState.CHARACTER_JOINED)
        val roleChoice = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(
                EntryAnchorId.ROLE_REWARD_TITLE to 0.95,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.94,
            ),
        )
        val freshOpening = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(
                EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99,
                EntryAnchorId.INVITE_DISABLED_STANDARD to 0.95,
                // Deliberate false positives: the opening shell must still own the frame.
                EntryAnchorId.ROLE_REWARD_TITLE to 0.90,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.89,
            ),
        )

        assertTrue(labyrinthResumedRunRewardPageOwnsRoute(joined))
        assertTrue(labyrinthResumedRunRewardPageOwnsRoute(roleChoice))
        assertEquals(false, labyrinthResumedRunRewardPageOwnsRoute(freshOpening))
        assertEquals(
            false,
            labyrinthResumedRunRewardPageOwnsRoute(result(LabyrinthEntryPageState.NODE_SELECTION)),
        )
    }

    @Test
    fun `role reward signature distinguishes consecutive choice rounds without a page-state change`() {
        val anchors = mapOf(
            EntryAnchorId.ROLE_REWARD_TITLE to 0.95,
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.94,
        )
        val first = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = anchors,
            characterMatches = listOf(
                character("1001", "A", trusted = true),
                character("1002", "B", trusted = true),
                character("1003", "C", trusted = true),
            ),
        )
        val second = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = anchors,
            characterMatches = listOf(
                character("1004", "D", trusted = true),
                character("1005", "E", trusted = true),
                character("1006", "F", trusted = true),
            ),
        )

        val firstSignature = labyrinthRoleRewardSelectionSignature(first)
        assertEquals(firstSignature, labyrinthRoleRewardSelectionSignature(first))
        assertTrue(firstSignature != labyrinthRoleRewardSelectionSignature(second))
    }

    @Test
    fun `role reward batch survives all choices then consecutive joined popups until a terminal page`() {
        assertTrue(
            labyrinthKeepsRoleRewardBatchOnPage(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                isRoleRewardPage = true,
            ),
        )
        assertTrue(labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.UNKNOWN, false))
        assertTrue(labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.CHARACTER_JOINED, false))
        assertTrue(labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.ITEM_REWARD, false))
        assertEquals(
            false,
            labyrinthKeepsRoleRewardBatchOnPage(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                isRoleRewardPage = false,
            ),
        )
        assertEquals(false, labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.NODE_SELECTION, false))
        assertEquals(false, labyrinthKeepsRoleRewardBatchOnPage(LabyrinthEntryPageState.SHOP, false))
    }

    @Test
    fun `only a reliable boss unknown next button can enter the intermediate boss settlement chain`() {
        val rect = EntryPixelRect(1412, 966, 414, 92)
        val reliable = EntryAnchorMatch(0.72, rect)

        assertEquals(
            rect,
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.UNKNOWN,
                LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                reliable,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.UNKNOWN,
                LabyrinthCombatContext(LabyrinthCombatKind.NORMAL),
                reliable,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.BATTLE_RESULT,
                LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                reliable,
            ),
        )
        assertNull(
            labyrinthBossSettlementNextButtonRect(
                LabyrinthEntryPageState.UNKNOWN,
                LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                EntryAnchorMatch(0.67, rect),
            ),
        )

        // Current three-team Boss summary has a different button skin. A dedicated strong match
        // must recover the settlement even when the legacy result-button template is unusable.
        assertEquals(
            rect,
            labyrinthBossSettlementNextButtonRect(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                combatContext = LabyrinthCombatContext(LabyrinthCombatKind.BOSS),
                nextButtonMatch = EntryAnchorMatch(0.19, rect),
                bossSummaryNextButtonMatch = EntryAnchorMatch(0.99, rect),
            ),
        )
    }

    @Test
    fun `opening roster reclaims premature route handoff but reward choices do not`() {
        val opening = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99998),
        )
        assertTrue(labyrinthShouldResumeOpeningSelection(true, opening))
        assertEquals(false, labyrinthShouldResumeOpeningSelection(false, opening))
        val reward = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = mapOf(
                EntryAnchorId.SELECTION_HEADER_STANDARD to 0.99,
                EntryAnchorId.ROLE_REWARD_TITLE to 0.95,
                EntryAnchorId.ROLE_REWARD_INSTRUCTION to 0.95,
            ),
        )
        assertEquals(false, labyrinthShouldResumeOpeningSelection(true, reward))
        assertEquals(false, labyrinthShouldResumeOpeningSelection(true, result(LabyrinthEntryPageState.NODE_SELECTION)))
        assertEquals(false, labyrinthShouldResumeOpeningSelection(true, result(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION)))
    }

    @Test
    fun `page state is the hard UI ownership boundary`() {
        assertEquals(LabyrinthPageUiOwner.NODE_SELECTION, labyrinthPageUiOwner(LabyrinthEntryPageState.NODE_SELECTION))
        assertEquals(LabyrinthPageUiOwner.NODE_MAP_VIEW, labyrinthPageUiOwner(LabyrinthEntryPageState.NODE_MAP_VIEW))
        assertEquals(
            LabyrinthPageUiOwner.CHARACTER_SELECTION,
            labyrinthPageUiOwner(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION),
        )
        assertEquals(
            LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION,
            labyrinthPageUiOwner(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION),
        )
        assertEquals(LabyrinthPageUiOwner.NONE, labyrinthPageUiOwner(LabyrinthEntryPageState.UNKNOWN))
    }

    @Test
    fun `node move confirmation cannot preempt a recognized shop dialog`() {
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                hasPendingNodeTransition = false,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                hasPendingNodeTransition = true,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION,
                hasPendingNodeTransition = true,
            ),
        )
    }

    @Test
    fun `node move confirmation requires a programmatic pending node transition`() {
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_SELECTION,
                hasPendingNodeTransition = false,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                hasPendingNodeTransition = false,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = false,
            ),
        )
        assertTrue(
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_SELECTION,
                hasPendingNodeTransition = true,
            ),
        )
        assertTrue(
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                hasPendingNodeTransition = true,
            ),
        )
        assertTrue(
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = true,
            ),
        )
        assertEquals(
            false,
            labyrinthNodeMoveConfirmationOwnsFrame(
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
                hasPendingNodeTransition = true,
            ),
        )
    }

    @Test
    fun `recognized movement modal preserves pending transition across unknown map classification`() {
        assertTrue(
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = true,
            ),
        )
        assertTrue(
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.NODE_MAP_VIEW,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = true,
            ),
        )
        assertEquals(
            false,
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = false,
                hasMoveConfirmation = true,
            ),
        )
        assertEquals(
            false,
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.SHOP_EXIT_CONFIRMATION,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = true,
            ),
        )
        assertEquals(
            false,
            labyrinthPreservesPendingNodeTransitionForMoveConfirmation(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                hasPendingNodeTransition = true,
                hasMoveConfirmation = false,
            ),
        )
    }

    @Test
    fun `auto shop purchase survives only a bounded unknown transition`() {
        assertTrue(
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                plannedRelicId = "25004",
                startedAt = 1_000L,
                now = 4_000L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                plannedRelicId = "25004",
                startedAt = 1_000L,
                now = 10_001L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.NODE_SELECTION,
                plannedRelicId = "25004",
                startedAt = 1_000L,
                now = 2_000L,
                timeoutMillis = 8_000L,
            ),
        )
        assertEquals(
            false,
            labyrinthKeepsPlannedShopPurchaseAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                plannedRelicId = null,
                startedAt = 1_000L,
                now = 2_000L,
                timeoutMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `character joined contributes any trusted full-icon identity`() {
        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.CHARACTER_JOINED,
                characterMatches = listOf(character("1310", "真步(梦想乐园)", trusted = true)),
            ),
        )

        assertEquals(listOf("1310"), matches.mapNotNull(LabyrinthCharacterMatch::characterId))
    }

    @Test
    fun `character joined never reconciles an untrusted guess`() {
        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.CHARACTER_JOINED,
                characterMatches = listOf(character("1310", "真步(梦想乐园)", trusted = false)),
            ),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `battle team candidates are never treated as newly joined roles`() {
        val observation = battleTeamObservation(
            state = LabyrinthBattleTeamRecognitionState.STABLE,
            visible = listOf(
                battleCharacter("1059", "可可萝"),
                battleCharacter("1075", "贪吃佩可(夏日)"),
                battleCharacter("1351", "雪菲(夏日)"),
                battleCharacter("1310", "真步(梦想乐园)"),
            ),
            selected = listOf(
                battleCharacter("1091", "静流(情人节)", selected = true),
                battleCharacter("1168", "珠希(工作服)", selected = true),
            ),
        )

        val matches = labyrinthRosterReconciliationMatches(
            result(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, battleTeam = observation),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `moving battle team viewport is not roster evidence`() {
        val observation = battleTeamObservation(
            state = LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
            visible = listOf(battleCharacter("1059", "可可萝")),
        )

        assertTrue(
            labyrinthRosterReconciliationMatches(
                result(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, battleTeam = observation),
            ).isEmpty(),
        )
    }

    @Test
    fun `confirmed opening selection contributes exactly its three trusted selected roles`() {
        val anchors = mapOf(
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 1.0,
            EntryAnchorId.INVITE_ENABLED to 1.0,
        )
        val opening = listOf(
            battleCharacter("1059", "可可萝", selected = true),
            battleCharacter("1075", "贪吃佩可(夏日)", selected = true),
            battleCharacter("1351", "雪菲(夏日)", selected = true),
            battleCharacter("1310", "真步(梦想乐园)", selected = false),
        )

        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                anchorScores = anchors,
                openingCharacters = opening,
            ),
        )

        assertEquals(listOf("1059", "1075", "1351"), matches.mapNotNull(LabyrinthCharacterMatch::characterId))
        assertTrue(
            labyrinthRosterReplacesExisting(
                result(
                    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                    anchorScores = anchors,
                    openingCharacters = opening,
                ),
                matches,
            ),
        )
    }

    @Test
    fun `role reward candidates are not treated as already-owned roster`() {
        val anchors = mapOf(
            EntryAnchorId.ROLE_REWARD_TITLE to 1.0,
            EntryAnchorId.ROLE_REWARD_INSTRUCTION to 1.0,
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 1.0,
            EntryAnchorId.INVITE_ENABLED to 1.0,
        )

        val matches = labyrinthRosterReconciliationMatches(
            result(
                LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                anchorScores = anchors,
                openingCharacters = listOf(
                    battleCharacter("1310", "真步(梦想乐园)", selected = true),
                    battleCharacter("1022", "绫音", selected = true),
                    battleCharacter("1063", "亚里莎", selected = true),
                ),
            ),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `opening roster with duplicate identity guesses is not persisted`() {
        val anchors = mapOf(
            EntryAnchorId.SELECTION_COUNT_COMPLETE to 1.0,
            EntryAnchorId.INVITE_ENABLED to 1.0,
        )
        val result = result(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            anchorScores = anchors,
            openingCharacters = listOf(
                battleCharacter("1075", "贪吃佩可(夏日)", selected = true),
                battleCharacter("1108", "可可萝(夏日)", selected = true),
                battleCharacter("1108", "可可萝(夏日)", selected = true),
            ),
        )
        val matches = labyrinthRosterReconciliationMatches(result)

        assertTrue(matches.isEmpty())
        assertTrue(!labyrinthRosterReplacesExisting(result, matches))
    }

    @Test
    fun `roster reconciliation is append-only across partial later viewports`() {
        val existing = listOf(
            joined("1059", "可可萝", 0),
            joined("1075", "贪吃佩可(夏日)", 1),
            joined("1351", "雪菲(夏日)", 2),
        )

        val merged = LabyrinthRosterMerger.merge(
            existing = existing,
            matches = listOf(character("1310", "真步(梦想乐园)", trusted = true)),
            nowMillis = 20L,
        )

        assertEquals(setOf("1059", "1075", "1351", "1310"), merged.map { it.characterId }.toSet())
    }

    @Test
    fun `confirmed opening roster replaces transient false identities`() {
        val polluted = listOf(
            joined("1075", "贪吃佩可(夏日)", 0),
            joined("1155", "贪吃佩可(圣诞节)", 1),
            joined("1290", "雪菲(新年)", 2),
            joined("1124", "花凛", 3),
        )

        val replaced = LabyrinthRosterMerger.merge(
            existing = polluted,
            matches = listOf(
                character("1075", "贪吃佩可(夏日)", trusted = true),
                character("1108", "可可萝(夏日)", trusted = true),
                character("1351", "雪菲(夏日)", trusted = true),
            ),
            nowMillis = 20L,
            replaceExisting = true,
        )

        assertEquals(setOf("1075", "1108", "1351"), replaced.map { it.characterId }.toSet())
    }

    @Test
    fun `combined member evidence canonicalizes to one combined role`() {
        val merged = LabyrinthRosterMerger.merge(
            existing = emptyList(),
            matches = listOf(
                character("1183", "初音", trusted = true),
                character("1184", "栞", trusted = true),
            ),
            nowMillis = 20L,
        )

        assertEquals(listOf("1807"), merged.map { it.characterId })
    }

    private fun result(
        state: LabyrinthEntryPageState,
        anchorScores: Map<String, Double> = emptyMap(),
        characterMatches: List<LabyrinthCharacterMatch> = emptyList(),
        openingCharacters: List<LabyrinthBattleCharacterMatch> = emptyList(),
        battleTeam: LabyrinthBattleTeamObservation? = null,
    ) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = 1.0,
            stateScores = mapOf(state to 1.0),
            anchorScores = LabyrinthAnchorScores(anchorScores),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0L,
        characterMatches = characterMatches,
        openingCharacterMatches = openingCharacters,
        battleTeamSelection = battleTeam,
        frameWidth = 1920,
        frameHeight = 1080,
    )

    private fun character(
        id: String,
        name: String,
        trusted: Boolean,
    ) = LabyrinthCharacterMatch(
        slotId = "slot-$id",
        characterId = id,
        displayName = name,
        confidence = if (trusted) 0.40 else 0.30,
        screenRect = RECT,
        iconVariant = "31",
        trusted = trusted,
        rivalMargin = if (trusted) 0.15 else 0.01,
    )

    private fun battleCharacter(
        id: String,
        name: String,
        selected: Boolean = false,
    ) = LabyrinthBattleCharacterMatch(
        slotId = "slot-$id-${if (selected) "selected" else "visible"}",
        characterId = id,
        displayName = name,
        iconVariant = "31",
        confidence = 0.40,
        screenRect = RECT,
        selected = selected,
        trusted = true,
        rivalMargin = 0.15,
    )

    private fun battleTeamObservation(
        state: LabyrinthBattleTeamRecognitionState,
        visible: List<LabyrinthBattleCharacterMatch>,
        selected: List<LabyrinthBattleCharacterMatch> = emptyList(),
    ) = LabyrinthBattleTeamObservation(
        currentFilter = LabyrinthBattleElementFilter.ALL,
        filters = emptyList(),
        visibleCharacters = visible,
        selectedCharacters = selected,
        scrollbar = LabyrinthBattleScrollbarObservation(
            trackRect = RECT,
            thumbRect = RECT,
            visible = true,
            canScroll = true,
            position = 0.0,
        ),
        recognitionState = state,
        viewportRevision = 1L,
    )

    private fun joined(id: String, name: String, order: Int) = LabyrinthJoinedCharacter(
        characterId = id,
        displayName = name,
        orderIndex = order,
        confidence = 0.9,
        firstSeenAt = 1L,
        lastSeenAt = 1L,
    )

    private companion object {
        val RECT = EntryPixelRect(10, 10, 100, 100)
    }
}
