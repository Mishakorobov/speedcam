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
 * Rule: a candidate replaces the incumbent only when it is more accurate **by more than** the
 * distance the vehicle could plausibly have travelled between the two fixes:
 *
 *     cand.accuracy + max(5.0, (candSpeed + curSpeed) / 2) * elapsedSeconds < cur.accuracy
 *
 * The allowance is the point of the rule. Two fixes 10 s apart at 20 m/s describe positions
 * 200 m apart, so a candidate that is only marginally "more accurate" is indistinguishable
 * from the incumbent, and switching to it would manufacture noise rather than remove it.
 */
object FixArbiter {
    /** Minimum accuracy improvement required to displace an incumbent, in metres. */
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
            candidate.accuracyM.toDouble() + slackMetres(current, candidate) < current.accuracyM -> candidate
            else -> current
        }

    private fun slackMetres(
        a: Fix,
        b: Fix,
    ): Double {
        val elapsedSeconds = abs(b.timestampMillis - a.timestampMillis) / 1000.0
        if (elapsedSeconds <= 0.0) return MIN_SLACK_M

        val meanSpeedMs = (a.speedMs.toDouble() + b.speedMs.toDouble()) / 2.0
        return max(MIN_SLACK_M, meanSpeedMs * elapsedSeconds)
    }
}
