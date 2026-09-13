package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.landosol.toolbox.labyrinth.LabyrinthExEncounterCatalog
import com.landosol.toolbox.labyrinth.LabyrinthExEncounterStrategy

/**
 * Android-only OCR resolver for EX encounter identity.
 *
 * It never recognizes animated monster artwork.  On the challenge page it reads a broad text ROI
 * for “极难” and single-EX names.  For multi-monster EX the session opens slot 3's info dialog;
 * this resolver then reads only the stable monster-name row.  Two distinct OCR completions must
 * agree before an identity is trusted.
 */
class AndroidLabyrinthExEncounterResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead)
    private var lastChallengeEncounterId: String? = null
    private var lastChallengeEvidenceId = Long.MIN_VALUE
    private var challengeStableReads = 0
    private var lastDetailEncounterId: String? = null
    private var lastDetailEvidenceId = Long.MIN_VALUE
    private var detailStableReads = 0

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        val page = result.observation.state

        // A monster-detail modal does not necessarily replace the challenge-page anchors: the
        // dimmed BATTLE_CHALLENGE page remains visible behind it and can still win the generic
        // page classifier.  Modal structure therefore owns the frame before challenge-page OCR.
        // Keep the probe narrow to challenge/UNKNOWN states so unrelated light dialogs cannot be
        // mistaken for an EX detail panel.
        if (
            (page == LabyrinthEntryPageState.BATTLE_CHALLENGE ||
                page == LabyrinthEntryPageState.UNKNOWN) &&
            looksLikeMonsterDetail(bitmap)
        ) {
            resetChallengeTracking()
            val nameRect = map(bitmap, DETAIL_NAME_RECT) ?: return result
            val closeRect = map(bitmap, DETAIL_CLOSE_RECT) ?: return result
            val read = ocr.read(bitmap, DETAIL_OCR_SLOT, nameRect, scale = 2)
            val text = read?.text
            val matched = LabyrinthExEncounterCatalog.matchObservedName(text)
            val trustedStrategy = updateDetailStability(matched, read?.id)
            return result.copy(
                exEncounter = LabyrinthExEncounterObservation(
                    monsterDetailOpen = true,
                    monsterDetailText = text,
                    encounterId = trustedStrategy?.id,
                    encounterName = trustedStrategy?.identityName,
                    trusted = trustedStrategy != null,
                    closeButtonRect = closeRect,
                    evidenceId = read?.id,
                ),
                matchedFeatures = (result.matchedFeatures + EX_MONSTER_DETAIL_FEATURE).distinct(),
            )
        }

        if (page == LabyrinthEntryPageState.BATTLE_CHALLENGE) {
            resetDetailTracking()
            val structural = result.exChallenge
            val difficulty = LabyrinthChallengeDifficultyResolver.resolve(
                normalScore = result.observation.anchorScores[EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_NORMAL],
                extremeScore = result.observation.anchorScores[EntryAnchorId.BATTLE_CHALLENGE_SUFFIX_EXTREME],
            )

            // Multi-monster and special dual-target EX identities are intentionally resolved by
            // opening one stable monster-detail panel.  Only an unstructured challenge page is
            // eligible for direct single-target name OCR.
            val singleTargetCandidate = structural?.multiMonsterLikely != true &&
                structural?.specialDualLikely != true
            val nameRead = if (singleTargetCandidate) {
                map(bitmap, SINGLE_TARGET_NAME_RECT)?.let { rect ->
                    ocr.read(bitmap, SINGLE_TARGET_NAME_OCR_SLOT, rect, scale = 3)
                }
            } else {
                null
            }
            val nameText = nameRead?.text
            val matched = if (singleTargetCandidate) {
                LabyrinthExEncounterCatalog.matchObservedName(nameText)
                    ?.takeIf { strategy -> strategy.targetCount == 1 }
            } else {
                null
            }
            val trustedStrategy = updateChallengeStability(matched, nameRead?.id)
            return result.copy(
                exEncounter = LabyrinthExEncounterObservation(
                    challengeText = nameText,
                    challengeTitleText = difficulty.name,
                    challengeDifficultyResolved = difficulty != LabyrinthChallengeDifficulty.UNKNOWN,
                    extremeChallenge = difficulty == LabyrinthChallengeDifficulty.EXTREME,
                    multiMonsterLikely = structural?.multiMonsterLikely == true,
                    slot3InfoButtonRect = structural?.slot3InfoButtonRect,
                    specialDualLikely = structural?.specialDualLikely == true,
                    specialDualIdentityInfoButtonRect = structural?.specialDualIdentityInfoButtonRect,
                    encounterId = trustedStrategy?.id,
                    encounterName = trustedStrategy?.identityName,
                    trusted = trustedStrategy != null,
                    evidenceId = nameRead?.id,
                ),
            )
        }

        resetChallengeTracking()
        // Do not clear the physical OCR scheduler on every unrelated animation; its own ROI
        // fingerprints prevent stale reads from applying to a changed frame.
        resetDetailTracking()
        return result
    }

    @Synchronized
    private fun updateChallengeStability(
        strategy: LabyrinthExEncounterStrategy?,
        evidenceId: Long?,
    ): LabyrinthExEncounterStrategy? {
        val id = strategy?.id ?: run {
            lastChallengeEncounterId = null
            challengeStableReads = 0
            return null
        }
        if (id != lastChallengeEncounterId) {
            lastChallengeEncounterId = id
            lastChallengeEvidenceId = Long.MIN_VALUE
            challengeStableReads = 0
        }
        if (evidenceId != null && evidenceId != lastChallengeEvidenceId) {
            lastChallengeEvidenceId = evidenceId
            challengeStableReads++
        }
        return strategy.takeIf { challengeStableReads >= REQUIRED_STABLE_OCR_READS }
    }

    @Synchronized
    private fun updateDetailStability(
        strategy: LabyrinthExEncounterStrategy?,
        evidenceId: Long?,
    ): LabyrinthExEncounterStrategy? {
        val id = strategy?.id ?: run {
            lastDetailEncounterId = null
            detailStableReads = 0
            return null
        }
        if (id != lastDetailEncounterId) {
            lastDetailEncounterId = id
            lastDetailEvidenceId = Long.MIN_VALUE
            detailStableReads = 0
        }
        if (evidenceId != null && evidenceId != lastDetailEvidenceId) {
            lastDetailEvidenceId = evidenceId
            detailStableReads++
        }
        return strategy.takeIf { detailStableReads >= REQUIRED_STABLE_OCR_READS }
    }

    private fun resetChallengeTracking() {
        lastChallengeEncounterId = null
        lastChallengeEvidenceId = Long.MIN_VALUE
        challengeStableReads = 0
    }

    private fun resetDetailTracking() {
        lastDetailEncounterId = null
        lastDetailEvidenceId = Long.MIN_VALUE
        detailStableReads = 0
    }

    private fun looksLikeMonsterDetail(bitmap: Bitmap): Boolean {
        val header = map(bitmap, DETAIL_HEADER_SAMPLE) ?: return false
        val nameBand = map(bitmap, DETAIL_NAME_SAMPLE) ?: return false
        val body = map(bitmap, DETAIL_BODY_SAMPLE) ?: return false
        val close = map(bitmap, DETAIL_CLOSE_SAMPLE) ?: return false
        return coverage(bitmap, header, ::isBlue) >= MIN_HEADER_BLUE &&
            coverage(bitmap, nameBand, ::isLight) >= MIN_NAME_LIGHT &&
            coverage(bitmap, body, ::isLight) >= MIN_BODY_LIGHT &&
            coverage(bitmap, close, ::isLight) >= MIN_CLOSE_LIGHT
    }

    private fun coverage(
        bitmap: Bitmap,
        rect: EntryPixelRect,
        predicate: (Int, Int, Int) -> Boolean,
    ): Double {
        var matched = 0
        var samples = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var x = rect.left
            while (x < rect.left + rect.width) {
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                if (predicate(red, green, blue)) matched++
                samples++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else matched.toDouble() / samples
    }

    private fun map(bitmap: Bitmap, rect: EntryReferenceRect): EntryPixelRect? =
        ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, rect)

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)

        // The challenge title and single-target boss name are read independently.  Keeping the
        // latter away from Lv/HP text and animated artwork materially improves OCR stability.
        // These were mapped from a 1280x591 reference screenshot through ReferenceFitMapper; the
        // exact screenshot resolution is not assumed at runtime.
        val CHALLENGE_TITLE_RECT = EntryReferenceRect(60, 60, 620, 100)
        // Current client renders the single-target name lower than the old calibration.  The old
        // 530..605 band clipped the lower half of names such as "反物质兽" while wasting much of
        // the crop on animated background.  Keep the crop narrow and just above the HP bar.
        val SINGLE_TARGET_NAME_RECT = EntryReferenceRect(520, 550, 420, 65)
        const val CHALLENGE_TITLE_OCR_SLOT = "ex-challenge-title"
        const val SINGLE_TARGET_NAME_OCR_SLOT = "ex-single-target-name"
        const val DETAIL_OCR_SLOT = "ex-monster-detail-name"

        // Calibrated from the current 1920x1080 monster-detail modal.  The name row is deliberately
        // isolated from HP numbers and the long description so fuzzy matching has very little noise.
        // Keep only the actual name row. The previous 530..1380 x 135..240 crop included the
        // decorative blue curl to the left (OCR'd as a stable leading "C" on 爆炸・遗物), the
        // underline and the top edge of the stats panel. A tighter static crop also gives the OCR
        // scheduler a stable fingerprint so its required second independent read can complete.
        val DETAIL_NAME_RECT = EntryReferenceRect(585, 140, 795, 70)
        val DETAIL_CLOSE_RECT = EntryReferenceRect(750, 915, 420, 115)
        val DETAIL_HEADER_SAMPLE = EntryReferenceRect(500, 45, 900, 90)
        val DETAIL_NAME_SAMPLE = EntryReferenceRect(540, 140, 820, 90)
        val DETAIL_BODY_SAMPLE = EntryReferenceRect(520, 500, 850, 300)
        val DETAIL_CLOSE_SAMPLE = EntryReferenceRect(790, 930, 340, 60)

        const val REQUIRED_STABLE_OCR_READS = 2
        const val MIN_HEADER_BLUE = 0.35
        const val MIN_NAME_LIGHT = 0.70
        const val MIN_BODY_LIGHT = 0.50
        const val MIN_CLOSE_LIGHT = 0.70
        const val SAMPLE_STEP = 4
        const val EX_MONSTER_DETAIL_FEATURE = "entry.ex.monster_detail"

        fun isBlue(red: Int, green: Int, blue: Int): Boolean =
            blue >= 145 && blue - red >= 20 && blue - green >= 5

        fun isLight(red: Int, green: Int, blue: Int): Boolean =
            red >= 195 && green >= 195 && blue >= 195
    }
}
