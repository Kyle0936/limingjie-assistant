package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.*
import org.junit.Test

class LabyrinthRelicAcquisitionGateTest {
    private val gate = LabyrinthRelicAcquisitionGate()
    @Test fun `click and unknown loading frames never commit a reward`() {
        repeat(10) { assertFalse(gate.observe(true, LabyrinthEntryPageState.RELIC_CHOICE)) }
        repeat(10) { assertFalse(gate.observe(true, LabyrinthEntryPageState.UNKNOWN)) }
    }
    @Test fun `requires two consecutive map frames after click`() {
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION))
        assertFalse(gate.observe(true, LabyrinthEntryPageState.UNKNOWN))
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION))
        assertTrue(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION))
    }
    @Test fun `cleared commitment or new run cannot add reward again`() {
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION))
        assertTrue(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION))
        repeat(10) { assertFalse(gate.observe(false, LabyrinthEntryPageState.NODE_SELECTION)) }
        gate.reset()
        assertFalse(gate.observe(true, LabyrinthEntryPageState.NODE_SELECTION))
    }
}
