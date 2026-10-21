package com.speedcam.core.data

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the seam between persistence and the pure domain model.
 *
 * `CameraMappers` is the single place where Room rows become the values the detection engine
 * sees, and nothing else in the project would catch a mistake here: a wrong mapping does not
 * fail loudly, it just stops warning about a camera. The enum-ordinal degradation cases
 * matter most, because the database is written by an external JVM tool whose idea of the
 * enum orderings could drift from the app's.
 */
class CameraMappersTest {
    private fun entity(
        id: Long = 42,
        source: Int = CameraSource.SPEEDCAM.ordinal,
        externalId: String? = "node/1",
        lat: Double = 55.7522,
        lon: Double = 37.6156,
        limitKmh: Int? = 60,
        kind: Int = CameraKind.RED_LIGHT.ordinal,
        facingDeg: Double? = null,
        dirCount: Int = 1,
        operator: String? = "GIBDD",
        sourceDetail: String? = null,
        checkDate: String? = "2026-05-31",
    ) = CameraEntity(
        id = id,
        source = source,
        externalId = externalId,
        lat = lat,
        lon = lon,
        limitKmh = limitKmh,
        kind = kind,
        facingDeg = facingDeg,
        dirCount = dirCount,
        operator = operator,
        sourceDetail = sourceDetail,
        checkDate = checkDate,
    )

    @Test
    fun `toDomain carries every field across`() {
        val e = entity()
        val c = CameraMappers.toDomain(e)

        assertEquals(42L, c.id)
        assertEquals(LatLon(55.7522, 37.6156), c.position)
        assertEquals(CameraSource.SPEEDCAM, c.source)
        assertEquals("node/1", c.externalId)
        assertEquals(60, c.limitKmh)
        assertEquals(CameraKind.RED_LIGHT, c.kind)
        assertNull(c.facingDeg)
        assertEquals(1, c.dirCount)
        assertEquals("GIBDD", c.operator)
        assertEquals("2026-05-31", c.checkDate)
    }

    @Test
    fun `an unknown source ordinal degrades instead of throwing`() {
        // A database written by a newer build of tools:import could carry an ordinal this
        // build has never heard of. Dropping every row would be far worse than mislabelling one.
        val c = CameraMappers.toDomain(entity(source = 9999))
        assertEquals(CameraSource.OSM, c.source)
    }

    @Test
    fun `an unknown kind ordinal degrades to UNKNOWN`() {
        assertEquals(CameraKind.UNKNOWN, CameraMappers.toDomain(entity(kind = 9999)).kind)
    }

    @Test
    fun `a negative ordinal degrades rather than throwing`() {
        // Kotlin's List.getOrElse treats a negative index as an error the same way an
        // out-of-range positive one is, so this must not escape as IndexOutOfBounds.
        val c = CameraMappers.toDomain(entity(source = -1, kind = -5))
        assertEquals(CameraSource.OSM, c.source)
        assertEquals(CameraKind.UNKNOWN, c.kind)
    }

    @Test
    fun `an absent limit stays null and is not coerced to zero`() {
        // The distinction is load-bearing: null means "the source recorded no limit", while 0
        // would mean a measured zero, and the engine treats them differently downstream.
        val c = CameraMappers.toDomain(entity(limitKmh = null))
        assertNull(c.limitKmh)
    }

    @Test
    fun `an absent facing stays null`() {
        assertNull(CameraMappers.toDomain(entity(facingDeg = null)).facingDeg)
    }

    @Test
    fun `hasKnownFacing requires both a direction and a single approach`() {
        assertTrue(CameraMappers.toDomain(entity(facingDeg = 180.0)).hasKnownFacing)
        assertTrue(!CameraMappers.toDomain(entity(facingDeg = 180.0, dirCount = 2)).hasKnownFacing)
        assertTrue(!CameraMappers.toDomain(entity(facingDeg = null)).hasKnownFacing)
    }

    @Test
    fun `a round trip through the entity preserves the domain model`() {
        val original =
            Camera(
                id = 7,
                position = LatLon(59.9343, 30.3351),
                source = CameraSource.OSM,
                externalId = "way/9",
                limitKmh = 80,
                kind = CameraKind.COMPLEX,
                facingDeg = 275.0,
                dirCount = 1,
                operator = null,
                sourceDetail = "raw",
                checkDate = "2026-10-01",
            )
        assertEquals(original, CameraMappers.toDomain(CameraMappers.toEntity(original)))
    }

    @Test
    fun `a round trip preserves an unknown facing and an absent limit`() {
        val original =
            Camera(
                id = 8,
                position = LatLon(43.1155, 131.8855),
                source = CameraSource.MANUAL,
                externalId = null,
                limitKmh = null,
                kind = CameraKind.UNKNOWN,
                facingDeg = null,
            )
        val restored = CameraMappers.toDomain(CameraMappers.toEntity(original))
        assertNull(restored.facingDeg)
        assertNull(restored.limitKmh)
        assertNull(restored.externalId)
        assertEquals(CameraSource.MANUAL, restored.source)
    }

    @Test
    fun `section round trip preserves endpoints, limit and active flag`() {
        val original =
            SectionRecord(
                from = LatLon(55.70, 37.50),
                to = LatLon(55.72, 37.52),
                limitKmh = 90,
                active = true,
            )
        assertEquals(original, SectionMappers.toDomainOrNull(SectionMappers.toEntity(original)))
    }

    @Test
    fun `a section entity with a non-positive limit is dropped rather than throwing`() {
        val invalid =
            SectionEntity(
                fromLat = 55.70,
                fromLon = 37.50,
                toLat = 55.72,
                toLon = 37.52,
                limitKmh = 0,
            )
        assertNull(SectionMappers.toDomainOrNull(invalid))
    }
}
