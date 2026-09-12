package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import android.graphics.Bitmap
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.LabyrinthEventOcrDocument
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AndroidLabyrinthEventRecognitionData(
    val events: List<EventResource>,
    val ocr: LabyrinthEventOcrDocument,
)

class AndroidLabyrinthEventRecognitionDataLoader(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(): AndroidLabyrinthEventRecognitionData {
        val eventDocument = context.assets.open(EVENTS_PATH).bufferedReader().use { reader ->
            json.decodeFromString<EventCategoryDocument>(reader.readText())
        }
        val ocrDocument = context.assets.open(OCR_PATH).bufferedReader().use { reader ->
            json.decodeFromString<LabyrinthEventOcrDocument>(reader.readText())
        }
        require(eventDocument.schemaVersion == 1 && eventDocument.events.isNotEmpty()) {
            "事件目录为空或版本不受支持"
        }
        return AndroidLabyrinthEventRecognitionData(eventDocument.events, ocrDocument)
    }

    @Serializable
    private data class EventCategoryDocument(
        val schemaVersion: Int = 1,
        val events: List<EventResource> = emptyList(),
    )

    private companion object {
        const val ROOT = "resource-packs/cn-bilibili"
        const val EVENTS_PATH = "$ROOT/events.placeholder.json"
        const val OCR_PATH = "$ROOT/labyrinth-event-ocr.json"
    }
}

/**
 * Asynchronous Chinese-OCR fallback for event pages. It can promote an otherwise UNKNOWN frame
 * to EVENT_CHOICE, but [LabyrinthEventChoiceObservation.trusted] becomes true only after the same
 * catalog event is observed repeatedly. Button pixel evidence remains a separate click gate.
 */
