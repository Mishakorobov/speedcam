package com.speedcam.core.detect

import com.speedcam.core.geo.LatLon

/** Provenance of a camera record. Stored on every row; see Plan.md §4. */
enum class CameraSource {
    /** OpenStreetMap `highway=speed_camera`. ODbL 1.0. */
    OSM,

    /** Community SpeedCam corpus, Russia-specific. Licence UNVERIFIED — see Plan.md §9.1. */
    SPEEDCAM,

    /** Added by the user. */
    MANUAL,

    /** Proprietary ContraCam binary archive (map.dat) via HTTP. */
    CONTRACAM,
}

/**
 * Device class.
 *
 * The original application distinguishes 128 hazard categories; only the part that a
 * GPS-driven app can actually act on is reproduced here.
 */
enum class CameraKind {
    /** Fixed single-point speed measurement. */
    FIXED,

    /** Red-light plus speed. */
    RED_LIGHT,

    /** Mobile or temporary enforcement. Rarely mappable, frequently absent from the DB. */
    MOBILE,

    /** Point marking the entry of an average-speed corridor. */
    AVERAGE_ENTRY,

    /** Point marking the exit of an average-speed corridor. */
    AVERAGE_EXIT,

    /** Combined or otherwise multi-measurement installation. */
    COMPLEX,

    /** Dummy / non-functional housing. */
    DUMMY,

    /** Dedicated bus / public transport lane enforcement. */
    BUS_LANE,

    /** Road marking, solid line, and lane change enforcement. */
    ROAD_MARKING,

    UNKNOWN,
}

/**
 * A camera as the detection engine sees it.
 *
 * This is a plain domain type deliberately decoupled from the Room entity in `core:data`,
 * which maps its persistence model into this one. That separation is what keeps
 * `DetectionEngine` a pure, dependency-free, fully testable component.
 */
data class Camera(
    val id: Long,
    val position: LatLon,
    val source: CameraSource,
    val externalId: String? = null,
    /** Enforced limit in km/h, or null when the source did not record one. */
    val limitKmh: Int? = null,
    val kind: CameraKind = CameraKind.UNKNOWN,
    /**
     * Compass direction the device faces, in degrees within [0, 360).
     * Null means the source recorded no direction, i.e. the camera may face either way.
     */
    val facingDeg: Double? = null,
    /** How many approach directions this record represents. 1 for a single-direction device. */
    val dirCount: Int = 1,
    val operator: String? = null,
    /**
     * The source record verbatim, where the format is not fully documented.
     *
     * Kept so a later parser correction can be applied to already-imported data without
     * re-fetching the corpus. Meaningless for OSM, whose schema is fully specified.
     */
    val sourceDetail: String? = null,
    /** Provenance / freshness, ISO-8601. Null means unknown. */
    val checkDate: String? = null,
) {
    /** True when the source recorded a single facing direction we can use to suppress oncoming warnings. */
    val hasKnownFacing: Boolean get() = facingDeg != null && dirCount <= 1
}
