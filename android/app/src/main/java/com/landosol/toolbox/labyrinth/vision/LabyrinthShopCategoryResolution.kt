package com.landosol.toolbox.labyrinth.vision

/**
 * Per visible title, at most three crop variants. A variant is retired only once its OCR read has
 * actually completed (or a long backstop elapses), never merely because time passed.
 *
 * OCR runs on one shared serial queue at 2-5 s per read, so a fixed 3 s-per-round wall clock
 * retired titles whose only read was still in flight: 2026-09-21 bundle 134750 shows all three
 * shop titles stuck at READING with no text, yet ticked round 1 -> 2 -> 3 on a 3 s cadence and
 * were marked EXHAUSTED, closing the shop after just one or two purchases.
 */
internal class LabyrinthShopCategoryResolution {
    private data class State(val fingerprint: Long, var attempt: Int = 0, var variantStartedAt: Long)
    private val slots = mutableMapOf<String, State>()

    fun clear() = slots.clear()

    /** The current crop-variant index (0..[MAX_ATTEMPTS]) for this slot. Does not advance itself. */
    fun attempt(slot: String, fingerprint: Long, now: Long): Int {
        val state = slots[slot]?.takeIf { it.fingerprint == fingerprint }
            ?: State(fingerprint, variantStartedAt = now).also { slots[slot] = it }
        return state.attempt
    }

    /**
     * Retire the current variant and move to the next only when its OCR actually finished
     * ([completed]) or it has been the active variant far longer than any single read should take.
     * The backstop exists solely so a dead OCR engine cannot hang the shop; a healthy device
     * always advances on completion.
     */
    fun noteVariantOutcome(slot: String, fingerprint: Long, variant: Int, completed: Boolean, now: Long) {
        val state = slots[slot]?.takeIf { it.fingerprint == fingerprint } ?: return
        if (variant != state.attempt || state.attempt >= MAX_ATTEMPTS) return
        if (completed || now - state.variantStartedAt >= VARIANT_STALL_BACKSTOP_MILLIS) {
            state.attempt++
            state.variantStartedAt = now
        }
    }

    fun resolve(item: LabyrinthShopItemMatch, text: String?, evidenceId: Long?, attempt: Int): LabyrinthShopItemMatch {
        val imprint = LabyrinthShopItemText.looksLikeRoleImprint(text)
        val label = LabyrinthShopItemText.roleImprintLabel(text)
        val ready = imprint || (!text.isNullOrBlank() && item.relicMatch?.recognized == true)
        return item.copy(
            kind = when {
                imprint -> LabyrinthShopItemKind.ROLE_IMPRINT
                ready -> LabyrinthShopItemKind.RELIC
                else -> LabyrinthShopItemKind.UNKNOWN
            },
            relicMatch = if (imprint) null else item.relicMatch,
            roleImprintLabel = label,
            choiceRoleImprint = imprint && LabyrinthShopItemText.isChoiceRoleImprint(text),
            titleText = text,
            titleEvidenceId = evidenceId,
            categoryState = when {
                ready -> LabyrinthShopCategoryState.READY
                attempt >= MAX_ATTEMPTS -> LabyrinthShopCategoryState.EXHAUSTED
                else -> LabyrinthShopCategoryState.READING
            },
            categoryAttempt = minOf(attempt + 1, MAX_ATTEMPTS),
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        /** No single ROI OCR read should take this long; only a stalled engine trips this. */
        const val VARIANT_STALL_BACKSTOP_MILLIS = 12_000L
    }
}
