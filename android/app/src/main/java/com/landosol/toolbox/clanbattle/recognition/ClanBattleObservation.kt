package com.landosol.toolbox.clanbattle.recognition

import com.landosol.toolbox.automation.CoordinateMapper
import com.landosol.toolbox.automation.PixelRect
import com.landosol.toolbox.automation.PixelSize
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import kotlin.math.ceil
import kotlin.math.floor

data class ReferenceRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0)
    }
}

data class ClanBattleVisionProfile(
    val referenceSize: PixelSize = PixelSize(1920, 1080),
    val startBattle: ReferenceRegion? = ReferenceRegion(1565, 850, 275, 115),
    val loading: ReferenceRegion? = ReferenceRegion(1545, 955, 190, 60),
    val clock: ReferenceRegion? = ReferenceRegion(1619, 38, 64, 27),
    val menuButton: ReferenceRegion? = ReferenceRegion(1761, 33, 87, 37),
    val energyHud: ReferenceRegion? = ReferenceRegion(384, 1034, 1160, 25),
    val energySlots: Map<BattleSlot, EnergyRegion> = defaultEnergySlots(),
    val autoButton: ReferenceRegion? = ReferenceRegion(1783, 795, 95, 78),
    val globalSetButton: ReferenceRegion? = ReferenceRegion(1788, 644, 87, 86),
    val roleSetBadges: Map<BattleSlot, ReferenceRegion> = BattleSlot.entries.associateWith { slot ->
        ReferenceRegion(540 + slot.ordinal * 240, 761, 74, 73)
    },
) {
    companion object {
        fun defaultEnergySlots(): Map<BattleSlot, EnergyRegion> = mapOf(
            BattleSlot.SLOT_1 to EnergyRegion(8, 6, 176, 13),
            BattleSlot.SLOT_2 to EnergyRegion(248, 6, 176, 13),
            BattleSlot.SLOT_3 to EnergyRegion(488, 6, 176, 13),
            BattleSlot.SLOT_4 to EnergyRegion(728, 6, 176, 13),
            BattleSlot.SLOT_5 to EnergyRegion(968, 6, 176, 13),
        )
    }
}

enum class ClanBattleScreenKind {
    BATTLE_START,
    LOADING,
    BATTLE,
    BATTLE_MENU_OR_OVERLAY,
    UNKNOWN,
    UNSUPPORTED_ORIENTATION,
}

data class FrameViewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val scale: Float,
)

data class ClanBattleObservation(
    val frameTimestampMillis: Long,
    val frameSize: PixelSize,
    val viewport: FrameViewport?,
    val screenKind: ClanBattleScreenKind,
    val screenConfidence: Double,
    val clock: RecognitionResult?,
    val filteredClock: FilterResult?,
    val energy: EnergyObservation?,
    val controls: BattleControlObservation?,
    val actionSafe: Boolean,
    val shouldPause: Boolean,
    val diagnostics: List<String>,
    val menuButton: MenuButtonObservation? = null,
)

