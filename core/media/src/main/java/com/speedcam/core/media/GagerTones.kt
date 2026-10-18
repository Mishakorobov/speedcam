package com.speedcam.core.media

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Frequency ladder for the distance-pitched proximity tone.
 *
 * Ported from the original application (`ToneEngine`, 8 kHz / 16-bit mono PCM via `AudioTrack`).
 * The pitch rises as the camera gets closer, which is the single most recognisable
 * characteristic of the original's audio design.
 *
 * The step index is derived from distance in kilometres via `floor(km * 16.0934)`, which is
 * `km * (1000 ft per km) / (60 m per step)`. Retained verbatim so that the tone feels
 * identical to the reference.
 */
object GagerTones {
    const val SAMPLE_RATE_HZ = 8_000
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1

    /** Upper bound of the ladder index. */
    const val MAX_INDEX = 19

    /** Multiplier converting kilometres to the step index. */
    const val KM_TO_STEP = 16.0934

    private data class Step(
        val frequencyHz: Int,
        val durationMs: Int,
    )

    private val LADDER =
        arrayOf(
            Step(1328, 500), // 0-1   ~0 km
            Step(1328, 500),
            Step(1248, 600), // 2-3
            Step(1248, 600),
            Step(1168, 700), // 4-5
            Step(1168, 700),
            Step(1088, 800), // 6-7
            Step(1088, 800),
            Step(1028, 800), // 8-19
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
            Step(1028, 800),
        )

    fun stepIndex(distanceKm: Double): Int = floor(distanceKm * KM_TO_STEP).toInt().coerceIn(0, MAX_INDEX)

    fun frequencyHz(distanceKm: Double): Int = LADDER[stepIndex(distanceKm)].frequencyHz

    fun durationMs(distanceKm: Double): Int = LADDER[stepIndex(distanceKm)].durationMs

    /**
     * Renders one tone as 16-bit little-endian PCM with linear fade in and out.
     *
     * The ramps suppress the click that a hard start/stop on the buffer would produce.
     */
    fun render(distanceKm: Double): ShortArray {
        val durationMs = durationMs(distanceKm)
        val frequency = frequencyHz(distanceKm).toDouble()
        val total = SAMPLE_RATE_HZ * durationMs / 1000
        val ramp = (total / RAMP_DIVISOR).coerceAtLeast(1)

        val out = ShortArray(total)
        for (i in 0 until total) {
            val envelope =
                when {
                    i < ramp -> i.toDouble() / ramp
                    i >= total - ramp -> (total - i).toDouble() / ramp
                    else -> 1.0
                }
            val sample = sin(2.0 * PI * frequency * i / SAMPLE_RATE_HZ) * envelope * AMPLITUDE
            out[i] = sample.toInt().toShort()
        }
        return out
    }

    /** Fade length as a fraction of the tone duration. Matches the original's `ramp` parameter. */
    private const val RAMP_DIVISOR = 3

    /** Peak amplitude, about -3 dBFS, leaving headroom against clipping. */
    private const val AMPLITUDE = 22_000.0
}
