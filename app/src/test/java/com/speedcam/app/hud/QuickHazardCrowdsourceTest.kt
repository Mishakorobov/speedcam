package com.speedcam.app.hud

import com.speedcam.app.map.VectorMapActions
import com.speedcam.app.util.toRussianTitle
import com.speedcam.app.util.toShortTitle
import com.speedcam.core.data.HazardReportEntity
import com.speedcam.core.data.HazardReportType
import com.speedcam.core.data.ReportSyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickHazardCrowdsourceTest {
    @Test
    fun `hazard report types define localized titles and short action labels`() {
        assertEquals("Засада ДПС", HazardReportType.DPS_PATROL.toRussianTitle())
        assertEquals("ДПС", HazardReportType.DPS_PATROL.toShortTitle())

        assertEquals("Мобильная тренога", HazardReportType.MOBILE_TRIPOD.toRussianTitle())
        assertEquals("Тренога", HazardReportType.MOBILE_TRIPOD.toShortTitle())

        assertEquals("Авария (ДТП)", HazardReportType.ROAD_ACCIDENT.toRussianTitle())
        assertEquals("ДТП", HazardReportType.ROAD_ACCIDENT.toShortTitle())
    }

    @Test
    fun `vector map actions dispatches quick hazard report type`() {
        val reported = mutableListOf<HazardReportType>()
        val actions =
            VectorMapActions(
                onReportHazardType = { reported.add(it) },
            )

        actions.onReportHazardType(HazardReportType.DPS_PATROL)
        actions.onReportHazardType(HazardReportType.MOBILE_TRIPOD)
        actions.onReportHazardType(HazardReportType.ROAD_ACCIDENT)

        assertEquals(
            listOf(
                HazardReportType.DPS_PATROL,
                HazardReportType.MOBILE_TRIPOD,
                HazardReportType.ROAD_ACCIDENT,
            ),
            reported,
        )
    }

    @Test
    fun `hazard report entity stores crowdsource hazard report type correctly`() {
        val report =
            HazardReportEntity(
                lat = 55.75,
                lon = 37.61,
                headingDeg = 90.0,
                cameraId = null,
                reportType = HazardReportType.DPS_PATROL.ordinal,
                speedLimitKmh = 60,
                comment = "Засада ДПС (быстрая отметка)",
                status = ReportSyncStatus.PENDING.ordinal,
                attempts = 0,
            )

        assertEquals(HazardReportType.DPS_PATROL.ordinal, report.reportType)
        assertEquals(ReportSyncStatus.PENDING.ordinal, report.status)
        assertEquals(55.75, report.lat)
        assertEquals(37.61, report.lon)
        assertEquals("Засада ДПС (быстрая отметка)", report.comment)
    }
}
