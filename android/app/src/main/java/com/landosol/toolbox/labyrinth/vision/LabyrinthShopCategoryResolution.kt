package com.landosol.toolbox.labyrinth.vision

/** Per visible title, at most three crop variants. A cached OCR read is not a new attempt. */
internal class LabyrinthShopCategoryResolution {
    private data class State(val fingerprint: Long, var started: Long, var attempt: Int = 0)
    private val slots = mutableMapOf<String, State>()

    fun clear() = slots.clear()

    fun attempt(slot: String, fingerprint: Long, now: Long): Int {
        val state = slots[slot]?.takeIf { it.fingerprint == fingerprint }
            ?: State(fingerprint, now).also { slots[slot] = it }
        if (state.attempt < 3 && now - state.started >= 3_000) {
            state.attempt++
            state.started = now
        }
        return state.attempt
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
            titleText = text,
            titleEvidenceId = evidenceId,
            categoryState = when {
                ready -> LabyrinthShopCategoryState.READY
                attempt >= 3 -> LabyrinthShopCategoryState.EXHAUSTED
                else -> LabyrinthShopCategoryState.READING
            },
            categoryAttempt = minOf(attempt + 1, 3),
        )
    }
}
