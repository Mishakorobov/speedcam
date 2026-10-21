package com.speedcam.core.media

import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.Hazard
import kotlin.math.roundToInt

/**
 * What to say, in order.
 *
 * [phrases] is a flat list rather than a tree because the original queues a list of `.m4a`
 * URIs and plays them in order with a short delay clip between each. The delay is a *file*,
 * not a number — `PhraseShortDelay.m4a` — and when it is missing the phrases simply run
 * back-to-back.
 */
data class SpeechPlan(
    val phrases: List<Phrase>,
    /** True when the driver is being told to slow down. */
    val warnsExcess: Boolean = false,
    /** Enforced speed limit in km/h, when the announcement carries one. */
    val limitKmh: Int? = null,
    /** Achieved average speed in km/h, spoken on leaving an average-speed corridor. */
    val achievedAverageKmh: Int? = null,
) {
    val isEmpty: Boolean get() = phrases.isEmpty()
}

/**
 * Builds the spoken warning for one hazard.
 *
 * Plan.md §7.3: "a deliberately compact phrase set replaces the original's ~450-entry enum:
 * distance words, camera type, excess, average-speed zone entry/exit, oncoming-camera
 * suppression". That is what this produces.
 */
object SpeechPlanner {
    /** Excess at or above this is worth saying out loud, in km/h. */
    const val EXCESS_THRESHOLD_KMH = 10.0

    /**
     * How the warning is worded.
     *
     * @param decision what the alert policy decided to do.
     * @param hazard the hazard being announced.
     */
    fun plan(
        decision: AlertDecision,
        hazard: Hazard,
    ): SpeechPlan {
        val phrases = ArrayList<Phrase>(6)
        phrases += DistancePhrases.describe(decision.distanceM)
        phrases += cameraPhrase(hazard.camera.kind)

        val limit = decision.limitKmh?.takeIf { it > 0 }
        if (limit != null) {
            phrases += Phrase.SPEED_LIMIT
        }

        // An oncoming camera is announced differently from one being driven at, because the
        // action is the same but the geometry is not. The original has no phrase for it and
        // expresses the distinction purely by suppression; saying so is the useful half.
        if (hazard.isBehind) {
            phrases += Phrase.ONCOMING_CAMERA
        }

        val excess = decision.excessKmh ?: 0.0
        val warns = limit != null && excess >= EXCESS_THRESHOLD_KMH
        if (warns) {
            phrases += Phrase.DECREASE_SPEED
        }

        return SpeechPlan(
            phrases = phrases,
            warnsExcess = warns,
            limitKmh = limit,
        )
    }

    /**
     * Spoken on entering a measured-speed corridor, carrying the corridor's [limitKmh].
     *
     * Plan.md §8.1: "Announce zone entry with the limit and zone exit with the achieved average."
     */
    fun zoneEntered(limitKmh: Int): SpeechPlan =
        SpeechPlan(
            phrases = listOf(Phrase.AVERAGE_SPEED_ZONE_ENTERED, Phrase.SPEED_LIMIT),
            warnsExcess = false,
            limitKmh = limitKmh.takeIf { it > 0 },
        )

    /**
     * Spoken on leaving a measured-speed corridor, with the [averageKmh] actually achieved
     * and the corridor's [limitKmh].
     */
    fun zoneExited(
        averageKmh: Double,
        limitKmh: Int,
    ): SpeechPlan {
        val warns = limitKmh > 0 && (averageKmh - limitKmh) >= EXCESS_THRESHOLD_KMH
        val phrases =
            if (warns) {
                listOf(Phrase.AVERAGE_SPEED_ZONE_EXIT, Phrase.DECREASE_SPEED)
            } else {
                listOf(Phrase.AVERAGE_SPEED_ZONE_EXIT)
            }
        return SpeechPlan(
            phrases = phrases,
            warnsExcess = warns,
            limitKmh = limitKmh.takeIf { it > 0 },
            achievedAverageKmh = averageKmh.roundToInt(),
        )
    }

    private fun cameraPhrase(kind: CameraKind): Phrase =
        when (kind) {
            CameraKind.RED_LIGHT -> Phrase.RED_LIGHT_CAMERA
            CameraKind.MOBILE -> Phrase.MOBILE_CAMERA
            CameraKind.COMPLEX -> Phrase.COMPLEX_CAMERA
            else -> Phrase.SPEED_CAMERA
        }
}
