package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthMemberGeometryTest {
    private val matcher = LabyrinthCharacterIconMatcher()
    private fun match(vararg candidates: Pair<String, Double>, variant: String = "31", trusted: Boolean = false): LabyrinthCharacterIconMatch {
        val best = candidates.first()
        val rival = candidates.getOrNull(1)?.second ?: 0.0
        return LabyrinthCharacterIconMatch(
            characterId = if (trusted) best.first else null, displayName = if (trusted) best.first else null,
            iconVariant = if (trusted) variant else null, confidence = best.second, rivalConfidence = rival,
            rivalMargin = best.second - rival, trusted = trusted,
            suspectedCharacterId = best.first, suspectedDisplayName = best.first,
            candidates = candidates.map { (id, score) -> LabyrinthCharacterIconCandidate(id, id, variant, score) },
        )
    }

    @Test fun `a different role from another crop still blocks an ambiguous recovery`() {
        val initial = match("wrong" to 0.31, "target" to 0.30)
        val result = matcher.reconcileGeometryMatches(initial, listOf(
            match("target" to 0.40, "wrong" to 0.25, trusted = true),
            match("wrong" to 0.38, "target" to 0.26, trusted = true),
        ))
        assertSame(initial, result)
        assertFalse(result.trusted)
    }

    @Test fun `calibration keeps floors and treats star variants as one identity`() {
        val initial = match("wrong" to 0.30)
        assertSame(initial, matcher.reconcileGeometryMatches(initial, listOf(match("target" to 0.34))))
        val result = matcher.reconcileGeometryMatches(initial, listOf(
            match("target" to 0.40, variant = "11"), match("target" to 0.39, variant = "31"),
        ))
        assertTrue(result.trusted)
        assertEquals("target", result.characterId)
        assertEquals(0.10, result.rivalMargin, 0.0001)
        val known = match("known" to 0.7, trusted = true)
        assertSame(known, matcher.reconcileGeometryMatches(known, listOf(match("wrong" to 0.9))))
    }
}
