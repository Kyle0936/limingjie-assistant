package com.landosol.toolbox.clanbattle.axis

object AxisParser {
    fun parse(text: String): AxisDocument {
        require(text.length <= MAX_AXIS_BYTES) { "轴文件过大" }
        val header = linkedMapOf<String, String>()
        val events = mutableListOf<AxisEvent>()
        val switchOpenings = mutableListOf<SwitchAxisOpening>()
        val switchNodes = mutableListOf<SwitchAxisNode>()
        var inAxis = false
        var inSwitchAxis = false

        text.replace("\r\n", "\n").lines().forEachIndexed { index, source ->
            val lineNumber = index + 1
            val line = source.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line == "[轴]") {
                require(!inSwitchAxis) { "第${lineNumber}行不能在开关轴段之后开始顺序轴" }
                inAxis = true
                return@forEachIndexed
            }
            if (line.startsWith("[轴开局]")) {
                val parts = line.split('|').map(String::trim)
                require(parts.first() == "[轴开局]") { "第${lineNumber}行轴开局格式错误" }
                switchOpenings += SwitchAxisOpening(lineNumber, parseSwitchTarget(parseFields(parts.drop(1), lineNumber)))
                inSwitchAxis = true
                inAxis = false
                return@forEachIndexed
            }
            if (!inAxis && !inSwitchAxis) {
                val (key, value) = parseKeyValue(line, lineNumber)
                require(header.put(key, value) == null) { "第${lineNumber}行表头字段重复：$key" }
                return@forEachIndexed
            }
            if (inSwitchAxis) {
                val parts = line.split('|').map(String::trim)
                require(parts.isNotEmpty()) { "第${lineNumber}行开关轴为空" }
                val fields = parseFields(parts.drop(1), lineNumber)
                switchNodes += SwitchAxisNode(
                    id = "switch-line-$lineNumber",
                    sourceLine = lineNumber,
                    timeSeconds = parseTime(parts.first()),
                    trigger = parseNodeTrigger(fields),
                    target = parseSwitchTarget(fields),
                )
                return@forEachIndexed
            }
            val parts = line.split('|').map(String::trim)
            val sequenceFields = parts.drop(1).map { parseKeyValue(it, lineNumber) }
            val triggerFields = linkedMapOf<String, String>()
            sequenceFields.filter { (key, _) -> key in triggerKeys }.forEach { (key, value) ->
                require(triggerFields.put(key, value) == null) { "第${lineNumber}行字段重复：$key" }
            }
            val actions = sequenceFields
                .filterNot { (key, _) -> key in triggerKeys }
                .flatMap { (key, value) -> parseAction("$key=$value", lineNumber) }
            events += AxisEvent(
                id = "line-$lineNumber",
                sourceLine = lineNumber,
                timeSeconds = parseTime(parts.first()),
                actions = actions,
                trigger = parseNodeTrigger(triggerFields),
            )
        }
        return AxisDocument(
            type = if (header["轴类型"] == "开关" || switchOpenings.isNotEmpty()) AxisType.SWITCH else AxisType.SEQUENCE,
            clickIntervalMs = header["点击间隔"]?.toIntOrNull() ?: DEFAULT_CLICK_INTERVAL_MS,
            header = header,
            events = events,
            hasAxisSection = inAxis || switchOpenings.isNotEmpty(),
            switchOpenings = switchOpenings,
            switchNodes = switchNodes,
            hasSwitchSection = switchOpenings.isNotEmpty(),
        )
    }

    fun parseTime(raw: String): Int {
        val match = Regex("^(\\d):([0-5]\\d)$").matchEntire(raw.trim())
            ?: error("时间格式必须为 M:SS：$raw")
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    private fun parseAction(field: String, lineNumber: Int): List<AxisAction> {
        val (key, value) = parseKeyValue(field, lineNumber)
        return when (key) {
            "点击" -> value.split(',').map { name ->
                when (val trimmed = name.trim()) {
                    "AUTO" -> AxisAction(AxisActionType.CLICK_AUTO)
                    "BOSS" -> AxisAction(AxisActionType.BOSS)
                    else -> AxisAction(AxisActionType.CLICK_ROLE, role = trimmed)
                }
            }
            "提示" -> listOf(AxisAction(AxisActionType.NOTIFY, message = value))
            "AUTO" -> listOf(AxisAction(AxisActionType.TOGGLE_AUTO, value = when (value) {
                "开" -> "on"
                "关" -> "off"
                else -> null
            }, rawValue = value))
            "SET" -> listOf(AxisAction(AxisActionType.SET_ROLES, values = value.split(',').map(String::trim)))
            else -> error("第${lineNumber}行未知字段：$key")
        }
    }

    private fun parseKeyValue(line: String, lineNumber: Int): Pair<String, String> {
        val index = line.indexOf('=')
        require(index >= 0) { "第${lineNumber}行缺少等号：$line" }
        return line.substring(0, index).trim() to line.substring(index + 1).trim()
    }

    private fun parseFields(parts: List<String>, lineNumber: Int): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        parts.filter(String::isNotBlank).forEach { part ->
            val (key, value) = parseKeyValue(part, lineNumber)
            require(fields.put(key, value) == null) { "第${lineNumber}行字段重复：$key" }
        }
        return fields
    }

    private fun parseSwitchTarget(fields: Map<String, String>): SwitchControlTarget {
        val rawRoles = fields["SET"]?.split(',')?.map(String::trim).orEmpty()
        val roles = BattleSlot.entries.zip(rawRoles.map(::parseToggleState)).toMap()
        val rawAuto = fields["AUTO"]
        return SwitchControlTarget(
            auto = rawAuto?.let(::parseToggleState),
            roles = roles,
            rawAuto = rawAuto,
            rawRoles = rawRoles,
            message = fields["提示"],
        )
    }

    private fun parseNodeTrigger(fields: Map<String, String>): SwitchNodeTrigger {
        val ubAfter = fields["UB后"]
        val pauseFrame = fields["卡帧"]
        if (ubAfter != null && pauseFrame != null) return ConflictingSwitchTrigger(ubAfter, pauseFrame)
        if (pauseFrame != null) return PauseFrameTrigger(parseRole(pauseFrame), pauseFrame)
        if (ubAfter == null) return TimedTrigger
        if (ubAfter.equals("BOSS", ignoreCase = true)) {
            val rawDelay = fields["延迟"]
            return BossDelayTrigger(rawDelay?.toDoubleOrNull()?.let { (it * 1_000).toLong() }, rawDelay)
        }
        return CharacterUbTrigger(parseRole(ubAfter), ubAfter)
    }

    private fun parseToggleState(raw: String): AxisToggleState? = when (raw) {
        "开" -> AxisToggleState.ON
        "关" -> AxisToggleState.OFF
        else -> null
    }

    private fun parseRole(raw: String): BattleSlot? = when (raw) {
        "角色1" -> BattleSlot.SLOT_1
        "角色2" -> BattleSlot.SLOT_2
        "角色3" -> BattleSlot.SLOT_3
        "角色4" -> BattleSlot.SLOT_4
        "角色5" -> BattleSlot.SLOT_5
        else -> null
    }

    private val triggerKeys = setOf("UB后", "延迟", "卡帧")

    private const val MAX_AXIS_BYTES = 1_000_000
    private const val DEFAULT_CLICK_INTERVAL_MS = 100
}
