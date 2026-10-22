package com.speedcam.core.media

import com.speedcam.core.detect.AlertChannel
import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.Hazard
import com.speedcam.core.detect.SectionEvent
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoicePhraseBuilderTest {
    private fun makeCamera(
        kind: CameraKind = CameraKind.FIXED,
        limitKmh: Int? = 60,
        sourceDetail: String? = null,
    ) = Camera(
        id = 1L,
        position = LatLon(55.75, 37.61),
        source = CameraSource.OSM,
        kind = kind,
        limitKmh = limitKmh,
        facingDeg = 180.0,
        sourceDetail = sourceDetail,
    )

    private fun makeHazard(
        camera: Camera,
        isBehind: Boolean = false,
        distanceM: Double = 300.0,
        excessKmh: Double? = 0.0,
    ) = Hazard(
        camera = camera,
        distanceM = distanceM,
        bearingDeg = 180.0,
        relativeBearingDeg = if (isBehind) 150.0 else 10.0,
        excessKmh = excessKmh,
    )

    private fun makeDecision(
        distanceM: Double = 300.0,
        limitKmh: Int? = 60,
        excessKmh: Double? = 0.0,
    ) = AlertDecision(
        channels = setOf(AlertChannel.VOICE),
        soundId = 0,
        pitched = false,
        shortVoice = false,
        distanceM = distanceM,
        limitKmh = limitKmh,
        excessKmh = excessKmh,
    )

    @Test
    fun `resolves Bus Lane fixation from CameraKind and ContraCam subtypes`() {
        val busLaneCam = makeCamera(kind = CameraKind.BUS_LANE)
        assertEquals(FixationType.BUS_LANE, VoicePhraseBuilder.resolveFixation(busLaneCam))

        val contraCamFwd = makeCamera(kind = CameraKind.COMPLEX, sourceDetail = "subType=8,code=0")
        assertEquals(FixationType.BUS_LANE, VoicePhraseBuilder.resolveFixation(contraCamFwd))

        val contraCamBack = makeCamera(kind = CameraKind.COMPLEX, sourceDetail = "subType=49,code=0")
        assertEquals(FixationType.BUS_LANE, VoicePhraseBuilder.resolveFixation(contraCamBack))
    }

    @Test
    fun `resolves Red Light fixation from CameraKind and ContraCam subtypes`() {
        val redLightCam = makeCamera(kind = CameraKind.RED_LIGHT)
        assertEquals(FixationType.RED_LIGHT, VoicePhraseBuilder.resolveFixation(redLightCam))

        val contraCamFwd = makeCamera(kind = CameraKind.COMPLEX, sourceDetail = "subType=7,code=0")
        assertEquals(FixationType.RED_LIGHT, VoicePhraseBuilder.resolveFixation(contraCamFwd))

        val contraCamBack = makeCamera(kind = CameraKind.COMPLEX, sourceDetail = "subType=48,code=0")
        assertEquals(FixationType.RED_LIGHT, VoicePhraseBuilder.resolveFixation(contraCamBack))
    }

    @Test
    fun `resolves Road Marking fixation from CameraKind and ContraCam subtypes`() {
        val markingCam = makeCamera(kind = CameraKind.ROAD_MARKING)
        assertEquals(FixationType.ROAD_MARKING, VoicePhraseBuilder.resolveFixation(markingCam))

        val contraCamFwd = makeCamera(kind = CameraKind.COMPLEX, sourceDetail = "subType=9,code=0")
        assertEquals(FixationType.ROAD_MARKING, VoicePhraseBuilder.resolveFixation(contraCamFwd))

        val contraCamBack = makeCamera(kind = CameraKind.COMPLEX, sourceDetail = "subType=50,code=0")
        assertEquals(FixationType.ROAD_MARKING, VoicePhraseBuilder.resolveFixation(contraCamBack))
    }

    @Test
    fun `builds full and short speech for Bus Lane camera`() {
        val camera = makeCamera(kind = CameraKind.BUS_LANE, limitKmh = 60)
        val hazard = makeHazard(camera, isBehind = false)
        val decision = makeDecision(distanceM = 300.0, limitKmh = 60, excessKmh = 0.0)

        val fullText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.FULL)
        assertTrue(fullText.contains("контроль полосы общественного транспорта"))
        assertTrue(fullText.contains("через 300 метров"))
        assertTrue(fullText.contains("Ограничение 60 километров в час"))
        assertFalse(fullText.contains("Снижайте скорость!"))

        val shortText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.SHORT)
        assertEquals("полоса ОТ 60, 300 метров", shortText)
    }

    @Test
    fun `builds full and short speech for Red Light camera`() {
        val camera = makeCamera(kind = CameraKind.RED_LIGHT, limitKmh = null)
        val hazard = makeHazard(camera, isBehind = false)
        val decision = makeDecision(distanceM = 200.0, limitKmh = null, excessKmh = null)

        val fullText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.FULL)
        assertTrue(fullText.contains("контроль светофора"))
        assertTrue(fullText.contains("через 200 метров"))

        val shortText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.SHORT)
        assertEquals("светофор, 200 метров", shortText)
    }

    @Test
    fun `builds full and short speech for Road Marking camera`() {
        val camera = makeCamera(kind = CameraKind.ROAD_MARKING, limitKmh = 80)
        val hazard = makeHazard(camera, isBehind = false)
        val decision = makeDecision(distanceM = 500.0, limitKmh = 80, excessKmh = 0.0)

        val fullText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.FULL)
        assertTrue(fullText.contains("контроль разметки"))
        assertTrue(fullText.contains("через 500 метров"))

        val shortText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.SHORT)
        assertEquals("разметка 80, 500 метров", shortText)
    }

    @Test
    fun `announces oncoming behind camera correctly`() {
        val camera = makeCamera(kind = CameraKind.FIXED, limitKmh = 60)
        val hazard = makeHazard(camera, isBehind = true)
        val decision = makeDecision(distanceM = 300.0, limitKmh = 60, excessKmh = 0.0)

        val fullText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.FULL)
        assertTrue(fullText.contains("в спину"))

        val shortText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.SHORT)
        assertTrue(shortText.contains("в спину"))
    }

    @Test
    fun `warns to slow down when speed limit excess is 10 kmh or more`() {
        val camera = makeCamera(kind = CameraKind.FIXED, limitKmh = 60)
        val hazard = makeHazard(camera, isBehind = false, excessKmh = 14.0)
        val decision = makeDecision(distanceM = 300.0, limitKmh = 60, excessKmh = 14.0)

        val fullText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.FULL)
        assertTrue(fullText.endsWith("Снижайте скорость!"))

        val shortText = VoicePhraseBuilder.buildHazardText(decision, hazard, VoicePhraseStyle.SHORT)
        assertTrue(shortText.endsWith("Снижайте скорость!"))
    }

    @Test
    fun `builds natural speech for SectionEvent entry and exit`() {
        val section =
            SectionRecord(
                from = LatLon(55.75, 37.61),
                to = LatLon(55.76, 37.62),
                limitKmh = 90,
            )

        val enterEvent =
            SectionEvent.Entered(
                section = section,
                entryTimeMillis = 1000L,
                entryOdometerM = 0.0,
            )
        assertEquals(
            "Внимание! Начало зоны контроля средней скорости. Ограничение 90 километров в час.",
            VoicePhraseBuilder.buildSectionEventText(enterEvent, VoicePhraseStyle.FULL),
        )
        assertEquals(
            "Зона средней скорости 90.",
            VoicePhraseBuilder.buildSectionEventText(enterEvent, VoicePhraseStyle.SHORT),
        )

        val exitNormal =
            SectionEvent.Exited(
                section = section,
                durationMillis = 100_000L,
                distanceM = 2360.0,
                averageKmh = 85.0,
                excessKmh = -5.0,
            )
        val exitNormalText = VoicePhraseBuilder.buildSectionEventText(exitNormal, VoicePhraseStyle.FULL)
        assertTrue(exitNormalText.contains("Средняя скорость 85 километров в час"))
        assertFalse(exitNormalText.contains("Превышение"))

        val exitExcess =
            SectionEvent.Exited(
                section = section,
                durationMillis = 80_000L,
                distanceM = 2360.0,
                averageKmh = 105.0,
                excessKmh = 15.0,
            )
        val exitExcessText = VoicePhraseBuilder.buildSectionEventText(exitExcess, VoicePhraseStyle.FULL)
        assertTrue(exitExcessText.contains("Превышение скорости!"))
    }

    @Test
    fun `resolves audio clip keys accurately for audio pack matching`() {
        val camera = makeCamera(kind = CameraKind.BUS_LANE, limitKmh = 60)
        val hazard = makeHazard(camera, isBehind = true, excessKmh = 15.0)
        val decision = makeDecision(distanceM = 300.0, limitKmh = 60, excessKmh = 15.0)

        val fullKeys = VoicePhraseBuilder.getAudioClipKeys(decision, hazard, VoicePhraseStyle.FULL)
        assertEquals(
            listOf("attention", "dist_300", "cam_bus_lane", "oncoming", "limit_60", "slow_down"),
            fullKeys,
        )

        val shortKeys = VoicePhraseBuilder.getAudioClipKeys(decision, hazard, VoicePhraseStyle.SHORT)
        assertEquals(
            listOf("dist_300", "cam_bus_lane", "oncoming", "limit_60", "slow_down"),
            shortKeys,
        )
    }
}
