package com.landosol.toolbox.clanbattle

import com.landosol.toolbox.clanbattle.axis.BattleSlot
import com.landosol.toolbox.clanbattle.recognition.ClanBattleObservation
import com.landosol.toolbox.clanbattle.recognition.ClanBattleScreenKind
import java.util.ArrayDeque

/**
 * Detects Boss UB clock holds without owning capture or action infrastructure.
 * A single confirmed TP drop attributes the hold to a character UB. Multiple
 * simultaneous drops remain eligible because a Boss animation can obscure HUD slots.
 */
class BossUbDetector(
    private val holdMarginMillis: Long = 700L,
    private val fallbackSecondMillis: Long = 1_000L,
    private val eventRetentionMillis: Long = 30_000L,
    private val maxObservationGapMillis: Long = 5_000L,
    private var earlyConfirmationHoldMillis: Long = 7_000L,
) {
    private val normalSecondDurations = ArrayDeque<Long>()
    private var clockSeconds: Int? = null
    private var clockStartedAtWallMillis: Long? = null
    private var lastObservedAtWallMillis: Long? = null
    private var characterUbObserved = false
    private var earlyEventEmittedForHold = false
    private var latestEvent: SwitchBossUbEvent? = null
    private var schedulerEvent: SwitchBossUbEvent? = null

    init {
        require(holdMarginMillis > 0)
        require(fallbackSecondMillis > 0)
        require(eventRetentionMillis > 0)
        require(maxObservationGapMillis > 0)
        require(earlyConfirmationHoldMillis > 0)
    }

    fun update(
        clockSeconds: Int,
        triggeredRoles: Set<BattleSlot>,
        nowMillis: Long,
    ): SwitchBossUbEvent? {
        val previousClock = this.clockSeconds
        val startedAt = clockStartedAtWallMillis
        if (previousClock == null || startedAt == null) {
            anchor(clockSeconds, nowMillis, triggeredRoles)
            return null
        }
        val lastObservedAt = lastObservedAtWallMillis
        if (
            lastObservedAt == null ||
            nowMillis < lastObservedAt ||
            nowMillis - lastObservedAt > maxObservationGapMillis
        ) {
            anchor(clockSeconds, nowMillis, triggeredRoles)
            return null
        }
        lastObservedAtWallMillis = nowMillis

        val durationMillis = (nowMillis - startedAt).coerceAtLeast(0L)
        if (triggeredRoles.size == 1) {
            characterUbObserved = true
            schedulerEvent = null
        }
        if (clockSeconds == previousClock) {
            if (
                !earlyEventEmittedForHold &&
                !characterUbObserved &&
                durationMillis >= earlyConfirmationHoldMillis
            ) {
                earlyEventEmittedForHold = true
                return event(previousClock, nowMillis, durationMillis, early = true)
            }
            return null
        }

        val sequentialTick = previousClock - clockSeconds == 1
        val detected = if (
            sequentialTick &&
            durationMillis >= normalSecondMillis() + holdMarginMillis &&
            !characterUbObserved
        ) {
            event(previousClock, nowMillis, durationMillis, early = false)
        } else {
            null
        }

        if (sequentialTick && durationMillis in MIN_NORMAL_SECOND_MILLIS..MAX_NORMAL_SECOND_MILLIS) {
            normalSecondDurations.addLast(durationMillis)
            while (normalSecondDurations.size > MAX_NORMAL_SAMPLES) {
                normalSecondDurations.removeFirst()
            }
        }
        anchor(clockSeconds, nowMillis, triggeredRoles)
        return detected
    }

    /**
     * Applies the recognition safety gate and returns the retained event that is
     * currently eligible for a Dry Run scheduler.
     */
    fun updateFromObservation(
        observation: ClanBattleObservation,
        wallTimeMillis: Long = observation.frameTimestampMillis,
    ): SwitchBossUbEvent? {
        val filteredClock = observation.filteredClock
        val clockUsable = filteredClock?.let { it.accepted || it.reason == "same-time" } == true
        val clockSeconds = filteredClock?.timeSeconds
        val energy = observation.energy
        if (
            observation.screenKind != ClanBattleScreenKind.BATTLE ||
            observation.shouldPause ||
            !clockUsable ||
            clockSeconds == null ||
            energy == null
        ) {
            suspend()
            return null
        }
        update(clockSeconds, energy.confirmedDrops, wallTimeMillis)
        return schedulerEvent?.takeIf {
            wallTimeMillis - it.detectedAtWallMillis in 0L..eventRetentionMillis
        }
    }

    fun latestEvent(nowMillis: Long): SwitchBossUbEvent? = latestEvent?.takeIf {
        nowMillis - it.detectedAtWallMillis in 0L..eventRetentionMillis
    }

    fun configureEarlyConfirmationHoldMillis(value: Long) {
        require(value > 0)
        earlyConfirmationHoldMillis = value
    }

    /** Drops an in-progress hold while retaining learned normal cadence and recent diagnostics. */
    fun suspend() {
        clockSeconds = null
        clockStartedAtWallMillis = null
        lastObservedAtWallMillis = null
        characterUbObserved = false
        earlyEventEmittedForHold = false
        schedulerEvent = null
    }

    fun reset() {
        suspend()
        normalSecondDurations.clear()
        latestEvent = null
    }

    private fun event(
        heldClockSeconds: Int,
        detectedAtWallMillis: Long,
        holdDurationMillis: Long,
        early: Boolean,
    ) = SwitchBossUbEvent(
        heldClockSeconds = heldClockSeconds,
        detectedAtWallMillis = detectedAtWallMillis,
        early = early,
        holdDurationMillis = holdDurationMillis,
    ).also {
        latestEvent = it
        schedulerEvent = it
    }

    private fun anchor(
        clockSeconds: Int,
        nowMillis: Long,
        triggeredRoles: Set<BattleSlot>,
    ) {
        this.clockSeconds = clockSeconds
        clockStartedAtWallMillis = nowMillis
        lastObservedAtWallMillis = nowMillis
        characterUbObserved = triggeredRoles.size == 1
        earlyEventEmittedForHold = false
    }

    private fun normalSecondMillis(): Long {
        if (normalSecondDurations.isEmpty()) return fallbackSecondMillis
        val sorted = normalSecondDurations.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val MAX_NORMAL_SAMPLES = 15
        const val MIN_NORMAL_SECOND_MILLIS = 400L
        const val MAX_NORMAL_SECOND_MILLIS = 1_600L
    }
}
