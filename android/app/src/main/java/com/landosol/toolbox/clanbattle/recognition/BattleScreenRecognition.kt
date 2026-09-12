package com.landosol.toolbox.clanbattle.recognition

enum class BattleScreenStage { BATTLE_START, LOADING, UNKNOWN }

data class BattleScreenCrops(
    val startBattle: PixelImage,
    val loading: PixelImage,
)

data class BattleScreenRecognition(
    val stage: BattleScreenStage,
    val confidence: Double,
    val startScore: Double,
    val loadingScore: Double,
    val reason: String? = null,
)

class BattleScreenRecognizer(
    private val templates: BattleScreenTemplates,
    private val minScore: Double = 0.72,
    private val minMargin: Double = 0.05,
) {
    fun recognize(crops: BattleScreenCrops): BattleScreenRecognition {
        val startScore = TemplateMatcher.score(crops.startBattle, templates.startBattle)
        val loadingScore = TemplateMatcher.score(crops.loading, templates.loading)
        val best = maxOf(startScore, loadingScore)
        val margin = kotlin.math.abs(startScore - loadingScore)
        val stage = when {
            best < minScore -> BattleScreenStage.UNKNOWN
            margin < minMargin -> BattleScreenStage.UNKNOWN
            startScore > loadingScore -> BattleScreenStage.BATTLE_START
            else -> BattleScreenStage.LOADING
        }
        return BattleScreenRecognition(
            stage = stage,
            confidence = when (stage) {
                BattleScreenStage.UNKNOWN -> 0.0
                else -> minOf(1.0, ((best - minScore) / (1.0 - minScore)).coerceAtLeast(0.0) + 0.5)
            },
            startScore = startScore,
            loadingScore = loadingScore,
            reason = if (stage == BattleScreenStage.UNKNOWN) "battle-stage-untrusted" else null,
        )
    }
}
