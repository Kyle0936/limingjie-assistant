package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.LabyrinthMapScanDirection
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEntryRecognitionPolicyTest {
    @Test
    fun `role reward stays manual while battle team and relic pages are automated`() {
        assertEquals(
            "等待人工选择角色并确认",
            labyrinthManualInputMessage(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION),
        )
        assertNull(labyrinthManualInputMessage(LabyrinthEntryPageState.RELIC_CHOICE))
        assertNull(labyrinthManualInputMessage(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION))
    }

    @Test
    fun `battle team recommendation message announces automatic preparation`() {
        val recommendation = LabyrinthBattleTeamRecommendation(
            members = (1..5).map { index ->
                LabyrinthRecommendedTeamMember("$index", "角色$index")
            },
            damageType = LabyrinthTeamDamageType.PHYSICAL,
            score = 88.5,
            vanguard = LabyrinthRecommendedTeamMember("1", "角色1"),
            survivalAnchor = LabyrinthRecommendedTeamMember("1", "角色1"),
            reasons = listOf("当前角色池最高分五人"),
            defenseMarkStacks = 4,
            targetCount = 1,
        )

        val message = labyrinthBattleTeamSelectionMessage(
            combatContext = LabyrinthCombatContext(LabyrinthCombatKind.BOSS, teamIndex = 1),
            recommendation = recommendation,
            unavailableReason = null,
        )

        assertTrue(message.contains("准备 Boss 第1/3 队编组"))
        assertTrue(message.contains("推荐第一队"))
        assertTrue(!message.contains("不执行编组点击"))
    }

    @Test
    fun `automated post entry pages are not blocked by the manual input gate`() {
        listOf(
            LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.RELIC_CHOICE,
            LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.CHARACTER_JOINED,
            LabyrinthEntryPageState.SHOP,
            LabyrinthEntryPageState.BATTLE_CHALLENGE,
            LabyrinthEntryPageState.BATTLE_RESULT,
            LabyrinthEntryPageState.RUN_CLEAR_RESULT,
        ).forEach { state ->
            assertNull(state.name, labyrinthManualInputMessage(state))
        }
    }

    @Test
    fun `character acquisition fallback only arms after a known reward transition`() {
        listOf(
            LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
            LabyrinthEntryPageState.LINK_CHOICE,
            LabyrinthEntryPageState.EVENT_CHOICE,
            LabyrinthEntryPageState.EVENT_ANIMATION,
            LabyrinthEntryPageState.ITEM_REWARD,
            LabyrinthEntryPageState.BATTLE_RESULT,
        ).forEach { previousPage ->
            assertTrue(
                previousPage.name,
                shouldArmCharacterAcquisitionFallback(
                    previousPage = previousPage,
                    currentPage = LabyrinthEntryPageState.UNKNOWN,
                    activeNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
                ),
            )
        }
        assertTrue(
            shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.ITEM_REWARD,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
                activeNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
            ),
        )
        assertTrue(
            shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.ITEM_REWARD,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
                activeNodeType = LabyrinthNodeTypes.LINK,
            ),
        )
        assertTrue(
            !shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.ITEM_REWARD,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
                activeNodeType = LabyrinthNodeTypes.RELIC,
            ),
        )
        assertTrue(
            !shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.NODE_SELECTION,
                currentPage = LabyrinthEntryPageState.UNKNOWN,
            ),
        )
        assertTrue(
            !shouldArmCharacterAcquisitionFallback(
                previousPage = LabyrinthEntryPageState.EVENT_ANIMATION,
                currentPage = LabyrinthEntryPageState.EVENT_ANIMATION,
            ),
        )
    }

    @Test
    fun `event unknown fallback alternates between left and right edge-safe points`() {
        assertEquals(160 to 780, labyrinthEventUnknownFallbackPoint(0))
        assertEquals(1760 to 780, labyrinthEventUnknownFallbackPoint(1))
        assertEquals(160 to 780, labyrinthEventUnknownFallbackPoint(2))
        assertEquals(1760 to 780, labyrinthEventUnknownFallbackPoint(3))
    }

    @Test
    fun `event full roster one-role selector owns initial-selection-looking frame`() {
        assertTrue(
            labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = LabyrinthNodeTypes.EVENT,
                roleRewardPage = false,
                hasOpeningViewport = true,
            ),
        )
        assertTrue(
            !labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = null,
                roleRewardPage = false,
                hasOpeningViewport = true,
            ),
        )
        assertTrue(
            !labyrinthEventFreeRoleSelectionOwnsFrame(
                pageState = LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
                activeNodeType = LabyrinthNodeTypes.EVENT,
                roleRewardPage = true,
                hasOpeningViewport = true,
            ),
        )
    }

    @Test
    fun `confirmed node transition survives transient unresolved destination frames`() {
        val confirmedAt = 10_000L
        listOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
            LabyrinthEntryPageState.PRE_HOME_DATA_LOADING,
        ).forEach { page ->
            assertTrue(
                page.name,
                labyrinthPreservesConfirmedNodeTransitionAcrossPage(
                    pageState = page,
                    confirmationDispatchedAtMillis = confirmedAt,
                    nowMillis = confirmedAt + 5_999L,
                    timeoutMillis = 6_000L,
                ),
            )
        }
    }

    @Test
    fun `confirmed node transition expires and never survives a semantic destination page`() {
        val confirmedAt = 10_000L
        assertTrue(
            !labyrinthPreservesConfirmedNodeTransitionAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 6_000L,
                timeoutMillis = 6_000L,
            ),
        )
        assertTrue(
            !labyrinthPreservesConfirmedNodeTransitionAcrossPage(
                pageState = LabyrinthEntryPageState.EVENT_CHOICE,
                confirmationDispatchedAtMillis = confirmedAt,
                nowMillis = confirmedAt + 500L,
                timeoutMillis = 6_000L,
            ),
        )
        assertTrue(
            !labyrinthPreservesConfirmedNodeTransitionAcrossPage(
                pageState = LabyrinthEntryPageState.UNKNOWN,
                confirmationDispatchedAtMillis = null,
                nowMillis = confirmedAt + 500L,
                timeoutMillis = 6_000L,
            ),
        )
    }

    @Test
    fun `forward map swipe stays inside the map and moves toward later columns`() {
        val swipe = labyrinthForwardMapSwipe(1920, 1080) as AutomationAction.Swipe

        assertEquals(1459.2f, swipe.start.x, 0.01f)
        assertEquals(626.4f, swipe.start.y, 0.01f)
        assertEquals(691.2f, swipe.end.x, 0.01f)
        assertEquals(626.4f, swipe.end.y, 0.01f)
        assertEquals(450L, swipe.durationMillis)
    }

    @Test
    fun `backward map swipe reverses the segment direction without entering bottom controls`() {
        val swipe = labyrinthMapSwipe(
            frameWidth = 1920,
            frameHeight = 1080,
            direction = LabyrinthMapScanDirection.BACKWARD,
        ) as AutomationAction.Swipe

        assertEquals(691.2f, swipe.start.x, 0.01f)
        assertEquals(626.4f, swipe.start.y, 0.01f)
        assertEquals(1459.2f, swipe.end.x, 0.01f)
        assertEquals(626.4f, swipe.end.y, 0.01f)
        assertEquals(450L, swipe.durationMillis)
    }

    @Test
    fun `empty node classifications still produce a bounded scan toward the route target`() {
        val towardLaterColumn = labyrinthBlindMapScanPlan(
            currentNodeId = 20601,
            targetLogicalColumn = 7,
            completedAttempts = 0,
            reverseAfterAttempts = 5,
        )
        val reverseAfterHalfBudget = labyrinthBlindMapScanPlan(
            currentNodeId = 20601,
            targetLogicalColumn = 7,
            completedAttempts = 5,
            reverseAfterAttempts = 5,
        )

        assertEquals(LabyrinthMapScanDirection.FORWARD, towardLaterColumn.direction)
        assertEquals(LabyrinthMapScanDirection.BACKWARD, reverseAfterHalfBudget.direction)
        assertTrue(towardLaterColumn.reason.contains("blind-empty-classifications"))
    }

    @Test
    fun `6002 popup blocks normal actions and exposes the recognized return-title button`() {
        val returnRect = EntryPixelRect(left = 756, top = 692, width = 410, height = 94)
        val block = labyrinthSessionBlockObservation(
            frameResult(
                errorScore = 0.91,
                returnScore = 0.88,
                returnRect = returnRect,
            ),
        )

        assertEquals(SessionBlockKind.RECONNECT_PROMPTED, block.kind)
        assertTrue(block.blocksNormalActions)
        assertEquals(returnRect, block.returnTitleRect)
    }

    @Test
    fun `return-title button alone cannot trigger session recovery`() {
        val block = labyrinthSessionBlockObservation(
            frameResult(
                errorScore = 0.40,
                returnScore = 0.93,
                returnRect = EntryPixelRect(left = 756, top = 692, width = 410, height = 94),
            ),
        )

        assertEquals(SessionBlockKind.NONE, block.kind)
        assertNull(block.returnTitleRect)
    }

    @Test
    fun `date change popup uses confirm button and enters relogin recovery`() {
        val confirmRect = EntryPixelRect(left = 750, top = 690, width = 415, height = 102)
        val base = frameResult(
            errorScore = 0.0,
            returnScore = 0.0,
            returnRect = confirmRect,
        )
        val result = base.copy(
            observation = base.observation.copy(
                anchorScores = LabyrinthAnchorScores(
                    mapOf(
                        EntryAnchorId.SESSION_DATE_CHANGE_TITLE to 0.91,
                        EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to 0.88,
                    ),
                ),
            ),
            anchorMatches = mapOf(
                EntryAnchorId.SESSION_DATE_CHANGE_CONFIRM to EntryAnchorMatch(0.88, confirmRect),
            ),
        )

        val block = labyrinthSessionBlockObservation(result)

        assertEquals(SessionBlockKind.RELOGIN_REQUIRED, block.kind)
        assertEquals(confirmRect, block.returnTitleRect)
        assertEquals("确认", block.actionLabel)
    }

    @Test
    fun `specific movement confirmation outranks the generic reconnect title`() {
        val confirmRect = EntryPixelRect(left = 975, top = 690, width = 415, height = 105)
        val result = frameResult(
            errorScore = 0.634,
            returnScore = 0.156,
            returnRect = EntryPixelRect(left = 756, top = 692, width = 410, height = 94),
        ).copy(
            nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(
                confidence = 0.895,
                confirmButtonRect = confirmRect,
            ),
        )

        val block = labyrinthSessionBlockObservation(result)

        assertEquals(SessionBlockKind.NONE, block.kind)
        assertTrue(!block.blocksNormalActions)
        assertNull(block.returnTitleRect)
    }

    @Test
    fun `node route advances only after a recognized destination page`() {
        assertTrue(labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.BATTLE_CHALLENGE))
        assertTrue(labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.LINK_CHOICE))
        assertTrue(labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.RELIC_CHOICE))
        assertTrue(!labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.NODE_SELECTION))
        assertTrue(!labyrinthConfirmsNodeEntry(LabyrinthEntryPageState.UNKNOWN))
    }

    @Test
    fun `one slow map frame cannot expire a pending movement confirmation`() {
        assertTrue(
            labyrinthKeepsPendingNodeTransition(
                elapsedMillis = 6_004L,
                timeoutMillis = 6_000L,
                nodeSelectionFramesSinceAction = 1,
            ),
        )
        assertTrue(
            labyrinthKeepsPendingNodeTransition(
                elapsedMillis = 5_999L,
                timeoutMillis = 6_000L,
                nodeSelectionFramesSinceAction = 2,
            ),
        )
        assertTrue(
            !labyrinthKeepsPendingNodeTransition(
                elapsedMillis = 6_004L,
                timeoutMillis = 6_000L,
                nodeSelectionFramesSinceAction = 2,
            ),
        )
    }

    @Test
    fun `recognized destination must match the clicked node type`() {
        assertTrue(
            labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.NORMAL_BATTLE,
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
            ),
        )
        assertTrue(
            !labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.NORMAL_BATTLE,
                LabyrinthEntryPageState.RELIC_CHOICE,
            ),
        )
        assertTrue(
            labyrinthNodeEntryMatchesExpectedType(
                LabyrinthNodeTypes.EVENT,
                LabyrinthEntryPageState.ITEM_REWARD,
            ),
        )
    }

    @Test
    fun `node viewport signature ignores tiny matcher jitter but detects map movement`() {
        fun node(left: Int) = NodeClassification(
            column = 2,
            row = 1,
            blockType = 2,
            confidence = 0.9,
            isClickable = true,
            screenRect = EntryPixelRect(left, 200, 160, 180),
        )

        assertEquals(
            labyrinthNodeViewportSignature(listOf(node(500))),
            labyrinthNodeViewportSignature(listOf(node(508))),
        )
        assertTrue(
            labyrinthNodeViewportSignature(listOf(node(500))) !=
                labyrinthNodeViewportSignature(listOf(node(420))),
        )
    }

    @Test
    fun `node target box is hidden while clicks are enabled`() {
        assertTrue(labyrinthShowsNextNodeOverlayBox(dryRun = true))
        assertTrue(!labyrinthShowsNextNodeOverlayBox(dryRun = false))
    }

    @Test
    fun `node diagnostics are visible only while the session is paused`() {
        assertTrue(labyrinthShowsPausedNodeDiagnostics(paused = true))
        assertTrue(!labyrinthShowsPausedNodeDiagnostics(paused = false))
    }

    private fun frameResult(
        errorScore: Double,
        returnScore: Double,
        returnRect: EntryPixelRect,
    ): LabyrinthEntryFrameResult {
        val scores = LabyrinthAnchorScores(
            mapOf(
                EntryAnchorId.SESSION_ERROR_TITLE to errorScore,
                EntryAnchorId.SESSION_RETURN_TITLE to returnScore,
            ),
        )
        return LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                state = LabyrinthEntryPageState.HOME,
                confidence = 0.90,
                stateScores = mapOf(LabyrinthEntryPageState.HOME to 0.90),
                anchorScores = scores,
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1,
            anchorMatches = mapOf(
                EntryAnchorId.SESSION_RETURN_TITLE to EntryAnchorMatch(returnScore, returnRect),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
        )
    }
}
