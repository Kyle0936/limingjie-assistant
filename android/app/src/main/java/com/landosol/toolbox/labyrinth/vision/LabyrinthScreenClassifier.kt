package com.landosol.toolbox.labyrinth.vision

enum class LabyrinthScreenStage {
    HOME,
    ADVENTURE_MENU,
    LABYRINTH_HOME,
    GUILD_SELECT,
    BATTLE_CONFIRM,
    UNKNOWN,
}

data class LabyrinthAnchorScores(val values: Map<String, Double>) {
    init {
        require(values.keys.none(String::isBlank))
        require(values.values.all { it in 0.0..1.0 })
    }

    operator fun get(id: String): Double = values[id] ?: 0.0
}

data class LabyrinthScreenObservation(
    val stage: LabyrinthScreenStage,
    val confidence: Double,
    val stageScores: Map<LabyrinthScreenStage, Double>,
    val anchorScores: LabyrinthAnchorScores,
    val reason: String? = null,
)

/**
 * Combines already measured visual anchors into a screen stage. It does not own screenshots
 * or produce actions, so an UNKNOWN result can always act as a hard click gate.
 */
class LabyrinthScreenClassifier(
    private val minScore: Double = 0.72,
    private val minMargin: Double = 0.05,
) {
    init {
        require(minScore in 0.0..1.0)
        require(minMargin in 0.0..1.0)
    }

    fun classify(anchorScores: LabyrinthAnchorScores): LabyrinthScreenObservation {
        val stageScores = DEFINITIONS.associate { definition ->
            definition.stage to definition.score(anchorScores)
        }
        val ranked = stageScores.entries.sortedByDescending(Map.Entry<LabyrinthScreenStage, Double>::value)
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.value ?: 0.0
        val stage = when {
            best.value < minScore -> LabyrinthScreenStage.UNKNOWN
            best.value - second < minMargin -> LabyrinthScreenStage.UNKNOWN
            else -> best.key
        }
        return LabyrinthScreenObservation(
            stage = stage,
            confidence = if (stage == LabyrinthScreenStage.UNKNOWN) 0.0 else best.value,
            stageScores = stageScores,
            anchorScores = anchorScores,
            reason = if (stage == LabyrinthScreenStage.UNKNOWN) "labyrinth-screen-untrusted" else null,
        )
    }

    private data class Definition(
        val stage: LabyrinthScreenStage,
        val allOf: List<String>,
        val anyOf: List<String> = emptyList(),
    ) {
        fun score(scores: LabyrinthAnchorScores): Double {
            val required = allOf.minOf(scores::get)
            val optionalGroup = if (anyOf.isEmpty()) 1.0 else anyOf.maxOf(scores::get)
            return minOf(required, optionalGroup)
        }
    }

    private companion object {
        val DEFINITIONS = listOf(
            Definition(LabyrinthScreenStage.HOME, allOf = listOf("home.adventure")),
            Definition(LabyrinthScreenStage.ADVENTURE_MENU, allOf = listOf("labyrinth.entry")),
            Definition(
                LabyrinthScreenStage.LABYRINTH_HOME,
                allOf = listOf("labyrinth.start", "ticket.owned"),
            ),
            Definition(
                LabyrinthScreenStage.GUILD_SELECT,
                allOf = listOf("guild.confirm"),
                anyOf = listOf(
                    "guild.labilins",
                    "guild.gourmet",
                    "guild.dawnstar",
                    "guild.nightmare",
                    "guild.saren",
                ),
            ),
            Definition(LabyrinthScreenStage.BATTLE_CONFIRM, allOf = listOf("battle.confirm")),
        )
    }
}
