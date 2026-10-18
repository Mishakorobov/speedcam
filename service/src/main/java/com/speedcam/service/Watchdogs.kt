package com.speedcam.service

import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon

/** What the GPS watchdog currently believes. */
enum class GpsStatus {
    /** Fixes are arriving within the timeout. */
    HEALTHY,

    /** No usable fix for longer than the timeout. The driver needs to know. */
    LOST,

    /**
     * A fix arrived after a loss.
     *
     * Reported exactly once per loss, so that the service can tell the driver it recovered
     * rather than silently reverting the notification.
     */
    RECOVERED,
}

/**
 * Announces when the GPS has stopped producing fixes.
 *
 * Plan.md §8.1: no fix for 30 seconds means the driver is being told about cameras while
 * blind to where they are, which is worse than silence. The announcement matters because a
 * silent app and a working one look identical until the moment they are needed.
 *
 * Pure: the caller supplies the clock, so the whole 30-second boundary is testable without
 * waiting for it.
 */
class GpsLostWatchdog(
    /** Silence longer than this is a lost signal. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private var lastFixMs: Long? = null
    private var wasLost = false

    /**
     * Begins the countdown. Called once when the service starts so that a provider which never
     * delivers anything at all is still reported after [timeoutMs], rather than counting from
     * some epoch value.
     */
    fun start(nowMs: Long) {
        if (lastFixMs == null) lastFixMs = nowMs
    }

    /** Records that a fix arrived at [fixMillis]. */
    fun onFix(fixMillis: Long) {
        lastFixMs = fixMillis
    }

    /**
     * Evaluates the signal loss at [nowMs].
     *
     * [GpsStatus.LOST] and [GpsStatus.RECOVERED] are edges, not levels: they are returned on
     * the transition only, so a caller that updates a notification once per tick does not
     * re-announce continuously, and one that waits for RECOVERED is guaranteed to see it.
     */
    fun evaluate(nowMs: Long): GpsStatus {
        val last = lastFixMs ?: return GpsStatus.HEALTHY
        val lost = nowMs - last > timeoutMs

        return when {
            lost && !wasLost -> {
                wasLost = true
                GpsStatus.LOST
            }

            !lost && wasLost -> {
                wasLost = false
                GpsStatus.RECOVERED
            }

            lost -> GpsStatus.LOST

            else -> GpsStatus.HEALTHY
        }
    }

    companion object {
        /** Plan.md §8.1: "no fix for 30 s". */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * Stops the service when it looks like it is being used as a covert tracker.
 *
 * Plan.md §8.1, ported from the original: "backgrounded and moved > 100 m within 15 min →
 * stop service". The reading taken here is that an app with no visible UI, whose device is
 * nonetheless being carried down a road, is not being used as a driving aid — and that
 * combination is both a battery problem and a privacy problem. The service shuts itself down
 * rather than continuing to report the route.
 *
 * Pure, so both halves of the condition and the exact boundary are testable.
 */
class BackgroundAbuseWatchdog(
    /** Movement inside this window is what triggers the check. */
    val windowMs: Long = DEFAULT_WINDOW_MS,
    /** How far the device must have moved. */
    val thresholdM: Double = DEFAULT_THRESHOLD_M,
) {
    private var backgroundedAtMs: Long? = null
    private var backgroundedAt: LatLon? = null

    /** The app went to the background at [nowMs], from [position] if known. */
    fun onBackgrounded(
        nowMs: Long,
        position: LatLon?,
    ) {
        backgroundedAtMs = nowMs
        backgroundedAt = position
    }

    /** The app came back to the foreground; the suspicion, if any, is cleared. */
    fun onForegrounded() {
        backgroundedAtMs = null
        backgroundedAt = null
    }

    /**
     * Records a movement to [position] at [nowMs] and reports whether the service should stop.
     *
     * Returns false — the watchdog disarms itself — once the window has expired, because a
     * journey that started long ago is no longer the thing the rule was written for.
     */
    fun onMoved(
        nowMs: Long,
        position: LatLon,
    ): Boolean {
        val since = backgroundedAtMs
        val origin = backgroundedAt
        if (since == null || origin == null) return false

        if (nowMs - since > windowMs) {
            onForegrounded()
            return false
        }

        return Geo.haversineMeters(origin, position) > thresholdM
    }

    /** True while the app is considered backgrounded and the window is still open. */
    val isArmed: Boolean get() = backgroundedAtMs != null && backgroundedAt != null

    companion object {
        /** Plan.md §8.1: 15 minutes. */
        const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L

        /** Plan.md §8.1: 100 metres. */
        const val DEFAULT_THRESHOLD_M = 100.0
    }
}
