package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/**
 * Reads only the title rows of visible shop goods that did not pass relic identity safely.
 *
 * Role-imprint icons are deliberately similar enough to some relic assets to create a high
 * "suspected relic" score.  The game-visible title is a much stronger discriminator: goods such
 * as "坦克型职能的选择印记" and "破防型职能的随机印记" are character-acquisition items, not relics.
 * OCR stays asynchronous and bounded by AndroidRelicRoiOcr, so it never blocks capture frames.
 */
class AndroidLabyrinthShopItemResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead)

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state != LabyrinthEntryPageState.SHOP) {
            ocr.clear()
            return result
        }
        val shop = result.shopObservation ?: return result
        val items = shop.items.map { item ->
            // A safely identified relic keeps the existing relic matcher as the authority. The
            // OCR fallback is for exactly the ambiguous/non-relic case seen in live shops.
            if (item.relicMatch?.recognized == true) {
                return@map item.copy(kind = LabyrinthShopItemKind.RELIC)
            }
            val referenceRect = TITLE_RECTS[item.slotId] ?: return@map item
            val titleRect = ReferenceFitMapper.map(
                bitmap.width,
                bitmap.height,
                STANDARD_REFERENCE,
                referenceRect,
            ) ?: return@map item
            val read = ocr.read(bitmap, "shop-title-${item.slotId}", titleRect, scale = 2)
                ?: return@map item
            val imprint = LabyrinthShopItemText.roleImprintLabel(read.text)
            val roleImprint = imprint != null || LabyrinthShopItemText.looksLikeRoleImprint(read.text)
            if (roleImprint) {
                item.copy(
                    // The large purple imprint body is shared between role classes and can look
                    // deceptively similar to relic assets. Once title semantics prove this is an
                    // imprint, discard the relic hypothesis entirely. Exact class identity is
                    // never inferred from the shared body; future visual evidence must use only
                    // the lower-right role badge ROI.
                    relicMatch = null,
                    kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                    titleText = read.text,
                    roleImprintLabel = imprint,
                    titleEvidenceId = read.id,
                )
            } else {
                item.copy(
                    titleText = read.text,
                    titleEvidenceId = read.id,
                )
            }
        }
        return result.copy(shopObservation = shop.copy(items = items))
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        // Keep only the title glyphs. The old 72px-tall crop included the blue decorative curl
        // and underline, which made the first (治疗) imprint substantially less stable for OCR.
        val TITLE_RECTS = mapOf(
            "shop_item_1" to EntryReferenceRect(125, 230, 390, 55),
            "shop_item_2" to EntryReferenceRect(703, 230, 390, 55),
            "shop_item_3" to EntryReferenceRect(1278, 230, 390, 55),
        )
    }
}
