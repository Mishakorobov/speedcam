package com.speedcam.tools.import

import com.speedcam.tools.import.source.BoundingBox

/**
 * Command-line contract for the offline database builder.
 *
 * Runs on a desktop JVM and is not shipped in the APK. It turns upstream OSM and SpeedCam
 * extracts into the SQLite file that `core:data` opens at runtime.
 *
 * ```
 * ./gradlew :tools:import:run --args="--output cameras.db --osm-area russia"
 * ./gradlew :tools:import:run --args="--output cams.db --osm-bbox 55.4,36.9,56.1,38.3"
 * ./gradlew :tools:import:run --args="--output cams.db --speedcam rus.txt --osm-area russia"
 * ```
 */
object ImportOptions {
    const val KEY_HELP = "help"
    const val KEY_SPEEDCAM = "speedcam"
    const val KEY_OVERPASS = "osm-area"
    const val KEY_BBOX = "osm-bbox"
    const val KEY_PBF = "osm-pbf"
    const val KEY_OUTPUT = "output"

    /** Values accepted by [KEY_OVERPASS]. */
    object Areas {
        const val RUSSIA = "russia"

        /**
         * The whole country in one bounding box.
         *
         * Kaliningrad is an exclave in the west but falls inside this box, so it is not a
         * separate area. The eastern edge stops just short of the antimeridian because
         * Overpass bboxes may not wrap it.
         */
        val ALL = listOf(RUSSIA)
    }

    /**
     * Parses `south,west,north,east`.
     *
     * Present so a single region can be refreshed without re-fetching the country, which is
     * the common case: a driver mostly needs the roads they actually use.
     */
    fun parseBbox(text: String): BoundingBox {
        val parts = text.split(',').map { it.trim() }
        require(parts.size == 4) { "expected south,west,north,east but got '$text'" }

        val numbers = parts.map { it.toDoubleOrNull() ?: error("'$it' is not a number in '$text'") }
        return BoundingBox(
            south = numbers[0],
            west = numbers[1],
            north = numbers[2],
            east = numbers[3],
        )
    }

    val USAGE =
        """
        speedcam-import — build the camera database

          --speedcam <file>     SpeedCam-format text file to import
          --osm-pbf <file>      Geofabrik .osm.pbf extract. Repeatable.
          --osm-area <area>     Fetch from Overpass. Repeatable. One of: ${Areas.ALL.joinToString(", ")}
          --osm-bbox <s,w,n,e>  Fetch an arbitrary Overpass region. Repeatable.
                                 Example: --osm-bbox 55.4,36.9,56.1,38.3
          --output <file>       SQLite database to write. Required.
          --help                Show this message

        Attribution: camera data derived from OpenStreetMap must carry
        "Data © OpenStreetMap contributors" under the ODbL 1.0 licence.

        Overpass tiles each region, because a single query spanning a country times out on
        the public instances. Tiles that fail after three attempts are skipped and reported;
        re-run to fill them.
        """.trimIndent()
}
