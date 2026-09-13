package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.landosol.toolbox.labyrinth.labyrinthRoleRewardNeedsNameVerification
import kotlin.math.roundToInt

/**
 * Android-only second-pass verifier for the three role-reward name rows.
 *
 * The expensive OCR path is deliberately narrow:
 * - only an icon result that is not independently safe for role-reward tapping is eligible;
 * - only icon candidates within a small score gap are compared;
 * - only the fixed name-row ROI is sent to OCR;
 * - a perceptual hash caches stable rows, so a stationary reward page is not OCR'd every 500 ms.
 */
class AndroidLabyrinthRoleRewardNameResolver(
    private val fuzzyMatcher: LabyrinthCharacterNameFuzzyMatcher = LabyrinthCharacterNameFuzzyMatcher(),
    private val submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val cache = mutableMapOf<String, CachedOcr>()
    private val pending = mutableMapOf<String, Long>()
    private var remainingNewOcrRuns = 0

    @Synchronized
    fun resolve(
        bitmap: Bitmap,
        result: LabyrinthEntryFrameResult,
    ): LabyrinthEntryFrameResult {
        if (result.observation.state != LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION) {
            // Every role-reward page is separated by another page/state in the live flow. Clearing
            // here avoids carrying a perceptual-hash hit into a later reward choice while keeping
            // the cache useful for a stationary page.
            cache.clear()
            return result
        }
        if (result.characterMatches.isEmpty()) return result

        // Even if all three portraits are ambiguous, initialize/process at most one new name ROI
        // on a capture frame. Stable cached rows are free to resolve together on later frames.
        remainingNewOcrRuns = MAX_NEW_OCR_RUNS_PER_FRAME
        var changed = false
        val resolved = result.characterMatches.map { match ->
            val updated = resolveMatch(bitmap, match)
            if (updated != match) changed = true
            updated
        }
        return if (changed) result.copy(characterMatches = resolved) else result
    }

    private fun resolveMatch(bitmap: Bitmap, match: LabyrinthCharacterMatch): LabyrinthCharacterMatch {
        if (!labyrinthRoleRewardNeedsNameVerification(match)) return match

        val referenceRect = NAME_RECTS[match.slotId] ?: return match
        val screenRect = ReferenceFitMapper.map(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = referenceRect,
        ) ?: return match
        val candidates = roleRewardNameCandidates(match)
        if (candidates.isEmpty()) return match

        val observedText = readCachedOrSchedule(bitmap, match.slotId, screenRect) ?: return match
        if (observedText.isBlank()) return match
        val evidence = fuzzyMatcher.match(
            observedText = observedText,
            candidates = candidates.map { candidate ->
                LabyrinthCharacterNameCandidate(
                    characterId = candidate.characterId,
                    displayName = candidate.displayName,
                    aliases = candidate.aliases,
                )
            },
        )
        val evidenceOnly = match.copy(
            nameEvidenceText = observedText,
            nameEvidenceScore = evidence.score,
            nameEvidenceMargin = evidence.margin,
        )
        if (!evidence.trusted) {
            val singleCharacter = roleRewardSingleCharacterOcrAssist(
                observedText = observedText,
                candidates = candidates,
                suspectedCharacterId = match.suspectedCharacterId,
                iconConfidence = match.confidence,
                iconMargin = match.rivalMargin,
            ) ?: return evidenceOnly
            val strongestOther = candidates
                .filter { it.characterId != singleCharacter.characterId }
                .maxOfOrNull(LabyrinthCharacterIconCandidate::confidence)
                ?: 0.0
            return evidenceOnly.copy(
                characterId = singleCharacter.characterId,
                displayName = singleCharacter.displayName,
                iconVariant = singleCharacter.iconVariant,
                confidence = singleCharacter.confidence,
                rivalMargin = singleCharacter.confidence - strongestOther,
                trusted = true,
                suspectedCharacterId = singleCharacter.characterId,
                suspectedDisplayName = singleCharacter.displayName,
                nameEvidenceScore = SINGLE_CHARACTER_OCR_ASSIST_SCORE,
                nameEvidenceMargin = SINGLE_CHARACTER_OCR_ASSIST_MARGIN,
                nameAssisted = true,
            )
        }

        val chosen = candidates.firstOrNull { it.characterId == evidence.characterId }
            ?: return evidenceOnly
        val strongestOther = candidates
            .filter { it.characterId != chosen.characterId }
            .maxOfOrNull(LabyrinthCharacterIconCandidate::confidence)
            ?: 0.0
        return evidenceOnly.copy(
            characterId = chosen.characterId,
            displayName = chosen.displayName,
            iconVariant = chosen.iconVariant,
            confidence = chosen.confidence,
            rivalMargin = chosen.confidence - strongestOther,
            trusted = true,
            suspectedCharacterId = chosen.characterId,
            suspectedDisplayName = chosen.displayName,
            nameAssisted = true,
        )
    }

    private fun readCachedOrSchedule(bitmap: Bitmap, slotId: String, rect: EntryPixelRect): String? {
        val fingerprint = differenceHash(bitmap, rect)
        cache[slotId]?.let { cached ->
            if (java.lang.Long.bitCount(cached.fingerprint xor fingerprint) <= MAX_CACHE_HASH_DISTANCE) {
                return cached.text
            }
        }
        // OCR is asynchronous so the MediaProjection/ImageReader callback is never blocked by ML
        // Kit model work. A later 500 ms frame consumes the cached result once it is ready.
        if (pending.containsKey(slotId)) return null
        if (remainingNewOcrRuns <= 0) return null
        remainingNewOcrRuns--
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        pending[slotId] = fingerprint
        runCatching {
            submitTextRead(crop) { text ->
                synchronized(this) {
                    val submittedFingerprint = pending.remove(slotId)
                    if (submittedFingerprint == fingerprint && !text.isNullOrBlank()) {
                        cache[slotId] = CachedOcr(fingerprint, text.trim())
                    }
                }
                if (!crop.isRecycled) crop.recycle()
            }
        }.onFailure {
            pending.remove(slotId)
            if (!crop.isRecycled) crop.recycle()
        }
        return null
    }

    /** 9x8 dHash: cheap enough to evaluate on every ambiguous frame and robust to tiny UI noise. */
    private fun differenceHash(bitmap: Bitmap, rect: EntryPixelRect): Long {
        var result = 0L
        var bit = 0
        for (sampleY in 0 until HASH_ROWS) {
            val y = rect.top +
                (sampleY.toDouble() * (rect.height - 1) / (HASH_ROWS - 1)).roundToInt()
            for (sampleX in 0 until HASH_COLUMNS - 1) {
                val firstX = rect.left +
                    (sampleX.toDouble() * (rect.width - 1) / (HASH_COLUMNS - 1)).roundToInt()
                val secondX = rect.left +
                    ((sampleX + 1).toDouble() * (rect.width - 1) / (HASH_COLUMNS - 1)).roundToInt()
                if (luminance(bitmap.getPixel(firstX, y)) > luminance(bitmap.getPixel(secondX, y))) {
                    result = result or (1L shl bit)
                }
                bit++
            }
        }
        return result
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000
    }

    private data class CachedOcr(val fingerprint: Long, val text: String)

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val NAME_RECTS = mapOf(
            // The left diagnostic overlay reaches roughly x=330 in the 1920 reference layout.
            // Start after it on purpose: losing part of the first glyph is acceptable because the
            // fuzzy matcher can confirm from "蕾西亚" / other partial core-name combinations.
            "role_reward_left" to EntryReferenceRect(335, 650, 280, 135),
            "role_reward_center" to EntryReferenceRect(820, 650, 300, 135),
            "role_reward_right" to EntryReferenceRect(1370, 650, 300, 135),
        )
        const val MAX_ICON_GAP_FOR_NAME_ASSIST = 0.08
        const val MAX_CACHE_HASH_DISTANCE = 6
        const val MAX_NEW_OCR_RUNS_PER_FRAME = 1
        const val HASH_COLUMNS = 9
        const val HASH_ROWS = 8
        const val SINGLE_CHARACTER_OCR_ASSIST_SCORE = 0.82
        const val SINGLE_CHARACTER_OCR_ASSIST_MARGIN = 0.16
    }
}

