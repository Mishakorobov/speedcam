package com.speedcam.core.detect

import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import kotlin.math.max

/**
 * Read access to active average-speed corridors (SPECS).
 *
 * Declared here in the pure domain module so [AverageSpeedTracker] stays free of Room and
 * Android dependencies.
 */
fun interface SectionQuery {
    fun activeSections(): List<SectionRecord>
}

/** Why an in-progress average-speed measurement was discarded without announcing an exit. */
enum class SectionAbortReason {
    /** Traversal took longer than any plausible drive (e.g. the driver parked mid-zone). */
    TIMEOUT,

    /** The vehicle wandered far outside the corridor before reaching the exit camera. */
    OFF_CORRIDOR,

    /** GPS signal was lost mid-corridor, making the elapsed time or distance untrustworthy. */
    GPS_LOST,

    /** Fix timestamps moved backwards. */
    TIME_REVERSED,
}

/** State transition or live update produced by [AverageSpeedTracker]. */
sealed interface SectionEvent {
    val section: SectionRecord

    /** Fired once when the vehicle passes the entry camera heading along the corridor. */
    data class Entered(
        override val section: SectionRecord,
        val entryTimeMillis: Long,
        val entryOdometerM: Double,
    ) : SectionEvent

    /** Fired on intermediate fixes while the vehicle is inside the corridor. */
    data class Progress(
        override val section: SectionRecord,
        val elapsedMillis: Long,
        val traveledM: Double,
        val remainingM: Double,
        val averageKmh: Double,
        val excessKmh: Double,
    ) : SectionEvent

    /**
     * Fired once when the vehicle reaches the exit camera.
     *
     * [averageKmh] is computed from `v_avg = (s1 - s0) / (t1 - t0)` per Plan.md §8.1.
     */
    data class Exited(
        override val section: SectionRecord,
        val durationMillis: Long,
        val distanceM: Double,
        val averageKmh: Double,
        val excessKmh: Double,
    ) : SectionEvent

    /** Fired when an active corridor is abandoned without a valid exit measurement. */
    data class Aborted(
        override val section: SectionRecord,
        val reason: SectionAbortReason,
    ) : SectionEvent
}

/**
 * Tracks entry, live average speed and exit across average-speed corridors (SPECS).
 *
 * Implements Plan.md §8.1:
 *  - On corridor entry within [gateRadiusM] of [SectionRecord.from] and aligned with the
 *    corridor's bearing within [headingToleranceDeg], records `(t0, s0)`.
 *  - Integrates distance `s` along the driven path from usable GPS fixes.
 *  - On corridor exit within [gateRadiusM] of [SectionRecord.to], computes
 *    `v_avg = (s1 - s0) / (t1 - t0)` and emits [SectionEvent.Exited].
 *  - Aborts cleanly on timeout, off-route deviation, or GPS loss so a broken measurement is
 *    never announced as a false average.
 */
