package com.speedcam.service

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.speedcam.core.data.CameraStore
import com.speedcam.core.detect.AlertContext
import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.Fix
import com.speedcam.core.detect.SectionEvent
import com.speedcam.core.media.MediaAlertEngine
import com.speedcam.core.media.OutputMode
import com.speedcam.service.wear.WearCompanionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * The foreground service that owns the location pipeline.
 *
 * Stage 0 delivered the shell and the notification channel; this attaches the provider, runs
 * the chain from fix to alert, and applies the watchdogs from Plan.md §8.1.
 *
 * Two lifecycle decisions are deliberate:
 *
 *  - `startForeground()` is called first and unconditionally in [onStartCommand], ahead of the
 *    permission check and of opening the database. Android requires it within five seconds of
 *    `startForegroundService()`; everything slow happens afterwards. A service that misses that
 *    window is killed with `ForegroundServiceDidNotStartInTimeException`, which on Android 12
 *    and later also counts as a foreground-start restriction violation against the whole app.
 *  - `START_STICKY`, so a low-memory kill is recovered from. [Resurrection] covers the two
 *    cases the system will not restart on its own: a reboot and a process reclaimed outright.
 */
class DetectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val gpsWatchdog = GpsLostWatchdog()
    private val abuseWatchdog = BackgroundAbuseWatchdog()
    private val adaptiveWatchdog = AdaptiveLocationWatchdog()
    private val profiles by lazy { ProfileStore(this) }
    private val gpsLostText by lazy { getString(R.string.notification_gps_lost) }

    private var lastAcceptedFix: Fix? = null
    private var store: CameraStore? = null
    private var activePipeline: DetectionPipeline? = null
    private var locationSource: LocationSource? = null
    private var visibility: AppVisibility? = null
    private var collecting = false
    private var fixesSeen = 0L

    /** Stage 5: turns decisions into sound and vibration. Created on first use. */
    private var mediaEngine: MediaAlertEngine? = null

    private val media: MediaAlertEngine
        get() = mediaEngine ?: MediaAlertEngine(this).also { mediaEngine = it }

    /** What the notification currently says. Only this class writes it. */
    private var notificationState: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationState = getString(R.string.notification_text)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // First, and unconditionally, before anything that might block.
        promoteToForeground(buildNotification(notificationState))

        if (intent?.action == ACTION_RELOAD_DATABASE) {
            scope.launch { reloadDatabase() }
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP || !profiles.isEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission(this)) {
            // Nothing can be detected without location. Stopping is honest; carrying on would
            // show an active-looking notification for an app that cannot possibly work.
            stopSelf()
            return START_NOT_STICKY
        }

        Resurrection.schedule(this)
        registerVisibilityWatch()
        startCollecting()

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        visibility?.unregister()
        visibility = null
        if (mediaEngine != null) mediaEngine?.release()
        scope.cancel()
        locationSource = null
        adaptiveWatchdog.reset()
        store?.close()
        store = null
        DetectionState.update { it.copy(running = false, hasFix = false) }
        CarLauncherBroadcaster.broadcast(this, DetectionState.state.value, profiles)
        WearCompanionBridge.broadcast(this, DetectionState.state.value, profiles, force = true)
        super.onDestroy()
    }

    /**
     * The output mode alerts are routed through.
     *
     * Reads the Bluetooth state the same way [TransportProfile.select] does for the warning
     * profile: the service is the only component entitled to know about audio routing, so the
     * headset presence is resolved here rather than guessed at in the media layer.
     */
    private fun currentOutputMode(): OutputMode {
        val configured = profiles.outputMode
        if (configured != OutputMode.STANDARD) {
            return configured
        }
        return if (hasConnectedAudioDevice(this)) {
            OutputMode.BLUETOOTH_MULTIMEDIA
        } else {
            OutputMode.STANDARD
        }
    }

    private fun micInUse(manager: android.media.AudioManager): Boolean =
        runCatching { manager.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false)

    /** Kept honest by [refreshCallState]; AudioManager owns the answer, not this class. */
    private fun refreshCallState() {
        val manager = getSystemService(android.media.AudioManager::class.java)
        if (manager == null) return
        media.updateCallState(
            audioMode = manager.mode,
            microphoneInUse = micInUse(manager),
            silentDuringCall = profiles.silentDuringCall,
        )
    }

    // ------------------------------------------------------------------- pipeline

    private fun startCollecting() {
        if (collecting) return
        collecting = true
        Log.i(TAG, "Pipeline starting.")

        scope.launch {
            val cameraStore = store ?: CameraStore.open(applicationContext).also { store = it }
            cameraStore.useContraCamDb = profiles.useContraCamDb
            // The driver's blocks and active SPECS corridors must be in force before the first query.
            cameraStore.refreshOverrides()
            cameraStore.refreshSections()
            val pipeline = DetectionPipeline(cameraStore.cameraQuery(), cameraStore.sectionQuery())
            activePipeline = pipeline
            publishState(cameraStore)
            val source = locationSource(this@DetectionService)
            locationSource = source

            // Armed before the first fix is awaited, so that a provider which never delivers
            // anything is still reported after the timeout.
            gpsWatchdog.start(System.currentTimeMillis())
            Log.i(TAG, "Location source: ${source::class.java.simpleName}. Awaiting first fix.")

            source.fixes().collect { fix -> onFix(fix, pipeline) }
        }

        // The GPS watchdog runs on its own tick rather than off incoming fixes, because a total
        // loss of signal means no fixes arrive to notice it on.
        scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                refreshNotification()
            }
        }
    }

    /** Reloads the camera database queries and active pipeline after an update. */
    private suspend fun reloadDatabase() {
        val currentStore = store ?: CameraStore.open(applicationContext).also { store = it }
        currentStore.useContraCamDb = profiles.useContraCamDb
        currentStore.refreshOverrides()
        currentStore.refreshSections()
        activePipeline = DetectionPipeline(currentStore.cameraQuery(), currentStore.sectionQuery())
        publishState(currentStore)
        Log.i(
            TAG,
            "Detection database reloaded: ${currentStore.cameraCount()} cameras, " +
                "${currentStore.sectionCount()} sections.",
        )
    }

    /** Pushes the current situation to the interface. */
    private fun publishHazards(
        hazards: List<com.speedcam.core.detect.Hazard>,
        fix: Fix,
        sectionEvent: SectionEvent?,
    ) {
        val views = hazards.toHazardViews()
        val now = System.currentTimeMillis()
        DetectionState.update { previous ->
            val nextSection =
                when {
                    sectionEvent != null -> sectionEvent.toSectionUiState(now)
                    previous.activeSection?.completed == true -> {
                        val completedAt = previous.activeSection.completedAtMs ?: 0L
                        if (now - completedAt < COMPLETED_SECTION_LINGER_MS) {
                            previous.activeSection
                        } else {
                            null
                        }
                    }
                    else -> previous.activeSection
                }
            previous.copy(
                running = true,
                hasFix = true,
                speedKmh = fix.speedKmh,
                currentLat = fix.position.lat,
                currentLon = fix.position.lon,
                currentBearingDeg = fix.headingDeg,
                nearest = views.getOrNull(0),
                second = views.getOrNull(1),
                activeSection = nextSection,
                stationaryThrottled = adaptiveWatchdog.currentRate == PollingRate.STATIONARY,
            )
        }
        CarLauncherBroadcaster.broadcast(this, DetectionState.state.value, profiles)
        WearCompanionBridge.broadcast(this, DetectionState.state.value, profiles)
    }

    /** Pushes everything that does not change from fix to fix. */
    private suspend fun publishState(cameraStore: CameraStore) {
        val profile = profiles.profile()
        val count = runCatching { cameraStore.cameraCount() }.getOrDefault(0)
        val sections = runCatching { cameraStore.sectionCount() }.getOrDefault(0)
        val builtAt = cameraStore.databaseBuiltAt()
        // Recorded so the settings screen and the list header have a number to show. Anything
        // else leaves it at zero forever, which reads as "no cameras" rather than "unknown".
        if (count > 0) profiles.knownCameraCount = count

        DetectionState.set(
            DetectionUiState(
                running = true,
                cameraCount = count,
                sectionCount = sections,
                databaseBuiltAt = builtAt,
                profileName = profiles.quickSetting.displayName,
                headsetConnected = hasConnectedAudioDevice(this),
                muted = !profile.beepEnabled && !profile.voice && !profile.vibro,
            ),
        )
        WearCompanionBridge.broadcast(this, DetectionState.state.value, profiles, force = true)
    }

    private fun onFix(
        fix: Fix,
        pipeline: DetectionPipeline,
    ) {
        gpsWatchdog.onFix(fix.timestampMillis)
        val rate = adaptiveWatchdog.onFix(System.currentTimeMillis(), fix)
        locationSource?.setPollingRate(rate)
        refreshCallState()
        fixesSeen++
        // Rate-limited: a one-second cadence would be a line a second forever.
        if (fixesSeen <= 3 || fixesSeen % LOG_EVERY_N_FIXES == 0L) {
            Log.i(
                TAG,
                "Fix #$fixesSeen provider=${fix.providerTag} acc=${fix.accuracyM.toInt()}m " +
                    "speed=${fix.speedKmh.toInt()}km/h at ${fix.position.lat},${fix.position.lon}",
            )
        }

        val profile = profiles.profile()
        val result =
            pipeline.process(
                fix = fix,
                profile = profile,
                previous = lastAcceptedFix,
                context = AlertContext(isNight = isNight(LocalDateTime.now())),
            )
        // Updated after processing, so the next fix is compared against this one rather than
        // against itself.
        lastAcceptedFix = result.fix

        publishHazards(result.hazards, fix, result.sectionEvent)
        result.sectionEvent?.let { handleSectionEvent(it, profile) }

        if (result.hazards.isNotEmpty()) {
            Log.i(
                TAG,
                "${result.hazards.size} hazard(s), ${result.decisions.size} decision(s): " +
                    result.hazards.joinToString { h ->
                        "id=${h.camera.id} d=${h.distanceM.toInt()}m limit=${h.camera.limitKmh}"
                    },
            )
        }

// Only the nearest. Hazards arrive sorted nearest-first, and dispatching every one of
        // them would leave the notification describing whichever was furthest away — telling
        // the driver about a camera 8 km off while one 8.2 km off goes unmentioned, with the
        // numbers silently swapped for the worse.
        result.decisions.firstOrNull()?.let { nearest ->
            dispatch(nearest)

            // The alert policy has already decided what to say; the cadence latch decides
            // whether it is heard this tick, and the media engine performs it.
            val nearestHazard = result.hazards.firstOrNull()
            if (nearestHazard != null) {
                // Isolated on purpose. Detection is the safety-critical loop and audio is not:
                // a failing SoundPool or AudioTrack must not be able to stop the driver being
                // warned. Without this the whole collection dies on the first media exception.
                runCatching {
                    media.setOutputMode(currentOutputMode())
                    media.play(
                        decision = nearest,
                        hazard = nearestHazard,
                        isPrimary = true,
                        nowMillis = System.currentTimeMillis(),
                    )
                }.onFailure {
                    Log.w(TAG, "Media layer failed; detection continues: ${it.message}")
                }
            }
        }

        if (abuseWatchdog.onMoved(System.currentTimeMillis(), fix.position)) {
            // Backgrounded and stationary for > 15 min (parked): auto-shutdown to save battery.
            Log.w(TAG, "Background inactivity watchdog tripped (parked); stopping detection.")
            stopSelf()
            return
        }

        refreshNotification()
    }

    /**
     * Records that a decision was made.
     *
     * Stage 5 owns playback; until it exists this surfaces the distance and limit in the
     * ongoing notification, which is enough to verify a real drive end to end.
     */
    private fun dispatch(decision: AlertDecision) {
        val limit = decision.limitKmh?.takeIf { it > 0 }
        notificationState =
            if (limit != null) {
                getString(R.string.notification_alert, decision.distanceM.toInt(), limit)
            } else {
                // No recorded limit is not a limit of zero. Printing "0 km/h" would assert a
                // fact the database does not contain, and the driver would be right to
                // dismiss the rest of the message.
                getString(R.string.notification_alert_unknown_limit, decision.distanceM.toInt())
            }
    }

    private fun handleSectionEvent(
        event: SectionEvent,
        profile: AlertProfile,
    ) {
        when (event) {
            is SectionEvent.Entered -> {
                Log.i(
                    TAG,
                    "SPECS zone entered: limit=${event.section.limitKmh} km/h, " +
                        "length=${event.section.lengthM.toInt()} m",
                )
                notificationState = getString(R.string.notification_section_entered, event.section.limitKmh)
            }

            is SectionEvent.Exited -> {
                Log.i(
                    TAG,
                    "SPECS zone exited: avg=${event.averageKmh.toInt()} km/h, " +
                        "limit=${event.section.limitKmh} km/h, duration=${event.durationMillis} ms",
                )
                notificationState =
                    getString(
                        R.string.notification_section_exited,
                        event.averageKmh.toInt(),
                        event.section.limitKmh,
                    )
            }

            is SectionEvent.Aborted -> {
                Log.i(TAG, "SPECS zone aborted: reason=${event.reason}")
                return
            }

            is SectionEvent.Progress -> return
        }

        runCatching {
            media.setOutputMode(currentOutputMode())
            media.playSectionEvent(event, profile)
        }.onFailure {
            Log.w(TAG, "Media layer failed on section event; detection continues: ${it.message}")
        }
    }

    private fun refreshNotification() {
        when (gpsWatchdog.evaluate(System.currentTimeMillis())) {
            GpsStatus.LOST -> {
                // Logged once per loss rather than once per tick, which would be a line a second.
                if (notificationState != gpsLostText) Log.w(TAG, "GPS signal lost.")
                notificationState = gpsLostText
                if (activePipeline?.onGpsLost() != null) {
                    DetectionState.update { it.copy(activeSection = null) }
                }
            }

            GpsStatus.RECOVERED -> {
                Log.i(TAG, "GPS signal recovered.")
                notificationState = getString(R.string.notification_gps_recovered)
            }

            GpsStatus.HEALTHY -> Unit
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(notificationState))
    }

    private fun registerVisibilityWatch() {
        if (visibility != null) return
        visibility =
            AppVisibility(application) { backgrounded ->
                if (backgrounded) {
                    abuseWatchdog.onBackgrounded(System.currentTimeMillis(), lastAcceptedFix?.position)
                } else {
                    abuseWatchdog.onForegrounded()
                }
            }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun promoteToForeground(notification: Notification) {
        // On API 34+ the type must be passed explicitly; earlier versions honour the manifest
        // declaration alone and this overload does not exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_detection_name),
                // Low importance, and therefore silent: the ongoing notification must never
                // compete with the warnings the app exists to produce.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_detection_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        const val CHANNEL_ID = "detection"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.speedcam.service.action.START"
        const val ACTION_STOP = "com.speedcam.service.action.STOP"
        const val ACTION_RELOAD_DATABASE = "com.speedcam.service.action.RELOAD_DATABASE"

        private const val TAG = "DetectionService"
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val LOG_EVERY_N_FIXES = 30L
        private const val COMPLETED_SECTION_LINGER_MS = 6_000L

        /** Day begins at 07:00 by this rule. See [isNight]. */
        private const val DAY_START_HOUR = 7

        /** Night begins at 21:00 by this rule. See [isNight]. */
        private const val NIGHT_START_HOUR = 21

        /**
         * Night, for the day-only and night-only profiles.
         *
         * Fixed hours rather than a computed twilight, and deliberately so. The original stores
         * `warnTime` as 0/1/2 and evaluates it inside `libVestigoEngines.so`; its decompile
         * contains no sunrise or sunset calculation anywhere, so there is nothing here to port.
         * Inventing a civil-twilight implementation would be a guess dressed as a recovery, and
         * it would be wrong for most of the year without looking wrong at all.
         */
        fun isNight(now: LocalDateTime): Boolean = now.hour < DAY_START_HOUR || now.hour >= NIGHT_START_HOUR
    }
}

/**
 * Reports whether the app has any visible activity.
 *
 * A `Service` is told nothing about this, but the background-abuse rule needs it: the rule is
 * about the app having no visible UI while its device is moving. Counting started activities
 * is the standard way to get that answer without pulling in `androidx.lifecycle`'s process
 * lifecycle.
 */
internal class AppVisibility(
    private val application: Application,
    private val onChanged: (backgrounded: Boolean) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun unregister() {
        // The application outlives every service, so leaving this registered would keep the
        // service reachable for the life of the process.
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) onChanged(false)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) onChanged(true)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