class AndroidLabyrinthEventChoiceResolver(
    data: AndroidLabyrinthEventRecognitionData,
    private val submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val recognizer = LabyrinthEventTextRecognizer(data.events)
    private val layoutMapper = LabyrinthEventLayoutMapper(data.ocr.layouts)
    private var cached: CachedOcr? = null
    private var pendingFingerprint: Long? = null
    private var lastEventId: String? = null
    private var stableFrames = 0
    private var lastOcrRequestAtMillis = Long.MIN_VALUE

    @Synchronized
    fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult): LabyrinthEntryFrameResult {
        if (result.observation.state !in ELIGIBLE_STATES) {
            clearPageState()
            return result
        }
        // UNKNOWN occurs during many unrelated animations.  Infer the visible option count from
        // the calibrated blue buttons instead of merely asking whether *any* layout overlaps blue
        // pixels.  This also lets OCR use the matching layout's tighter text bounds.
        val confidencesByOptionCount = (1..3).associateWith { optionCount ->
            val rects = layoutMapper.buttonRects(optionCount, bitmap.width, bitmap.height)
            if (rects.size != optionCount) emptyList()
            else rects.map { rect -> blueButtonConfidence(bitmap, rect) }
        }
        val hasAnyEventButtonEvidence = confidencesByOptionCount.values
            .flatten()
            .any { it >= MINIMUM_EVENT_BUTTON_CANDIDATE }
        if (!hasAnyEventButtonEvidence) {
            clearPageState()
            return result
        }
        val completeLayoutCount = labyrinthCompleteEventLayoutCount(
            confidencesByOptionCount = confidencesByOptionCount,
            minimumButtonConfidence = MINIMUM_EVENT_BUTTON_CANDIDATE,
        )
        // A centered one-choice button is geometrically identical to the center button of the
        // three-choice layout.  For count=1 (or any incomplete/grey layout), use generic OCR first
        // and let the matched event resource tell us the real option count.
        val cropRect = completeLayoutCount
            ?.takeIf { it > 1 }
            ?.let { optionCount -> layoutMapper.ocrBounds(optionCount, bitmap.width, bitmap.height) }
            ?: ReferenceFitMapper.map(
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = OCR_REFERENCE_RECT,
        ) ?: return result
        val fingerprint = differenceHash(bitmap, cropRect)
        val observedText = cached?.takeIf { cache ->
            java.lang.Long.bitCount(cache.fingerprint xor fingerprint) <= MAX_CACHE_HASH_DISTANCE
        }?.text
        if (observedText == null) {
            schedule(bitmap, cropRect, fingerprint)
            return result
        }

        val match = recognizer.recognize(observedText)
        val event = match.event ?: run {
            lastEventId = null
            stableFrames = 0
            retryOcr(bitmap, cropRect, fingerprint)
            return result
        }
        stableFrames = if (lastEventId == event.id) stableFrames + 1 else 1
        lastEventId = event.id
        val orderedChoices = event.choices.sortedBy { it.slot }
        val buttonRects = layoutMapper.buttonRects(orderedChoices.size, bitmap.width, bitmap.height)
        if (buttonRects.size != orderedChoices.size) return result
        val visuals = orderedChoices.zip(buttonRects).map { (choice, rect) ->
            LabyrinthEventChoiceVisual(
                choice = choice,
                buttonRect = rect,
                buttonConfidence = blueButtonConfidence(bitmap, rect),
            )
        }
        val trusted = match.trusted && stableFrames >= REQUIRED_STABLE_FRAMES
        if (!match.trusted && stableFrames >= REQUIRED_STABLE_FRAMES) {
            retryOcr(bitmap, cropRect, fingerprint)
        }
        val eventObservation = LabyrinthEventChoiceObservation(
            event = event,
            choices = visuals,
            rawText = observedText,
            confidence = match.score,
            rivalMargin = match.margin,
            stableFrames = stableFrames,
            trusted = trusted,
        )
        val anchorScore = match.score.coerceIn(0.0, 1.0)
        val updatedAnchors = LabyrinthAnchorScores(
            result.observation.anchorScores.values + mapOf(
                EntryAnchorId.EVENT_CHOICE_TITLE to match.titleScore.coerceIn(0.0, 1.0),
                EntryAnchorId.EVENT_CHOICE_INSTRUCTION to match.choiceScore.coerceIn(0.0, 1.0),
                EntryAnchorId.EVENT_CHOICE_OPTION to anchorScore,
            ),
        )
        return result.copy(
            observation = result.observation.copy(
                state = LabyrinthEntryPageState.EVENT_CHOICE,
                confidence = anchorScore,
                stateScores = result.observation.stateScores +
                    (LabyrinthEntryPageState.EVENT_CHOICE to anchorScore),
                anchorScores = updatedAnchors,
                reason = if (trusted) {
                    "事件OCR已稳定匹配 ${event.id}"
                } else {
                    "事件OCR候选 ${event.id}，等待重复确认"
                },
            ),
            matchedFeatures = (result.matchedFeatures + EVENT_OCR_FEATURE).distinct(),
            eventChoiceSelection = eventObservation,
        )
    }

    private fun schedule(bitmap: Bitmap, rect: EntryPixelRect, fingerprint: Long) {
        if (pendingFingerprint != null) return
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        pendingFingerprint = fingerprint
        lastOcrRequestAtMillis = System.currentTimeMillis()
        runCatching {
            submitTextRead(crop) { text ->
                synchronized(this) {
                    val submitted = pendingFingerprint
                    pendingFingerprint = null
                    if (submitted == fingerprint && !text.isNullOrBlank()) {
                        cached = CachedOcr(fingerprint, text.trim())
                    }
                }
                if (!crop.isRecycled) crop.recycle()
            }
        }.onFailure {
            pendingFingerprint = null
            if (!crop.isRecycled) crop.recycle()
        }
    }

    /** A poor first OCR read must never pin a static event page forever. */
    private fun retryOcr(bitmap: Bitmap, rect: EntryPixelRect, fingerprint: Long) {
        if (pendingFingerprint != null) return
        val now = System.currentTimeMillis()
        if (lastOcrRequestAtMillis != Long.MIN_VALUE &&
            now - lastOcrRequestAtMillis < MIN_OCR_RETRY_INTERVAL_MILLIS
        ) return
        cached = null
        schedule(bitmap, rect, fingerprint)
    }

    private fun blueButtonConfidence(bitmap: Bitmap, rect: EntryPixelRect): Double {
        var blue = 0
        var sampled = 0
        for (row in 1..BUTTON_SAMPLE_ROWS) {
            val y = rect.top + (row.toDouble() * (rect.height - 1) / (BUTTON_SAMPLE_ROWS + 1)).roundToInt()
            for (column in 1..BUTTON_SAMPLE_COLUMNS) {
                val x = rect.left +
                    (column.toDouble() * (rect.width - 1) / (BUTTON_SAMPLE_COLUMNS + 1)).roundToInt()
                val color = bitmap.getPixel(x, y)
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val channelBlue = color and 0xff
                if (channelBlue >= 105 && channelBlue >= red + 18 && channelBlue >= green - 25) blue++
                sampled++
            }
        }
        return if (sampled == 0) 0.0 else blue.toDouble() / sampled
    }

    private fun differenceHash(bitmap: Bitmap, rect: EntryPixelRect): Long {
        var output = 0L
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
                    output = output or (1L shl bit)
                }
                bit++
            }
        }
        return output
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000
    }

    private fun clearPageState() {
        cached = null
        pendingFingerprint = null
        lastEventId = null
        stableFrames = 0
        lastOcrRequestAtMillis = Long.MIN_VALUE
    }

    private data class CachedOcr(val fingerprint: Long, val text: String)
    private companion object {
        val ELIGIBLE_STATES = setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.EVENT_CHOICE,
        )
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val OCR_REFERENCE_RECT = EntryReferenceRect(150, 95, 1620, 750)
        const val EVENT_OCR_FEATURE = "entry.event_choice.ocr"
        const val REQUIRED_STABLE_FRAMES = 2
        const val MAX_CACHE_HASH_DISTANCE = 8
        const val HASH_COLUMNS = 9
        const val HASH_ROWS = 8
        const val BUTTON_SAMPLE_COLUMNS = 16
        const val BUTTON_SAMPLE_ROWS = 8
        const val MINIMUM_EVENT_BUTTON_CANDIDATE = 0.12
        const val MIN_OCR_RETRY_INTERVAL_MILLIS = 1_500L
    }
}
