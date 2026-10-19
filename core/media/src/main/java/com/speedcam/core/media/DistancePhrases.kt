package com.speedcam.core.media

import kotlin.math.roundToInt

/**
 * A spoken phrase.
 *
 * The original's `IntVoicePhrase` has 450 constants, each naming an `.m4a` file. Which one is
 * spoken for a given hazard is decided inside `libVestigoEngines.so` from an `int[]` and is not
 * recoverable. Only the *vocabulary* survives — 100 m steps to 900 m, then 1 km, then 0.1 km
 * steps, then 3, 4, 5 ... 500 km.
 *
 * So this is a stated replacement rather than a port, in the same spirit as the "Auto" capture
 * distance: the original's rule is hidden, so this one is explicit and testable.
 */
enum class Phrase {
    // --- distance -------------------------------------------------------------
    HUNDRED_METERS,
    TWO_HUNDRED_METERS,
    THREE_HUNDRED_METERS,
    FOUR_HUNDRED_METERS,
    FIVE_HUNDRED_METERS,
    SIX_HUNDRED_METERS,
    SEVEN_HUNDRED_METERS,
    EIGHT_HUNDRED_METERS,
    NINE_HUNDRED_METERS,
    ONE_KILOMETER,
    ONE_POINT_ONE_KILOMETERS,
    ONE_POINT_TWO_KILOMETERS,
    ONE_POINT_FIVE_KILOMETERS,
    ONE_POINT_EIGHT_KILOMETERS,
    TWO_KILOMETERS,
    THREE_KILOMETERS,
    FOUR_KILOMETERS,
    FIVE_KILOMETERS,
    SEVEN_KILOMETERS,
    TEN_KILOMETERS,
    FIFTEEN_KILOMETERS,
    TWENTY_KILOMETERS,
    THIRTY_KILOMETERS,
    FIFTY_KILOMETERS,

    // --- unit words -----------------------------------------------------------
    METER,
    METERS,
    KILOMETER,
    KILOMETERS,

    // --- camera ---------------------------------------------------------------
    SPEED_CAMERA,
    RED_LIGHT_CAMERA,
    MOBILE_CAMERA,
    COMPLEX_CAMERA,

    // --- limit and excess -----------------------------------------------------
    SPEED_LIMIT,
    INCREASE_SPEED,
    DECREASE_SPEED,

    // --- average-speed zones --------------------------------------------------
    AVERAGE_SPEED_ZONE_ENTERED,
    AVERAGE_SPEED_ZONE_EXIT,

    // --- oncoming -------------------------------------------------------------
    ONCOMING_CAMERA,
}

/**
 * How a distance is said out loud.
 *
 * The original announces a distance as a value and a unit — `[Phrase500Meters, PhraseMeters]`
 * for example — and the ladder below follows that shape.
 */
object DistancePhrases {
    /**
     * Phrases for whole-kilometre distances, keyed by the rounded kilometre value.
     *
     * Covers the original's 3..100 km rungs. Below three kilometres the ladder is in metres,
     * because saying "two point three kilometres" is longer and no clearer than "two thousand
     * three hundred metres"... which is why the original switches to metres there too.
     */
    private val KILOMETRES =
        mapOf(
            3 to Phrase.THREE_KILOMETERS,
            4 to Phrase.FOUR_KILOMETERS,
            5 to Phrase.FIVE_KILOMETERS,
            6 to Phrase.FIVE_KILOMETERS,
            7 to Phrase.SEVEN_KILOMETERS,
            8 to Phrase.SEVEN_KILOMETERS,
            9 to Phrase.SEVEN_KILOMETERS,
            10 to Phrase.TEN_KILOMETERS,
            12 to Phrase.TEN_KILOMETERS,
            14 to Phrase.TEN_KILOMETERS,
            15 to Phrase.FIFTEEN_KILOMETERS,
            16 to Phrase.FIFTEEN_KILOMETERS,
            18 to Phrase.FIFTEEN_KILOMETERS,
            20 to Phrase.TWENTY_KILOMETERS,
            25 to Phrase.TWENTY_KILOMETERS,
            30 to Phrase.THIRTY_KILOMETERS,
            40 to Phrase.THIRTY_KILOMETERS,
            50 to Phrase.FIFTY_KILOMETERS,
            60 to Phrase.FIFTY_KILOMETERS,
            70 to Phrase.FIFTY_KILOMETERS,
            80 to Phrase.FIFTY_KILOMETERS,
            90 to Phrase.FIFTY_KILOMETERS,
            100 to Phrase.FIFTY_KILOMETERS,
            200 to Phrase.FIFTY_KILOMETERS,
            300 to Phrase.FIFTY_KILOMETERS,
            500 to Phrase.FIFTY_KILOMETERS,
        )

