package com.speedcam.app.map

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VectorMapTest {
    private val moscow = LatLon(55.7558, 37.6173)

    @Test
    fun `initial state respects defaults`() {
        val state = VectorMapState(initialCenter = moscow, initialZoom = 15.0f)
        assertEquals(moscow.lat, state.centerLat, 1e-4)
        assertEquals(moscow.lon, state.centerLon, 1e-4)
        assertEquals(15.0f, state.zoom)
        assertEquals(MapFollowMode.FOLLOW_HEADING, state.followMode)
        assertEquals(MapThemeMode.AUTOMOTIVE_DARK, state.themeMode)
    }

    @Test
    fun `zoomIn and zoomOut clamp within bounds`() {
        val state = VectorMapState(initialZoom = 18.5f)
        state.zoomIn() // 19.0
        assertEquals(19.0f, state.zoom)
        state.zoomIn() // clamps to 19.0
        assertEquals(VectorMapState.MAX_ZOOM, state.zoom)

        state.zoom = 3.5f
        state.zoomOut() // 3.0
        state.zoomOut() // clamps to 3.0
        assertEquals(VectorMapState.MIN_ZOOM, state.zoom)
    }

    @Test
    fun `updateVehicle follows heading in FOLLOW_HEADING mode`() {
        val state = VectorMapState(initialCenter = moscow, initialFollowMode = MapFollowMode.FOLLOW_HEADING)
        val newLat = 55.7600
        val newLon = 37.6200
        val bearing = 120.0

        state.updateVehicle(newLat, newLon, bearing)
        assertEquals(newLat, state.centerLat, 1e-4)
        assertEquals(newLon, state.centerLon, 1e-4)
        assertEquals(120.0f, state.bearingDeg)
    }

    @Test
    fun `updateVehicle keeps zero bearing in FOLLOW_NORTH mode`() {
        val state = VectorMapState(initialCenter = moscow, initialFollowMode = MapFollowMode.FOLLOW_NORTH)
        state.updateVehicle(55.7600, 37.6200, 150.0)

        assertEquals(55.7600, state.centerLat, 1e-4)
        assertEquals(37.6200, state.centerLon, 1e-4)
        assertEquals(0.0f, state.bearingDeg)
    }

    @Test
    fun `pan disengages follow mode into FREE_ROAM`() {
        val state = VectorMapState(initialCenter = moscow, initialFollowMode = MapFollowMode.FOLLOW_HEADING)
        state.pan(50f, 50f, 1000f, 1000f)

        assertEquals(MapFollowMode.FREE_ROAM, state.followMode)
        // updateVehicle must NOT shift center in FREE_ROAM
        val pannedLat = state.centerLat
        val pannedLon = state.centerLon
        state.updateVehicle(59.9343, 30.3351, 90.0)
        assertEquals(pannedLat, state.centerLat, 1e-4)
        assertEquals(pannedLon, state.centerLon, 1e-4)
    }

    @Test
    fun `centerOn restores follow mode`() {
        val state = VectorMapState(initialFollowMode = MapFollowMode.FREE_ROAM)
        val spb = LatLon(59.9343, 30.3351)

        state.centerOn(spb, targetZoom = 16.0f, mode = MapFollowMode.FOLLOW_HEADING)
        assertEquals(spb.lat, state.centerLat, 1e-4)
        assertEquals(spb.lon, state.centerLon, 1e-4)
        assertEquals(16.0f, state.zoom)
        assertEquals(MapFollowMode.FOLLOW_HEADING, state.followMode)
    }

    @Test
    fun `toggleOrientation cycles correctly`() {
        val state = VectorMapState(initialFollowMode = MapFollowMode.FOLLOW_HEADING)
        state.toggleOrientation(currentVehicleBearing = 45.0)
        assertEquals(MapFollowMode.FOLLOW_NORTH, state.followMode)
        assertEquals(0.0f, state.bearingDeg)

        state.toggleOrientation(currentVehicleBearing = 45.0)
        assertEquals(MapFollowMode.FOLLOW_HEADING, state.followMode)
        assertEquals(45.0f, state.bearingDeg)
    }

    @Test
    fun `computeVisibleBoundingBox produces valid box containing center`() {
        val state = VectorMapState(initialCenter = moscow, initialZoom = 15.0f)
        val box = state.computeVisibleBoundingBox(1080f, 1920f)

        assertTrue(box.contains(moscow))
        assertTrue(box.minLat < moscow.lat)
        assertTrue(box.maxLat > moscow.lat)
        assertTrue(box.minLon < moscow.lon)
        assertTrue(box.maxLon > moscow.lon)
    }

    @Test
    fun `parseOverpassJson parses roads correctly`() {
        val sampleJson =
            """
            {
              "elements": [
                {
                  "type": "way",
                  "id": 12345,
                  "tags": {
                    "highway": "motorway",
                    "name": "M-11 Neva"
                  },
                  "geometry": [
                    {"lat": 55.75, "lon": 37.61},
                    {"lat": 55.76, "lon": 37.62}
                  ]
                },
                {
                  "type": "way",
                  "id": 67890,
                  "tags": {
                    "highway": "primary",
                    "name": "Tverskaya"
                  },
                  "geometry": [
                    {"lat": 55.77, "lon": 37.63},
                    {"lat": 55.78, "lon": 37.64}
                  ]
                },
                {
                  "type": "node",
                  "id": 99999,
                  "lat": 55.75,
                  "lon": 37.61
                }
              ]
            }
            """.trimIndent()

        val roads = VectorRoadRepository.parseOverpassJson(sampleJson)
        assertEquals(2, roads.size)

        val r1 = roads[0]
        assertEquals(12345L, r1.id)
        assertEquals("M-11 Neva", r1.name)
        assertEquals(RoadClass.MOTORWAY, r1.kind)
        assertEquals(2, r1.points.size)

        val r2 = roads[1]
        assertEquals(67890L, r2.id)
        assertEquals("Tverskaya", r2.name)
        assertEquals(RoadClass.PRIMARY, r2.kind)
    }

    @Test
    fun `parseOverpassJson handles empty or invalid json safely`() {
        val emptyRoads = VectorRoadRepository.parseOverpassJson("""{"elements": []}""")
        assertTrue(emptyRoads.isEmpty())
    }

    @Test
    fun `findCameraClusterCentroid calculates centroid accurately`() {
        assertNull(MapCenteringHelper.findCameraClusterCentroid(emptyList()))

        val cam1 =
            Camera(
                id = 1L,
                position = LatLon(50.0, 30.0),
                source = CameraSource.OSM,
                externalId = "1",
                kind = CameraKind.FIXED,
            )
        val cam2 =
            Camera(
                id = 2L,
                position = LatLon(60.0, 40.0),
                source = CameraSource.OSM,
                externalId = "2",
                kind = CameraKind.FIXED,
            )

        val centroid = MapCenteringHelper.findCameraClusterCentroid(listOf(cam1, cam2))
        assertNotNull(centroid)
        assertEquals(55.0, centroid!!.lat, 1e-4)
        assertEquals(35.0, centroid.lon, 1e-4)
    }

    @Test
    fun `isAtDefaultCenter accurately detects default Moscow coordinates`() {
        val state = VectorMapState(initialCenter = LatLon(55.7558, 37.6173))
        assertTrue(state.isAtDefaultCenter())

        state.centerOn(LatLon(59.9343, 30.3351))
        assertFalse(state.isAtDefaultCenter())
    }

    @Test
    fun `SyntheticRoadCorridors generates vector roads from SPECS sections`() {
        val cam1 =
            Camera(
                id = 100L,
                position = LatLon(55.10, 37.10),
                source = CameraSource.OSM,
                externalId = "100",
                kind = CameraKind.AVERAGE_ENTRY,
            )
        val cam2 =
            Camera(
                id = 200L,
                position = LatLon(55.20, 37.20),
                source = CameraSource.OSM,
                externalId = "200",
                kind = CameraKind.AVERAGE_EXIT,
            )
        val section =
            SectionRecord(
                from = LatLon(55.10, 37.10),
                to = LatLon(55.20, 37.20),
                limitKmh = 90,
            )

        val corridors = SyntheticRoadCorridors.createSectionCorridors(listOf(section), listOf(cam1, cam2))
        assertEquals(1, corridors.size)
        val road = corridors[0]
        assertEquals(RoadClass.MOTORWAY, road.kind)
        assertEquals(2, road.points.size)
        assertEquals(55.10, road.points[0].lat, 1e-4)
        assertEquals(55.20, road.points[1].lat, 1e-4)
    }

    @Test
    fun `VectorRoadRepository defines multiple mirrors and lowered MIN_ROAD_ZOOM`() {
        assertTrue(VectorRoadRepository.MIN_ROAD_ZOOM <= 11.5f)
        assertTrue(VectorRoadRepository.OVERPASS_MIRRORS.size >= 2)
    }
}
