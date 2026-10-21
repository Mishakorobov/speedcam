package com.speedcam.tools.import

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeduplicatorTest {
    private fun camera(
        lat: Double,
        lon: Double,
        source: CameraSource = CameraSource.OSM,
        limitKmh: Int? = null,
        kind: CameraKind = CameraKind.FIXED,
        facingDeg: Double? = null,
        sourceDetail: String? = null,
    ) = Camera(
        id = 0L,
        position = LatLon(lat, lon),
        source = source,
        externalId = "$source:$lat,$lon",
        limitKmh = limitKmh,
        kind = kind,
        facingDeg = facingDeg,
        dirCount = if (facingDeg == null) 2 else 1,
        sourceDetail = sourceDetail,
    )

    @Test
    fun `records at different positions are left alone`() {
        val cameras =
            listOf(
                camera(55.0, 37.0),
                camera(56.0, 38.0),
            )
        assertEquals(2, Deduplicator().merge(cameras).size)
    }

    @Test
    fun `identical positions across sources collapse`() {
        val cameras =
            listOf(
                camera(55.0, 37.0, source = CameraSource.OSM),
                camera(55.0, 37.0, source = CameraSource.SPEEDCAM),
            )
        assertEquals(1, Deduplicator().merge(cameras).size)
    }

    @Test
    fun `records within the merge radius collapse`() {
        // ~7 m apart, inside the 8 m radius: the two sources disagree by metres on the same
        // installation, which an exact-coordinate bucket would have missed entirely.
        val cameras =
            listOf(
                camera(55.0000000, 37.0000000),
                camera(55.0000630, 37.0000450),
            )
        assertEquals(1, Deduplicator().merge(cameras).size)
    }

    @Test
    fun `records within the merge radius collapse at high latitude`() {
        // At 60°N (e.g. St. Petersburg), longitude degrees are compressed by half (~55.6 m/deg).
        // Two cameras ~7 m apart longitudinally straddle cell boundaries; with CELL_DEGREES = 0.0005
        // they must still be recognized within the 8 m radius and collapse.
        val cameras =
            listOf(
                camera(60.000000, 30.000000, source = CameraSource.OSM),
                camera(60.000000, 30.000125, source = CameraSource.SPEEDCAM),
            )
        assertEquals(1, Deduplicator().merge(cameras).size)
    }

    @Test
    fun `devices a few tens of metres apart stay separate`() {
        // Russian complexes frequently place several devices on one gantry a few metres
        // apart. Merging them would lose a real warning, which is worse than a double alert.
        val cameras =
            listOf(
                camera(55.000000, 37.000000, source = CameraSource.OSM),
                camera(55.000300, 37.000000, source = CameraSource.SPEEDCAM),
            )
        assertEquals(2, Deduplicator().merge(cameras).size)
    }

    @Test
    fun `a long chain of nearby records does not merge transitively`() {
        // Each consecutive pair is inside the radius, but the ends are far apart. A greedy
        // implementation that keeps a single merged representative must not chain A-B-C.
        val cameras =
            listOf(
                camera(55.00000, 37.00000),
                camera(55.00006, 37.00000),
                camera(55.00012, 37.00000),
            )
        val merged = Deduplicator().merge(cameras)
        assertTrue(merged.size >= 2, "expected at least 2 clusters, got ${merged.size}")
    }

    @Test
    fun `distinct installations a hundred metres apart do not collapse`() {
        val cameras =
            listOf(
                camera(55.0000, 37.0000),
                camera(55.0009, 37.0000),
            )
        assertEquals(2, Deduplicator().merge(cameras).size)
    }

    @Test
    fun `merge takes a known limit from whichever record has one`() {
        val cameras =
            listOf(
                camera(55.0, 37.0, source = CameraSource.OSM, limitKmh = null),
                camera(55.0, 37.0, source = CameraSource.SPEEDCAM, limitKmh = 60),
            )
        assertEquals(60, Deduplicator().merge(cameras).single().limitKmh)
    }

    @Test
    fun `merge never invents a limit`() {
        val cameras =
            listOf(
                camera(55.0, 37.0, limitKmh = null),
                camera(55.0, 37.0, source = CameraSource.SPEEDCAM, limitKmh = null),
            )
        assertNull(Deduplicator().merge(cameras).single().limitKmh)
    }

    @Test
    fun `merge prefers a known facing direction`() {
        // A known facing angle is what suppresses an oncoming false alert, so it is worth
        // more than any other single field.
        val cameras =
            listOf(
                camera(55.0, 37.0, limitKmh = 60, facingDeg = null),
                camera(55.0, 37.0, source = CameraSource.SPEEDCAM, facingDeg = null),
                camera(55.0, 37.0, source = CameraSource.MANUAL, facingDeg = 180.0),
            )
        assertEquals(180.0, Deduplicator().merge(cameras).single().facingDeg)
    }

    @Test
    fun `merge prefers a known kind over UNKNOWN`() {
        val cameras =
            listOf(
                camera(55.0, 37.0, kind = CameraKind.UNKNOWN),
                camera(55.0, 37.0, source = CameraSource.SPEEDCAM, kind = CameraKind.RED_LIGHT),
            )
        assertEquals(CameraKind.RED_LIGHT, Deduplicator().merge(cameras).single().kind)
    }

    @Test
    fun `merge preserves every contributing param line`() {
        val cameras =
            listOf(
                camera(55.0, 37.0, sourceDetail = "0,60,2,90,1"),
                camera(55.0, 37.0, source = CameraSource.OSM, sourceDetail = "maxspeed=60"),
            )
        val detail = Deduplicator().merge(cameras).single().sourceDetail
        assertTrue(detail!!.contains("0,60,2,90,1"))
        assertTrue(detail.contains("maxspeed=60"))
    }

    @Test
    fun `merge is stable across input order`() {
        val a = camera(55.0, 37.0, source = CameraSource.OSM, limitKmh = 60)
        val b = camera(55.0, 37.0, source = CameraSource.SPEEDCAM, limitKmh = 90)

        val forwards = Deduplicator().merge(listOf(a, b))
        val backwards = Deduplicator().merge(listOf(b, a))

        assertEquals(forwards.single().limitKmh, backwards.single().limitKmh)
    }

    @Test
    fun `merge output is ordered deterministically`() {
        val cameras =
            listOf(
                camera(57.0, 39.0),
                camera(55.0, 37.0),
                camera(56.0, 38.0),
            )
        val first = Deduplicator().merge(cameras)
        val second = Deduplicator().merge(cameras.reversed())
        assertEquals(first.map { it.position.lat }, second.map { it.position.lat })
    }

    @Test
    fun `an empty input yields an empty result`() {
        assertTrue(Deduplicator().merge(emptyList()).isEmpty())
    }
}