class AverageSpeedTracker(
    private val query: SectionQuery,
    private val gateRadiusM: Double = DEFAULT_GATE_RADIUS_M,
    private val headingToleranceDeg: Double = DEFAULT_HEADING_TOLERANCE_DEG,
) {
    private data class ActiveSession(
        val section: SectionRecord,
        val t0: Long,
        val s0: Double,
    )

    private var activeSession: ActiveSession? = null
    private var odometerM: Double = 0.0
    private var lastFixPosition: LatLon? = null

    /** True while the vehicle is inside a measured corridor. */
    val isInCorridor: Boolean get() = activeSession != null

    /** The corridor currently being traversed, or null when outside any zone. */
    val currentSection: SectionRecord? get() = activeSession?.section

    /** Cumulative distance integrated across all usable fixes fed to this tracker, in metres. */
    val currentOdometerM: Double get() = odometerM

    /**
     * Processes a single GPS fix and returns the resulting corridor event, if any.
     *
     * Unusable fixes (e.g. accuracy > 200 m) are ignored so a momentary provider glitch
     * neither inflates the odometer nor triggers a bogus off-corridor abort.
     */
    fun onFix(fix: Fix): SectionEvent? {
        if (!fix.isUsable) return null

        lastFixPosition?.let { prev ->
            odometerM += Geo.haversineMeters(prev, fix.position)
        }
        lastFixPosition = fix.position

        val current = activeSession
        return if (current != null) {
            advanceActive(current, fix)
        } else {
            tryEnter(fix)
        }
    }

    /**
     * Aborts the active corridor when the GPS watchdog declares signal loss.
     *
     * A long tunnel or signal dropout leaves a gap in the driven path; silently continuing
     * would under-report the distance or misreport the average.
     */
    fun onGpsLost(): SectionEvent.Aborted? {
        val current = activeSession ?: return null
        activeSession = null
        return SectionEvent.Aborted(current.section, SectionAbortReason.GPS_LOST)
    }

    /** Clears all state. */
    fun reset() {
        activeSession = null
        odometerM = 0.0
        lastFixPosition = null
    }

    private fun advanceActive(
        session: ActiveSession,
        fix: Fix,
    ): SectionEvent? {
        val section = session.section
        val elapsedMs = fix.timestampMillis - session.t0
        val distFromStart = Geo.haversineMeters(section.from, fix.position)
        val distToExit = Geo.haversineMeters(fix.position, section.to)
        val maxReach = section.lengthM + OFF_CORRIDOR_MARGIN_M
        val abortReason =
            when {
                elapsedMs < 0L -> SectionAbortReason.TIME_REVERSED
                elapsedMs > maxDurationMillis(section) -> SectionAbortReason.TIMEOUT
                distFromStart > maxReach && distToExit > maxReach -> SectionAbortReason.OFF_CORRIDOR
                else -> null
            }

        return when {
            abortReason != null -> {
                activeSession = null
                SectionEvent.Aborted(section, abortReason)
            }

            elapsedMs == 0L -> null

            distToExit <= gateRadiusM -> {
                activeSession = null
                val traveledM = odometerM - session.s0
                val avgKmh = (traveledM / (elapsedMs / 1_000.0)) * MS_TO_KMH
                SectionEvent.Exited(
                    section = section,
                    durationMillis = elapsedMs,
                    distanceM = traveledM,
                    averageKmh = avgKmh,
                    excessKmh = avgKmh - section.limitKmh,
                )
            }

            else -> {
                val traveledM = odometerM - session.s0
                val avgKmh = (traveledM / (elapsedMs / 1_000.0)) * MS_TO_KMH
                SectionEvent.Progress(
                    section = section,
                    elapsedMillis = elapsedMs,
                    traveledM = traveledM,
                    remainingM = distToExit,
                    averageKmh = avgKmh,
                    excessKmh = avgKmh - section.limitKmh,
                )
            }
        }
    }

    private fun tryEnter(fix: Fix): SectionEvent.Entered? {
        val candidate =
            query
                .activeSections()
                .asSequence()
                .filter { it.active && it.lengthM >= MIN_CORRIDOR_LENGTH_M }
                .filter { Geo.haversineMeters(fix.position, it.from) <= gateRadiusM }
                .filter { section ->
                    val corridorBearing = Geo.bearingDeg(section.from, section.to)
                    Geo.angleDiff(fix.headingDeg, corridorBearing) <= headingToleranceDeg
                }.minByOrNull { Geo.haversineMeters(fix.position, it.from) }
                ?: return null

        activeSession = ActiveSession(section = candidate, t0 = fix.timestampMillis, s0 = odometerM)
        return SectionEvent.Entered(
            section = candidate,
            entryTimeMillis = fix.timestampMillis,
            entryOdometerM = odometerM,
        )
    }

    private fun maxDurationMillis(section: SectionRecord): Long {
        val slowTraversalMs = (section.lengthM / MIN_PLAUSIBLE_SPEED_MS * 1_000.0).toLong()
        return max(MIN_TIMEOUT_MS, slowTraversalMs)
    }

    companion object {
        private const val MS_TO_KMH = 3.6

        /** Entry and exit capture radius around the corridor endpoints, in metres. */
        const val DEFAULT_GATE_RADIUS_M = 50.0

        /** Maximum heading mismatch between the vehicle and the corridor's `from -> to` bearing. */
        const val DEFAULT_HEADING_TOLERANCE_DEG = 60.0

        /** Corridors shorter than this are data errors rather than real SPECS zones. */
        const val MIN_CORRIDOR_LENGTH_M = 50.0

        /** Extra slack beyond [SectionRecord.lengthM] before declaring the vehicle off-route. */
        const val OFF_CORRIDOR_MARGIN_M = 500.0

        /** Floor on the corridor timeout (10 minutes). */
        const val MIN_TIMEOUT_MS = 10 * 60 * 1_000L

        /** Minimum plausible average speed (~7.2 km/h) used to scale the timeout with length. */
        private const val MIN_PLAUSIBLE_SPEED_MS = 2.0
    }
}
