package com.landosol.toolbox.labyrinth

/** One mandatory opening slot. The first available candidate wins. */
data class LabyrinthOpeningRosterSlot(
    val candidates: List<LabyrinthOpeningCharacter>,
) {
    init {
        require(candidates.isNotEmpty())
        require(candidates.map(LabyrinthOpeningCharacter::characterId).distinct().size == candidates.size)
    }
}

data class LabyrinthOpeningCharacter(
    val characterId: String,
    val displayName: String,
) {
    init {
        require(characterId.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class LabyrinthOpeningRosterConfig(
    val guildId: Int,
    val guildName: String,
    val slots: List<LabyrinthOpeningRosterSlot>,
    /**
     * Characters the guild grants automatically at the opening, on top of the three picks. They
     * arrive as their own 角色加入 popup (2026-09-17: 拉比林斯 grants 菈比莉斯塔) and belong to
     * the run roster from the first frame, whether or not that popup's portrait is recognised.
     */
    val grantedCharacters: List<LabyrinthOpeningCharacter> = emptyList(),
) {
    init {
        require(guildId > 0)
        require(guildName.isNotBlank())
        require(slots.size == REQUIRED_OPENING_CHARACTERS) {
            "Opening roster must contain exactly $REQUIRED_OPENING_CHARACTERS slots"
        }
    }

    companion object {
        const val REQUIRED_OPENING_CHARACTERS = 3
    }
}

sealed interface LabyrinthOpeningRosterDecision {
    data class Ready(
        val characters: List<LabyrinthOpeningCharacter>,
        val reasons: List<String>,
    ) : LabyrinthOpeningRosterDecision

    data class Missing(
        val missingSlotNumbers: List<Int>,
        val reason: String,
    ) : LabyrinthOpeningRosterDecision
}

/** Resolves the three configured slots without replacing unavailable choices with unrelated roles. */
class LabyrinthOpeningRosterPolicy(
    private val config: LabyrinthOpeningRosterConfig,
) {
    /** True once every mandatory slot is satisfied by one of its configured candidates. */
    fun isSatisfied(selectedCharacterIds: Set<String>): Boolean =
        config.slots.all { slot ->
            slot.candidates.any { candidate -> candidate.characterId in selectedCharacterIds }
        }

    /** Mandatory slot numbers that are still missing from the accumulated opening selection. */
    fun missingSlotNumbers(selectedCharacterIds: Set<String>): List<Int> =
        config.slots.mapIndexedNotNull { index, slot ->
            (index + 1).takeIf {
                slot.candidates.none { candidate -> candidate.characterId in selectedCharacterIds }
            }
        }

    /**
     * Picks one currently visible candidate for an as-yet unsatisfied slot.
     *
     * This is intentionally viewport-local: the planner keeps selected ids across scrolls, while
     * this policy only answers whether the current screen contains something useful to click.
     */
    fun nextVisibleCandidate(
        availableCharacterIds: Set<String>,
        selectedCharacterIds: Set<String>,
    ): LabyrinthOpeningCharacter? {
        config.slots.forEach { slot ->
            if (slot.candidates.any { it.characterId in selectedCharacterIds }) return@forEach
            slot.candidates.firstOrNull { it.characterId in availableCharacterIds }?.let { return it }
        }
        return null
    }

    fun missingReason(selectedCharacterIds: Set<String>): String {
        val missing = missingSlotNumbers(selectedCharacterIds)
        return if (missing.isEmpty()) {
            "${config.guildName}初始方案已满足"
        } else {
            "${config.guildName}初始方案第${missing.joinToString("、")}槽滑到底仍未找到可靠候选，禁止随机补位"
        }
    }

    fun choose(availableCharacterIds: Set<String>): LabyrinthOpeningRosterDecision {
        val selected = mutableListOf<LabyrinthOpeningCharacter>()
        val reasons = mutableListOf<String>()
        val missing = mutableListOf<Int>()
        config.slots.forEachIndexed { index, slot ->
            val choice = slot.candidates.firstOrNull { it.characterId in availableCharacterIds }
            if (choice == null) {
                missing += index + 1
            } else {
                selected += choice
                val priority = slot.candidates.indexOf(choice) + 1
                reasons += if (priority == 1) {
                    "第${index + 1}槽选择${choice.displayName}（首选）"
                } else {
                    "第${index + 1}槽选择${choice.displayName}（第${priority}顺位替代）"
                }
            }
        }
        if (missing.isNotEmpty()) {
            return LabyrinthOpeningRosterDecision.Missing(
                missingSlotNumbers = missing,
                reason = "${config.guildName}初始方案第${missing.joinToString("、")}槽没有识别到可用候选，禁止随机补位",
            )
        }
        check(selected.map(LabyrinthOpeningCharacter::characterId).distinct().size == selected.size) {
            "Opening roster selected the same character more than once"
        }
        return LabyrinthOpeningRosterDecision.Ready(selected, reasons)
    }
}

/** User-confirmed opening selections. Slash-separated names are priority alternatives in one slot. */
object LabyrinthOpeningRosterCatalog {
    private fun character(id: String, name: String) = LabyrinthOpeningCharacter(id, name)
    private fun slot(vararg candidates: LabyrinthOpeningCharacter) = LabyrinthOpeningRosterSlot(candidates.toList())

    val configs: Map<Int, LabyrinthOpeningRosterConfig> = listOf(
        LabyrinthOpeningRosterConfig(
            guildId = 1,
            guildName = "美食殿堂",
            slots = listOf(
                slot(character("1075", "贪吃佩可(夏日)")),
                slot(character("1351", "雪菲(夏日)")),
                slot(character("1059", "可可萝")),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 2,
            guildName = "破晓之星",
            slots = listOf(
                slot(character("1089", "怜(新年)")),
                slot(character("1088", "优衣(新年)")),
                slot(
                    character("1003", "怜"),
                    character("1801", "日和(公主)"),
                    character("1225", "怜(夏日)"),
                ),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 3,
            guildName = "咲恋救济院",
            slots = listOf(
                slot(character("1145", "咲恋(圣诞节)")),
                slot(character("1213", "胡桃(舞台)"), character("1085", "胡桃(圣诞节)")),
                slot(
                    character("1077", "铃莓(夏日)"),
                    character("1121", "铃莓(新年)"),
                    character("1308", "铃莓(春日)"),
                    character("1023", "绫音"),
                    character("1086", "绫音(圣诞节)"),
                    character("1103", "咲恋(夏日)"),
                ),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 4,
            guildName = "王宫骑士团（NIGHTMARE）",
            slots = listOf(
                slot(character("1242", "纯(圣诞节)")),
                slot(character("1339", "克莉丝提娜(始源)")),
                slot(
                    character("1136", "纯(夏日)"),
                    character("1115", "克莉丝提娜(圣诞节)"),
                    character("1236", "智(万圣节)"),
                    character("1238", "克莉丝提娜(狂野)"),
                ),
            ),
        ),
        LabyrinthOpeningRosterConfig(
            guildId = 5,
            guildName = "拉比林斯",
            slots = listOf(
                slot(character("1091", "静流(情人节)")),
                slot(character("1171", "静流(夏日)")),
                slot(character("1011", "璃乃")),
            ),
            grantedCharacters = listOf(character("1068", "菈比莉斯塔")),
        ),
    ).associateBy(LabyrinthOpeningRosterConfig::guildId)

    /** Characters [guildId] grants at the opening without a pick; empty for unknown guilds. */
    fun grantedCharactersFor(guildId: Int?): List<LabyrinthOpeningCharacter> =
        guildId?.let(configs::get)?.grantedCharacters.orEmpty()

    fun policyFor(guildId: Int?): LabyrinthOpeningRosterPolicy? = guildId
        ?.let(configs::get)
        ?.let(::LabyrinthOpeningRosterPolicy)
}
