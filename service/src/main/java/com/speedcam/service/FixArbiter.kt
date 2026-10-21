package com.speedcam.service

import com.speedcam.core.detect.Fix
import kotlin.math.abs
import kotlin.math.max

/**
 * Chooses the better of two competing GPS fixes.
 *
 * Raw GPS produces wildly varying accuracy and occasional multi-second jumps. Without a
 * filter the detector either chases phantom positions or rejects genuinely better ones.
 *
 * Rule: a candidate replaces the incumbent when it is more accurate than the incumbent's
 * position uncertainty expanded by plausible vehicle movement:
 *
 *     cand.accuracy < cur.accuracy + max(5.0, (candSpeed + curSpeed) / 2) * elapsedSeconds
 *
 * The allowance is the point of the rule. Two fixes 10 s apart at 20 m/s describe positions
 * 200 m apart, so the incumbent's uncertainty about the vehicle's current position grows by
 * 200 m. A new fix whose accuracy is within that bound represents the real current location.
 */
object FixArbiter {
    /** Minimum speed floor in m/s used for uncertainty growth (5 m/s ≈ 18 km/h). */
    const val MIN_SPEED_MS = 5.0

    /** Minimum accuracy improvement required for out-of-order (older) fixes, in metres. */
    const val MIN_SLACK_M = 5.0

    /**
     * Returns whichever of [current] and [candidate] should become the new reference fix.
     *
     * A fix that is not usable never displaces a usable one: Android reports enormous
     * accuracy values while the provider is momentarily lost, and feeding those to the
     * detector produces positions hundreds of kilometres from the truth.
     */
    fun better(
        current: Fix?,
        candidate: Fix,
    ): Fix =
        when {
            current == null -> candidate
            !candidate.isUsable -> current
            !current.isUsable -> candidate
            else -> pickByAccuracy(current, candidate)
        }

    private fun pickByAccuracy(
        current: Fix,
        candidate: Fix,
    ): Fix =
        when {
            // A fused fix has already arbitrated between providers upstream, so when only one of
            // the two is fused, that one is authoritative regardless of the accuracy comparison.
            candidate.isFused && !current.isFused -> candidate
            current.isFused && !candidate.isFused -> current
            candidate.isFused && current.isFused && candidate.timestampMillis > current.timestampMillis -> candidate
            isCandidateBetter(current, candidate) -> candidate
            else -> current
        }

    private fun isCandidateBetter(
        current: Fix,
        candidate: Fix,
    ): Boolean {
        // If candidate is newer in time, incumbent's position uncertainty increases by travel distance.
        // Ported directly from ContraCam d.java:34:
        // cand.accuracy < cur.accuracy + max(5.0, (candSpeed + curSpeed) / 2) * elapsedSeconds
        if (candidate.timestampMillis >= current.timestampMillis) {
            val slack = slackMetres(current, candidate)
            return candidate.accuracyM.toDouble() < current.accuracyM.toDouble() + slack
        }
        // If candidate is older (out of order), it must be strictly more accurate than the incumbent.
        return candidate.accuracyM.toDouble() + MIN_SLACK_M < current.accuracyM.toDouble()
    }

    private fun slackMetres(
        a: Fix,
        b: Fix,
    ): Double {
        val elapsedSeconds = abs(b.timestampMillis - a.timestampMillis) / 1000.0
        val meanSpeedMs = (a.speedMs.toDouble() + b.speedMs.toDouble()) / 2.0
        return max(MIN_SPEED_MS, meanSpeedMs) * elapsedSeconds
    }
}
