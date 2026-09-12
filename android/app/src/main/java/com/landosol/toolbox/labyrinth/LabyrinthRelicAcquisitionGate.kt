package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** Clicking or briefly failing to recognize a reward is not proof of acquisition. */
internal class LabyrinthRelicAcquisitionGate {
    private var mapFrames = 0
    fun reset() { mapFrames = 0 }
    fun observe(clicked: Boolean, page: LabyrinthEntryPageState): Boolean {
        mapFrames = if (clicked && page == LabyrinthEntryPageState.NODE_SELECTION) mapFrames + 1 else 0
        return mapFrames >= 2
    }
}
