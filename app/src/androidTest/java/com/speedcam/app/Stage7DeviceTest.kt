package com.speedcam.app

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.speedcam.core.data.CameraStore
import com.speedcam.core.data.SectionEntity
import com.speedcam.core.data.SectionMappers
import com.speedcam.core.data.SpeedCamDatabase
import com.speedcam.core.detect.AlertContext
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.AverageSpeedTracker
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.Fix
import com.speedcam.core.detect.SectionAbortReason
import com.speedcam.core.detect.SectionEvent
import com.speedcam.core.detect.SectionQuery
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import com.speedcam.core.media.MediaAlertEngine
import com.speedcam.service.DetectionPipeline
import com.speedcam.service.DetectionState
import com.speedcam.service.toSectionUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos

@RunWith(AndroidJUnit4::class)
class Stage7DeviceTest {
    private lateinit var context: Context
    private lateinit var testDatabase: SpeedCamDatabase
    private lateinit var store: CameraStore

    // Fixed test corridor: 55.7500 N to 55.7600 N (~1112 m North), limit 60 km/h.
    private val startPos = LatLon(55.7500, 37.6000)
    private val endPos = offset(startPos, metresNorth = 1_000.0, metresEast = 0.0)
    private val corridor = SectionRecord(from = startPos, to = endPos, limitKmh = 60)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDatabase =
            Room
                .inMemoryDatabaseBuilder(context, SpeedCamDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = CameraStore.of(testDatabase)
        DetectionState.reset()
    }

    @After
    fun tearDown() {
        store.close()
        testDatabase.close()
        DetectionState.reset()
    }

