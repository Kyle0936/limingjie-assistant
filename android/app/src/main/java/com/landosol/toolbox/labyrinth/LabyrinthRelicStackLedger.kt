package com.landosol.toolbox.labyrinth

/** Unknown marks are absent, not zero. Cached evidence cannot manufacture consensus. */
internal class LabyrinthRelicStackLedger {
    enum class Source { MAP, CHOICE, DETAIL, ACQUISITION }
    private data class Pending(val value: Int, val source: Source, val count: Int)
    private val values = mutableMapOf<LabyrinthRelicMark, Int>()
    private val sources = mutableMapOf<LabyrinthRelicMark, Source>()
    private val pending = mutableMapOf<LabyrinthRelicMark, Pending>()
    private val evidence = mutableMapOf<String, Long>()
    private val conflicts = mutableSetOf<LabyrinthRelicMark>()
    val stacks: Map<LabyrinthRelicMark, Int> get() = values
    val needsAudit: Boolean get() = conflicts.isNotEmpty()

    fun clear() { values.clear(); sources.clear(); pending.clear(); evidence.clear(); conflicts.clear() }
    fun clearPending() { pending.clear() }

    fun seedAcquisitions(totals: Map<LabyrinthRelicMark, Int>) {
        totals.filterValues { it > 0 }.forEach { (mark, value) ->
            values[mark] = value.coerceAtMost(30)
            sources[mark] = Source.ACQUISITION
        }
    }

    fun observe(mark: LabyrinthRelicMark, value: Int?, source: Source, slot: String, evidenceId: Long?) {
        if (evidenceId == null) return
        val key = "$source:$slot"
        if (evidence[key]?.let { evidenceId <= it } == true) return
        evidence[key] = evidenceId
        if (value == null || value !in 0..30) { pending.remove(mark); return }
        val existing = values[mark]
        if (source == Source.MAP && sources[mark] != null && sources[mark] != Source.MAP) {
            // A tiny map digit cannot roll back a confirmed acquisition or overwrite card text.
            if (existing != value) conflicts += mark
            return
        }
        if (existing == value && (sources[mark]?.ordinal ?: -1) >= source.ordinal) {
            pending.remove(mark)
            if (source != Source.MAP) conflicts.remove(mark)
            return
        }
        val previous = pending[mark]
        val count = if (previous?.value == value && previous.source == source) previous.count + 1 else 1
        if (count < 2) { pending[mark] = Pending(value, source, count); return }
        values[mark] = value
        sources[mark] = source
        pending.remove(mark)
        conflicts.remove(mark)
    }

    /** Called once at an acknowledged reward transition, never on the click itself. */
    fun acquired(mark: LabyrinthRelicMark, bonus: Int, fallbackBefore: Int) {
        if (bonus <= 0) return
        values[mark] = ((values[mark] ?: fallbackBefore) + bonus).coerceIn(0, 30)
        sources[mark] = Source.ACQUISITION
        pending.remove(mark)
        // Keep seen IDs so a cached pre-acquisition card cannot restore the old total.
    }
}