/** Pure Kotlin frame processor. It emits observations only and never performs actions. */
class ClanBattleFrameProcessor(
    templates: DigitTemplates,
    private val profile: ClanBattleVisionProfile = ClanBattleVisionProfile(),
    private val screenRecognizer: BattleScreenRecognizer? = null,
    private val controlRecognizer: BattleControlRecognizer? = null,
    private val menuRecognizer: BattleMenuRecognizer? = null,
    private val clockFilter: RecognitionFilter = RecognitionFilter(minConfidence = 0.75),
) {
    private val clockRecognizer = ClockRecognizer(templates)
    private var energyRecognizer: EnergyRecognizer? = null
    private var energyHudSize: PixelSize? = null
    private var previousFrameSize: PixelSize? = null

    fun process(image: PixelImage, timestampMillis: Long): ClanBattleObservation {
        val frameSize = PixelSize(image.width, image.height)
        val diagnostics = mutableListOf<String>()
        if (image.width <= image.height) {
            resetTemporalState()
            return ClanBattleObservation(
                timestampMillis,
                frameSize,
                viewport = null,
                screenKind = ClanBattleScreenKind.UNSUPPORTED_ORIENTATION,
                screenConfidence = 1.0,
                clock = null,
                filteredClock = null,
                energy = null,
                controls = null,
                actionSafe = false,
                shouldPause = true,
                diagnostics = listOf("portrait-frame"),
                menuButton = null,
            )
        }
        if (previousFrameSize != null && previousFrameSize != frameSize) {
            resetTemporalState()
            diagnostics += "frame-size-changed"
        }
        previousFrameSize = frameSize

        val mapping = CoordinateMapper(profile.referenceSize).createMapping(
            PixelRect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
        )
        val viewport = FrameViewport(
            left = floor(mapping.viewport.left).toInt(),
            top = floor(mapping.viewport.top).toInt(),
            width = ceil(mapping.viewport.width).toInt(),
            height = ceil(mapping.viewport.height).toInt(),
            scale = mapping.scale,
        )

        val clock = profile.clock?.let { region ->
            extract(image, mapping.viewport, mapping.scale, region)?.let { clockRecognizer.recognize(it, minConfidence = 0.75) }
        }
        val filteredClock = clock?.let { clockFilter.update(it, timestampMillis) }
        if (clock == null) diagnostics += "clock-roi-unavailable"
        else if (!clock.ok) diagnostics += "clock:${clock.reason ?: "failed"}"

        val menuButton = menuRecognizer?.let { recognizer ->
            profile.menuButton
                ?.let { extract(image, mapping.viewport, mapping.scale, it) }
                ?.let(recognizer::recognize)
        }
        if (menuRecognizer != null) {
            when {
                menuButton == null -> diagnostics += "menu-button-roi-unavailable"
                !menuButton.trustworthy -> diagnostics += "menu-button-untrusted:${menuButton.score}"
            }
        }

        val energy = profile.energyHud?.let { region ->
            extract(image, mapping.viewport, mapping.scale, region)?.let { hud ->
                val size = PixelSize(hud.width, hud.height)
                if (energyRecognizer == null || energyHudSize != size) {
                    energyRecognizer = EnergyRecognizer(scaleEnergyRegions(profile.energySlots, size, region))
                    energyHudSize = size
                }
                energyRecognizer?.detect(hud)
            }
        }
        if (energy == null) diagnostics += "energy-roi-unavailable"

        val battleStage = screenRecognizer?.let { recognizer ->
            val start = profile.startBattle?.let { extract(image, mapping.viewport, mapping.scale, it) }
            val loading = profile.loading?.let { extract(image, mapping.viewport, mapping.scale, it) }
            if (start == null || loading == null) null else recognizer.recognize(BattleScreenCrops(start, loading))
        }
        battleStage?.reason?.let { diagnostics += "screen:$it" }

        val controls = controlRecognizer?.let { recognizer ->
            val auto = profile.autoButton?.let { extract(image, mapping.viewport, mapping.scale, it) }
            val global = profile.globalSetButton?.let { extract(image, mapping.viewport, mapping.scale, it) }
            val roles = profile.roleSetBadges.mapValues { (_, region) ->
                extract(image, mapping.viewport, mapping.scale, region)
            }
            if (auto == null || global == null || roles.values.any { it == null }) {
                diagnostics += "control-roi-unavailable"
                null
            } else {
                recognizer.recognize(BattleControlCrops(auto, global, roles.mapValues { it.value!! }))
            }
        }

        val clockUsable = filteredClock?.let { it.accepted || it.reason == "same-time" } == true
        val menuRequired = menuRecognizer != null
        val menuTrustworthy = menuButton?.trustworthy == true
        val battleConfidence = when {
            clockUsable && (!menuRequired || menuTrustworthy) && controls?.trustworthy == true -> 0.95
            clockUsable && (!menuRequired || menuTrustworthy) -> 0.80
            clock?.ok == true -> 0.60
            else -> 0.0
        }
        val screenKind = when (battleStage?.stage) {
            BattleScreenStage.BATTLE_START -> ClanBattleScreenKind.BATTLE_START
            BattleScreenStage.LOADING -> ClanBattleScreenKind.LOADING
            BattleScreenStage.UNKNOWN,
            null,
            -> when {
                clockUsable && menuRequired && !menuTrustworthy -> ClanBattleScreenKind.BATTLE_MENU_OR_OVERLAY
                battleConfidence >= 0.60 -> ClanBattleScreenKind.BATTLE
                else -> ClanBattleScreenKind.UNKNOWN
            }
        }
        val screenConfidence = when (screenKind) {
            ClanBattleScreenKind.BATTLE_START,
            ClanBattleScreenKind.LOADING,
            -> battleStage?.confidence ?: 0.0
            ClanBattleScreenKind.BATTLE -> battleConfidence
            ClanBattleScreenKind.BATTLE_MENU_OR_OVERLAY -> clock?.confidence ?: 0.0
            else -> 0.0
        }
        val actionSafe = screenKind == ClanBattleScreenKind.BATTLE &&
            clockUsable &&
            (!menuRequired || menuTrustworthy) &&
            controls?.trustworthy == true
        val shouldPause = filteredClock?.shouldPause == true ||
            controls?.let { !it.trustworthy } == true ||
            (menuRequired && !menuTrustworthy)
        return ClanBattleObservation(
            frameTimestampMillis = timestampMillis,
            frameSize = frameSize,
            viewport = viewport,
            screenKind = screenKind,
            screenConfidence = screenConfidence,
            clock = clock,
            filteredClock = filteredClock,
            energy = energy,
            controls = controls,
            actionSafe = actionSafe,
            shouldPause = shouldPause,
            diagnostics = diagnostics,
            menuButton = menuButton,
        )
    }

    fun resetTemporalState() {
        clockFilter.reset()
        energyRecognizer?.reset()
        previousFrameSize = null
    }

    private fun extract(
        image: PixelImage,
        viewport: PixelRect,
        scale: Float,
        region: ReferenceRegion,
    ): PixelImage? {
        val left = floor(viewport.left + region.x * scale).toInt().coerceIn(0, image.width)
        val top = floor(viewport.top + region.y * scale).toInt().coerceIn(0, image.height)
        val right = ceil(viewport.left + (region.x + region.width) * scale).toInt().coerceIn(0, image.width)
        val bottom = ceil(viewport.top + (region.y + region.height) * scale).toInt().coerceIn(0, image.height)
        if (right <= left || bottom <= top) return null
        return image.crop(left, top, right - left, bottom - top)
    }

    private fun scaleEnergyRegions(
        regions: Map<BattleSlot, EnergyRegion>,
        targetSize: PixelSize,
        source: ReferenceRegion,
    ): Map<BattleSlot, EnergyRegion> = regions.mapValues { (_, region) ->
        val left = region.x * targetSize.width / source.width
        val top = region.y * targetSize.height / source.height
        val right = (region.x + region.width) * targetSize.width / source.width
        val bottom = (region.y + region.height) * targetSize.height / source.height
        EnergyRegion(left, top, maxOf(1, right - left), maxOf(1, bottom - top))
    }
}