    @Test
    fun test01_deviceEnvironment() {
        assertTrue("Expected Android API >= 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        assertNotNull("Application context should be available", context)
        android.util.Log.i(
            "Stage7DeviceTest",
            "Running on device: ${Build.MANUFACTURER} ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}",
        )
    }

    @Test
    fun test02_roomDatabaseSectionPersistenceOnDevice() {
        val sectionDao = testDatabase.sectionDao()

        val entity =
            SectionEntity(
                id = 101,
                fromLat = startPos.lat,
                fromLon = startPos.lon,
                toLat = endPos.lat,
                toLon = endPos.lon,
                limitKmh = 60,
                active = 1,
            )
        runBlocking {
            sectionDao.upsertAll(listOf(entity))
        }

        val activeList = runBlocking { sectionDao.active() }
        assertEquals(1, activeList.size)
        assertEquals(101L, activeList[0].id)
        assertEquals(60, activeList[0].limitKmh)

        val domainList = SectionMappers.toDomainList(activeList)
        assertEquals(1, domainList.size)
        val domainRecord = domainList[0]
        assertEquals(startPos.lat, domainRecord.from.lat, 1e-6)
        assertEquals(startPos.lon, domainRecord.from.lon, 1e-6)
        assertEquals(endPos.lat, domainRecord.to.lat, 1e-6)
        assertEquals(endPos.lon, domainRecord.to.lon, 1e-6)
        assertEquals(60, domainRecord.limitKmh)
        assertTrue("Corridor length must be positive", domainRecord.lengthM > 990.0)
    }

    @Test
    fun test03_corridorEntryTrackingAndExitLifecycleOnDevice() {
        val tracker = AverageSpeedTracker(SectionQuery { listOf(corridor) })
        assertFalse(tracker.isInCorridor)

        // 1. Approach fix 200 m south: heading North, outside gate
        val approachFix = fix(offset(startPos, -200.0, 0.0), headingDeg = 0.0, timeMs = 1_000L)
        assertNull("Approach fix outside gate radius must not enter", tracker.onFix(approachFix))
        assertFalse(tracker.isInCorridor)

        // 2. Entry fix within gate (startPos, heading North)
        val entryFix = fix(startPos, headingDeg = 0.0, timeMs = 10_000L)
        val entered = tracker.onFix(entryFix) as? SectionEvent.Entered
        assertNotNull("Passing entry point within gate must emit Entered", entered)
        assertTrue(tracker.isInCorridor)
        assertEquals(corridor.limitKmh, entered!!.section.limitKmh)
        assertEquals(10_000L, entered.entryTimeMillis)

        // 3. Mid-corridor progress fix: 500 m North after 20 seconds
        val midPos = offset(startPos, metresNorth = 500.0, metresEast = 0.0)
        val midFix = fix(midPos, headingDeg = 0.0, timeMs = 30_000L)
        val progress = tracker.onFix(midFix) as? SectionEvent.Progress
        assertNotNull("Intermediate fix must emit Progress", progress)
        assertTrue(tracker.isInCorridor)
        assertEquals(20_000L, progress!!.elapsedMillis)
        val expectedDist500 = Geo.haversineMeters(startPos, midPos)
        val expectedSpeed500Kmh = (expectedDist500 / 20.0) * 3.6 // ~90 km/h
        assertEquals(expectedSpeed500Kmh, progress.averageKmh, 0.5)
        assertEquals(expectedSpeed500Kmh - 60.0, progress.excessKmh, 0.5)

        // 4. Exit fix: reaching endPos after 40 seconds total from entry
        val exitFix = fix(endPos, headingDeg = 0.0, timeMs = 50_000L)
        val exited = tracker.onFix(exitFix) as? SectionEvent.Exited
        assertNotNull("Reaching exit gate must emit Exited", exited)
        assertFalse("Corridor must be inactive after exit", tracker.isInCorridor)

        val totalDist = Geo.haversineMeters(startPos, midPos) + Geo.haversineMeters(midPos, endPos)
        val expectedAvgKmh = (totalDist / 40.0) * 3.6 // 1000m in 40s = 25 m/s = 90 km/h
        assertEquals(40_000L, exited!!.durationMillis)
        assertEquals(totalDist, exited.distanceM, 1e-4)
        assertEquals(expectedAvgKmh, exited.averageKmh, 0.1)
        assertEquals(expectedAvgKmh - 60.0, exited.excessKmh, 0.1)
    }

    @Test
    fun test04_oncomingTrafficDoesNotEnterCorridorOnDevice() {
        val tracker = AverageSpeedTracker(SectionQuery { listOf(corridor) })
        // Traffic heading South (180 deg) on a Northbound (0 deg) corridor
        val oncomingFix = fix(startPos, headingDeg = 180.0, timeMs = 1_000L)
        assertNull("Oncoming fix must be rejected by heading gate", tracker.onFix(oncomingFix))
        assertFalse(tracker.isInCorridor)
    }

    @Test
    fun test05_corridorAbortScenariosOnDevice() {
        // Abort Scenario A: Wandering off-corridor
        val trackerA = AverageSpeedTracker(SectionQuery { listOf(corridor) })
        trackerA.onFix(fix(startPos, headingDeg = 0.0, timeMs = 1_000L))
        assertTrue(trackerA.isInCorridor)

        // Fix deviates 3000m East perpendicular to corridor
        val offRoute = fix(offset(startPos, metresNorth = 500.0, metresEast = 3_000.0), headingDeg = 90.0, timeMs = 15_000L)
        val abortedOff = trackerA.onFix(offRoute) as? SectionEvent.Aborted
        assertNotNull("Deviating off route must emit Aborted", abortedOff)
        assertEquals(SectionAbortReason.OFF_CORRIDOR, abortedOff!!.reason)
        assertFalse(trackerA.isInCorridor)

        // Abort Scenario B: Timeout (> 2 hours)
        val trackerB = AverageSpeedTracker(SectionQuery { listOf(corridor) })
        trackerB.onFix(fix(startPos, headingDeg = 0.0, timeMs = 1_000L))
        assertTrue(trackerB.isInCorridor)

        val timeoutFix = fix(offset(startPos, metresNorth = 500.0, metresEast = 0.0), headingDeg = 0.0, timeMs = 8_000_000L)
        val abortedTimeout = trackerB.onFix(timeoutFix) as? SectionEvent.Aborted
        assertNotNull("Excessive duration must emit Aborted", abortedTimeout)
        assertEquals(SectionAbortReason.TIMEOUT, abortedTimeout!!.reason)
        assertFalse(trackerB.isInCorridor)

        // Abort Scenario C: GPS Lost signal mid-zone
        val trackerC = AverageSpeedTracker(SectionQuery { listOf(corridor) })
        trackerC.onFix(fix(startPos, headingDeg = 0.0, timeMs = 1_000L))
        assertTrue(trackerC.isInCorridor)

        val abortedGps = trackerC.onGpsLost()
        assertNotNull("Signal loss must abort active corridor", abortedGps)
        assertEquals(SectionAbortReason.GPS_LOST, abortedGps!!.reason)
        assertFalse(trackerC.isInCorridor)
    }

    @Test
    fun test06_detectionPipelineEndToEndIntegrationOnDevice() {
        val pipeline =
            DetectionPipeline(
                query = CameraQuery { emptyList() },
                sections = SectionQuery { listOf(corridor) },
            )
        val profile = AlertProfile()
        val alertContext = AlertContext()

        // 1. Approach fix
        val approachFix = fix(offset(startPos, -150.0, 0.0), headingDeg = 0.0, timeMs = 1_000L)
        val res1 = pipeline.process(approachFix, profile, previous = null, context = alertContext)
        assertNull(res1.sectionEvent)

        // 2. Entry fix
        val entryFix = fix(startPos, headingDeg = 0.0, timeMs = 8_000L)
        val res2 = pipeline.process(entryFix, profile, previous = res1.fix, context = alertContext)
        val entryEvent = res2.sectionEvent as? SectionEvent.Entered
        assertNotNull("Pipeline must output Entered sectionEvent", entryEvent)
        assertTrue(res2.hasAlerts)

        // 3. Progress fix
        val midFix = fix(offset(startPos, 500.0, 0.0), headingDeg = 0.0, timeMs = 28_000L)
        val res3 = pipeline.process(midFix, profile, previous = res2.fix, context = alertContext)
        val progressEvent = res3.sectionEvent as? SectionEvent.Progress
        assertNotNull("Pipeline must output Progress sectionEvent", progressEvent)

        // 4. Exit fix
        val exitFix = fix(endPos, headingDeg = 0.0, timeMs = 48_000L)
        val res4 = pipeline.process(exitFix, profile, previous = res3.fix, context = alertContext)
        val exitEvent = res4.sectionEvent as? SectionEvent.Exited
        assertNotNull("Pipeline must output Exited sectionEvent", exitEvent)
        assertTrue(res4.hasAlerts)
    }

    @Test
    fun test07_mediaAlertEngineSectionAlertsOnDeviceHardware() {
        val mediaEngine = MediaAlertEngine(context)
        try {
            val profile = AlertProfile(voice = true, beepEnabled = true, vibro = true)

            val enteredEvent = SectionEvent.Entered(corridor, entryTimeMillis = 10_000L, entryOdometerM = 0.0)
            mediaEngine.playSectionEvent(enteredEvent, profile)

            val exitedEvent =
                SectionEvent.Exited(
                    section = corridor,
                    durationMillis = 40_000L,
                    distanceM = 1_000.0,
                    averageKmh = 90.0,
                    excessKmh = 30.0,
                )
            mediaEngine.playSectionEvent(exitedEvent, profile)
        } finally {
            mediaEngine.release()
        }
    }

    @Test
    fun test08_uiStateTransformationAndExcessHighlightOnDevice() {
        val enteredEvent = SectionEvent.Entered(corridor, entryTimeMillis = 10_000L, entryOdometerM = 0.0)
        val uiEntered = enteredEvent.toSectionUiState()
        assertNotNull(uiEntered)
        assertEquals(60, uiEntered!!.limitKmh)
        assertEquals(0.0, uiEntered.averageKmh, 0.01)
        assertFalse(uiEntered.overLimit)
        assertFalse(uiEntered.completed)

        // Speeding in progress
        val speedingProgress =
            SectionEvent.Progress(
                section = corridor,
                elapsedMillis = 20_000L,
                traveledM = 500.0,
                remainingM = 500.0,
                averageKmh = 90.0,
                excessKmh = 30.0,
            )
        val uiProgress = speedingProgress.toSectionUiState()
        assertNotNull(uiProgress)
        assertEquals(90.0, uiProgress!!.averageKmh, 0.01)
        assertTrue("Speeding average must set overLimit=true", uiProgress.overLimit)

        // Updating DetectionState
        DetectionState.update { it.copy(activeSection = uiProgress) }
        assertTrue("Global state must reflect overLimit for HUD red styling", DetectionState.state.value.overLimit)

        // Normal exit
        val exitEvent = SectionEvent.Exited(corridor, durationMillis = 40_000L, distanceM = 1_000.0, averageKmh = 55.0, excessKmh = 0.0)
        val uiExit = exitEvent.toSectionUiState()
        assertNotNull(uiExit)
        assertTrue(uiExit!!.completed)
        assertFalse(uiExit.overLimit)

        DetectionState.update { it.copy(activeSection = uiExit) }
        assertFalse("Exit under limit should not be overLimit", DetectionState.state.value.overLimit)
    }

    private fun fix(
        pos: LatLon,
        headingDeg: Double,
        timeMs: Long,
        speedMs: Float = 25f,
    ): Fix =
        Fix(
            position = pos,
            accuracyM = 5f,
            speedMs = speedMs,
            headingDeg = headingDeg,
            timestampMillis = timeMs,
            providerTag = Fix.FUSED_TAG,
        )

    private fun offset(
        origin: LatLon,
        metresNorth: Double,
        metresEast: Double,
    ): LatLon {
        val dLat = metresNorth / 111_139.0
        val dLon = metresEast / (111_139.0 * cos(Math.toRadians(origin.lat)))
        return LatLon(origin.lat + dLat, origin.lon + dLon)
    }
}