/**
 * Single-character role names are a special OCR failure mode: a visually similar Han glyph can be
 * substituted every frame (露 -> 路 in a live report), while edit-distance=1 is meaningless for an
 * arbitrary one-character vocabulary.  Do not maintain a hand-written glyph table.  Instead allow
 * text to break the tie only when the icon shortlist itself supplies exactly one one-character
 * role and that role is already the portrait matcher's moderately separated best hypothesis.
 */
internal fun roleRewardSingleCharacterOcrAssist(
    observedText: String,
    candidates: List<LabyrinthCharacterIconCandidate>,
    suspectedCharacterId: String?,
    iconConfidence: Double,
    iconMargin: Double,
): LabyrinthCharacterIconCandidate? {
    val observed = observedText.filter(Char::isLetterOrDigit)
    if (observed.length != 1) return null
    if (iconConfidence < 0.45 || iconMargin < 0.06) return null
    val singleCharacterCandidates = candidates.filter { candidate ->
        val names = sequenceOf(candidate.displayName) + candidate.aliases.asSequence()
        names.any { name ->
            name.substringBefore('(').substringBefore('（')
                .filter(Char::isLetterOrDigit)
                .length == 1
        }
    }
    if (singleCharacterCandidates.size != 1) return null
    return singleCharacterCandidates.single()
        .takeIf { it.characterId == suspectedCharacterId }
}

/**
 * Keeps OCR constrained to the portrait matcher's nearby alternatives without reapplying the
 * portrait trust floor. A low best score is itself a reason to ask the visible name row for help.
 */
internal fun roleRewardNameCandidates(
    match: LabyrinthCharacterMatch,
    maximumIconGap: Double = 0.08,
): List<LabyrinthCharacterIconCandidate> {
    val bestIconScore = match.iconCandidates.firstOrNull()?.confidence ?: return emptyList()
    return match.iconCandidates.filter { candidate ->
        bestIconScore - candidate.confidence <= maximumIconGap
    }
}

/** One bundled recognizer instance for the application process; model initialization is reused. */
internal object AndroidChineseRoleNameOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun readAsync(bitmap: Bitmap, callback: (String?) -> Unit) {
        recognizer
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result -> callback(result.text.trim()) }
            .addOnFailureListener { callback(null) }
    }
}
