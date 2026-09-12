package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.LabyrinthRelicStackLedger.Source
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicStackLedgerTest {
    private val ledger = LabyrinthRelicStackLedger()
    private val mark = LabyrinthRelicMark.DEFENSE
    private fun observe(value: Int?, source: Source, id: Long) = ledger.observe(mark, value, source, "slot", id)
    private fun confirm(value: Int, source: Source, firstId: Long = 1) {
        observe(value, source, firstId); observe(value, source, firstId + 1)
    }
    @Test fun `repeated cache evidence is only one vote`() {
        repeat(20) { observe(9, Source.CHOICE, 1) }
        assertFalse(ledger.stacks.containsKey(mark))
        observe(9, Source.CHOICE, 2)
        assertEquals(9, ledger.stacks[mark])
    }
    @Test fun `tiny map counters cannot overwrite card or acquisition totals`() {
        confirm(9, Source.CHOICE)
        ledger.acquired(mark, 1, 0)
        confirm(1, Source.MAP)
        assertEquals(10, ledger.stacks[mark])
        assertTrue(ledger.needsAudit)
    }
    @Test fun `visible details can correct a wrong total without clearing unseen marks`() {
        ledger.acquired(mark, 9, 0)
        ledger.acquired(LabyrinthRelicMark.CRITICAL, 3, 0)
        confirm(4, Source.DETAIL)
        assertEquals(4, ledger.stacks[mark])
        assertEquals(3, ledger.stacks[LabyrinthRelicMark.CRITICAL])
        assertFalse(ledger.stacks.containsKey(LabyrinthRelicMark.ACCELERATION))
    }
    @Test fun `cached pre-acquisition readings do not roll back a reward`() {
        confirm(9, Source.CHOICE)
        ledger.acquired(mark, 1, 0)
        repeat(10) { observe(9, Source.CHOICE, 2) }
        assertEquals(10, ledger.stacks[mark])
    }
    @Test fun `uncertain or contradictory readings cannot form consensus`() {
        observe(9, Source.CHOICE, 1)
        observe(null, Source.CHOICE, 2)
        observe(9, Source.CHOICE, 3)
        observe(1, Source.CHOICE, 4)
        assertTrue(ledger.stacks.isEmpty())
    }
    @Test fun `new run clears evidence totals and conflicts`() {
        confirm(4, Source.DETAIL)
        confirm(1, Source.MAP)
        ledger.clear()
        assertTrue(ledger.stacks.isEmpty())
        assertFalse(ledger.needsAudit)
        confirm(1, Source.CHOICE)
        assertEquals(1, ledger.stacks[mark])
    }

    @Test fun `restored acquisitions cannot be replaced by tiny map digits`() {
        ledger.seedAcquisitions(mapOf(mark to 9, LabyrinthRelicMark.CRITICAL to 0))
        confirm(1, Source.MAP)
        assertEquals(9, ledger.stacks[mark])
        assertFalse(ledger.stacks.containsKey(LabyrinthRelicMark.CRITICAL))
        assertTrue(ledger.needsAudit)
    }

    @Test fun `out of order evidence cannot manufacture new confirmations`() {
        confirm(9, Source.CHOICE, 10)
        ledger.acquired(mark, 1, 0)
        observe(1, Source.CHOICE, 9)
        observe(1, Source.CHOICE, 10)
        assertEquals(10, ledger.stacks[mark])
    }
}
