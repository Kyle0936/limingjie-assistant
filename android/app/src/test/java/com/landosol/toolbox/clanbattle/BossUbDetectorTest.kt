package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.automation.PixelSize
import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind
import com.landosol.toolbox.clanbattle.recognition.EnergyObservation
import com.landosol.toolbox.clanbattle.recognition.FilterResult
import com.landosol.toolbox.clanbattle.recognition.ReadingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BossUbDetectorTest {
    @Test
    fun `normal clock cadence does not detect boss ub`() {
        val detector = BossUbDetector()

        assertNull(detector.update(60, emptySet(), 0))
        assertNull(detector.update(59, emptySet(), 1_000))
        assertNull(detector.update(58, emptySet(), 2_050))
        assertNull(detector.latestEvent(2_050))
    }

    @Test
    fun `learned median cadence adjusts completed hold threshold`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(59, emptySet(), 800)
        detector.update(58, emptySet(), 1_700)
        detector.update(57, emptySet(), 2_550)

        val event = detector.update(56, emptySet(), 4_100)

        assertEquals(57, event?.heldClockSeconds)
        assertEquals(1_550L, event?.holdDurationMillis)
    }

    @Test
    fun `completed abnormal hold detects boss ub`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(59, emptySet(), 1_000)
        detector.update(58, emptySet(), 2_000)

        assertNull(detector.update(58, emptySet(), 4_000))
        val event = detector.update(57, emptySet(), 8_000)

        assertEquals(
            SwitchBossUbEvent(58, 8_000, early = false, holdDurationMillis = 6_000),
            event,
        )
        assertEquals(event, detector.latestEvent(8_100))
    }

    @Test
    fun `long hold emits one early event before clock ticks`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        assertNull(detector.update(60, emptySet(), 4_000))
        assertNull(detector.update(60, emptySet(), 6_999))
        assertEquals(
            SwitchBossUbEvent(60, 7_000, early = true, holdDurationMillis = 7_000),
            detector.update(60, emptySet(), 7_000),
        )
        assertNull(detector.update(60, emptySet(), 7_100))
        assertEquals(
            SwitchBossUbEvent(60, 7_300, early = false, holdDurationMillis = 7_300),
            detector.update(59, emptySet(), 7_300),
        )
    }

    @Test
    fun `configured early threshold applies to current hold`() {
        val detector = BossUbDetector()
        detector.configureEarlyConfirmationHoldMillis(5_000)
        detector.update(60, emptySet(), 0)

        assertNull(detector.update(60, emptySet(), 4_999))
        assertEquals(
            SwitchBossUbEvent(60, 5_000, early = true, holdDurationMillis = 5_000),
            detector.update(60, emptySet(), 5_000),
        )
    }

    @Test
    fun `single character tp drop suppresses early and completed detection`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(60, setOf(BattleSlot.SLOT_4), 2_200)

        assertNull(detector.update(60, emptySet(), 7_000))
        assertNull(detector.update(59, emptySet(), 7_300))
        assertNull(detector.latestEvent(7_300))
    }

    @Test
    fun `simultaneous tp drops do not suppress boss detection`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(60, setOf(BattleSlot.SLOT_2, BattleSlot.SLOT_5), 2_000)

        val event = detector.update(59, emptySet(), 6_000)

        assertEquals(60, event?.heldClockSeconds)
    }

    @Test
    fun `capture gap starts a new observation anchor`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)

        assertNull(detector.update(59, emptySet(), 5_001))
        assertNull(detector.latestEvent(5_001))
    }

    @Test
    fun `suspend discards current hold but retains cadence`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 0)
        detector.update(59, emptySet(), 900)
        detector.update(59, emptySet(), 3_000)
        detector.suspend()

        assertNull(detector.update(59, emptySet(), 8_000))
        assertNull(detector.update(58, emptySet(), 8_900))
    }

    @Test
    fun `retained event expires and reset clears it`() {
        val detector = BossUbDetector(eventRetentionMillis = 30_000)
        detector.update(60, emptySet(), 0)
        val event = detector.update(59, emptySet(), 6_000)

        assertEquals(event, detector.latestEvent(36_000))
        assertNull(detector.latestEvent(36_001))
        detector.reset()
        assertNull(detector.latestEvent(6_000))
    }

    @Test
    fun `non monotonic timestamp cannot complete a hold`() {
        val detector = BossUbDetector()
        detector.update(60, emptySet(), 1_000)

        assertNull(detector.update(59, emptySet(), 900))
        assertNull(detector.latestEvent(900))
    }

    @Test
    fun `observation gate suspends hold outside trusted battle frames`() {
        val detector = BossUbDetector()
        detector.updateFromObservation(observation(60, 0))
        detector.updateFromObservation(observation(60, 6_000, screen = ClanBattleScreenKind.LOADING))

        assertNull(detector.updateFromObservation(observation(60, 7_000)))
        assertNull(detector.updateFromObservation(observation(59, 8_000)))
    }

    @Test
    fun `observation gate retains a completed event for scheduler frames`() {
        val detector = BossUbDetector()
        detector.updateFromObservation(observation(60, 0))

        val event = detector.updateFromObservation(observation(59, 6_000))

        assertEquals(event, detector.updateFromObservation(observation(59, 6_200)))
    }

    @Test
    fun `suspended observation does not replay retained diagnostic to scheduler`() {
        val detector = BossUbDetector()
        detector.updateFromObservation(observation(60, 0))
        val event = detector.updateFromObservation(observation(59, 6_000))
        assertEquals(event, detector.latestEvent(6_000))

        assertNull(detector.updateFromObservation(observation(59, 6_200, screen = ClanBattleScreenKind.LOADING)))
        assertNull(detector.updateFromObservation(observation(59, 6_400)))
        assertEquals(event, detector.latestEvent(6_400))
    }

    private fun observation(
        clockSeconds: Int,
        timestampMillis: Long,
        screen: ClanBattleScreenKind = ClanBattleScreenKind.BATTLE,
    ) = ClanBattleObservation(
        frameTimestampMillis = timestampMillis,
        frameSize = PixelSize(1920, 1080),
        viewport = null,
        screenKind = screen,
        screenConfidence = 0.95,
        clock = null,
        filteredClock = FilterResult(
            accepted = true,
            timeSeconds = clockSeconds,
            rawText = "1:00",
            source = ReadingSource.PRIMARY,
        ),
        energy = EnergyObservation(emptyMap(), averageDelta = null, confirmedDrops = emptySet()),
        controls = null,
        actionSafe = screen == ClanBattleScreenKind.BATTLE,
        shouldPause = false,
        diagnostics = emptyList(),
    )
}