    /** Largest distance that is still announced in metres rather than kilometres. */
    const val METRES_CEILING_M = 2_000.0

    /**
     * The phrase describing [distanceM], always followed by its unit word.
     *
     * Rounds to the nearest step rather than truncating: "1200 metres" when the camera is
     * 1196 m away is more use than "1000 metres".
     */
    fun describe(distanceM: Double): List<Phrase> {
        require(distanceM >= 0.0) { "distance must not be negative, was $distanceM" }

        return if (distanceM <= METRES_CEILING_M) {
            // Rounded, not truncated, and without pre-adding half a step: adding 50 and then
            // rounding double-counts, and turns 500 m into "six hundred".
            val rounded = (distanceM / 100.0).roundToInt().coerceAtLeast(1)
            listOf(hundredMetrePhrase(rounded), Phrase.METERS)
        } else {
            val km = ((distanceM / 1000.0) * 10).roundToInt() / 10.0
            if (km >= 3.0) {
                listOf(kmPhrase(km), Phrase.KILOMETERS)
            } else {
                val tenths = (km * 10).roundToInt().coerceAtLeast(1)
                listOf(pointOneKmPhrase(tenths), Phrase.KILOMETERS)
            }
        }
    }

    /**
     * Metres phrases indexed by the number of hundreds, so index 5 is "five hundred".
     *
     * A table rather than a `when` chain: the original has the same vocabulary in the same order,
     * and twenty branches of `when` is not a reviewable way to express a lookup.
     */
    private val HUNDREDS =
        listOf(
            Phrase.HUNDRED_METERS,
            Phrase.TWO_HUNDRED_METERS,
            Phrase.THREE_HUNDRED_METERS,
            Phrase.FOUR_HUNDRED_METERS,
            Phrase.FIVE_HUNDRED_METERS,
            Phrase.SIX_HUNDRED_METERS,
            Phrase.SEVEN_HUNDRED_METERS,
            Phrase.EIGHT_HUNDRED_METERS,
            Phrase.NINE_HUNDRED_METERS,
            Phrase.ONE_KILOMETER,
            Phrase.ONE_POINT_ONE_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_EIGHT_KILOMETERS,
            Phrase.ONE_POINT_EIGHT_KILOMETERS,
            Phrase.TWO_KILOMETERS,
        )

/** Tenths-of-a-kilometre phrases, index 10 is one kilometre, index 23 is 2.3 km. */
    private val TENTHS =
        listOf(
            Phrase.ONE_POINT_ONE_KILOMETERS, // index 0 is never used; the ladder starts at 1.0
            Phrase.ONE_POINT_ONE_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_TWO_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_FIVE_KILOMETERS,
            Phrase.ONE_POINT_EIGHT_KILOMETERS,
            Phrase.ONE_POINT_EIGHT_KILOMETERS,
            Phrase.TWO_KILOMETERS,
            Phrase.TWO_KILOMETERS,
            Phrase.TWO_KILOMETERS,
            Phrase.TWO_KILOMETERS,
            Phrase.TWO_KILOMETERS,
            Phrase.TWO_KILOMETERS,
            Phrase.TWO_KILOMETERS,
        )

    private fun hundredMetrePhrase(hundreds: Int): Phrase = HUNDREDS[(hundreds - 1).coerceIn(0, HUNDREDS.lastIndex)]

    private fun pointOneKmPhrase(tenths: Int): Phrase = TENTHS[(tenths - 10).coerceIn(0, TENTHS.lastIndex)]

    /** Snaps to the nearest whole kilometre the vocabulary actually has a word for. */
    private fun kmPhrase(km: Double): Phrase {
        val whole = km.roundToInt().coerceAtLeast(3)
        return KILOMETRES[whole] ?: KILOMETRES.entries.minByOrNull { kotlin.math.abs(it.key - whole) }?.value
            ?: Phrase.FIFTY_KILOMETERS
    }
}
