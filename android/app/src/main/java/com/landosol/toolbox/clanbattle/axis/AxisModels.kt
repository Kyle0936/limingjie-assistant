package com.landosol.toolbox.clanbattle.axis

enum class AxisType { SEQUENCE, SWITCH }

enum class BattleSlot { SLOT_1, SLOT_2, SLOT_3, SLOT_4, SLOT_5 }

enum class AxisActionType {
    CLICK_ROLE,
    CLICK_AUTO,
    TOGGLE_AUTO,
    NOTIFY,
    BOSS,
    SET_ROLES,
}

data class AxisAction(
    val type: AxisActionType,
    val role: String? = null,
    val value: String? = null,
    val rawValue: String? = null,
    val message: String? = null,
    val values: List<String> = emptyList(),
)

data class AxisEvent(
    val id: String,
    val sourceLine: Int,
    val timeSeconds: Int,
    val actions: List<AxisAction>,
    val trigger: SwitchNodeTrigger = TimedTrigger,
)

enum class AxisToggleState { ON, OFF }

sealed interface SwitchNodeTrigger
data object TimedTrigger : SwitchNodeTrigger
data class CharacterUbTrigger(val role: BattleSlot?, val rawRole: String) : SwitchNodeTrigger
data class BossDelayTrigger(val minimumDelayMs: Long?, val rawDelay: String?) : SwitchNodeTrigger
data class PauseFrameTrigger(val role: BattleSlot?, val rawRole: String) : SwitchNodeTrigger
data class ConflictingSwitchTrigger(val ubAfter: String, val pauseFrame: String) : SwitchNodeTrigger

data class SwitchControlTarget(
    val auto: AxisToggleState?,
    val roles: Map<BattleSlot, AxisToggleState?>,
    val rawAuto: String?,
    val rawRoles: List<String>,
    val message: String? = null,
) {
    fun isComplete(): Boolean =
        rawAuto in setOf("开", "关") && rawRoles.size == 5 && rawRoles.all { it in setOf("开", "关") }
}

data class SwitchAxisOpening(val sourceLine: Int, val target: SwitchControlTarget)

data class SwitchAxisNode(
    val id: String,
    val sourceLine: Int,
    val timeSeconds: Int,
    val trigger: SwitchNodeTrigger,
    val target: SwitchControlTarget,
)

data class AxisDocument(
    val type: AxisType,
    val clickIntervalMs: Int,
    val header: Map<String, String>,
    val events: List<AxisEvent>,
    val hasAxisSection: Boolean = false,
    val switchOpenings: List<SwitchAxisOpening> = emptyList(),
    val switchNodes: List<SwitchAxisNode> = emptyList(),
    val hasSwitchSection: Boolean = false,
)

