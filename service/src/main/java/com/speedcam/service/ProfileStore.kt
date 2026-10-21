package com.speedcam.service

import android.content.Context
import android.content.SharedPreferences
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile
import com.speedcam.core.media.OutputMode

/**
 * Persists what the driver has chosen about detection.
 *
 * Backed by SharedPreferences deliberately. The alternatives in the catalog — DataStore,
 * Room — are both fine and both heavier than a handful of scalars, and profile persistence is
 * not where this project is spending its complexity budget. Stage 6 owns the settings UI and
 * may well replace this.
 *
 * The enabled flag matters beyond the settings screen: it is what the boot receiver consults
 * to decide whether resurrecting the service is wanted at all, so a driver who switched
 * detection off does not get it started again by an alarm they set weeks earlier.
 */
class ProfileStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The chosen preset. Preset 0, Maximum alerts, is the original's factory default. */
    var quickSetting: QuickSetting
        get() =
            QuickSetting.entries
                .getOrElse(prefs.getInt(KEY_QUICK_SETTING, 0)) { QuickSetting.MAXIMUM_ALERTS }
        set(value) = prefs.edit().putInt(KEY_QUICK_SETTING, value.ordinal).apply()

    /** The driver's transport choice; a headset may still override it. See [TransportProfile]. */
    var transport: TransportProfile
        get() =
            TransportProfile.entries
                .getOrElse(prefs.getInt(KEY_TRANSPORT, TransportProfile.COMMON.ordinal)) {
                    TransportProfile.COMMON
                }
        set(value) = prefs.edit().putInt(KEY_TRANSPORT, value.ordinal).apply()

    /**
     * Capture distance in kilometres; 0 is "Auto".
     *
     * Kept out of the preset so that switching presets never moves the warning point for a
     * driver who had tuned it.
     */
    var captureDistanceKm: Double
        get() = prefs.getFloat(KEY_CAPTURE_DISTANCE_KM, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_CAPTURE_DISTANCE_KM, value.toFloat()).apply()

    /** Whether the floating HUD is wanted. Separate from the overlay permission, which is the system's. */
    var hudEnabled: Boolean
        get() = prefs.getBoolean(KEY_HUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_ENABLED, value).apply()

    /** Auto-dim floating HUD overlay when road is clear and no hazard is nearby. */
    var hudAutoHide: Boolean
        get() = prefs.getBoolean(KEY_HUD_AUTO_HIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_HUD_AUTO_HIDE, value).apply()

    /** How many cameras the last import found, shown in the settings and the list header. */
    var knownCameraCount: Int
        get() = prefs.getInt(KEY_CAMERA_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_CAMERA_COUNT, value).apply()

    /** Audio output routing mode (standard media, speaker, BT multimedia, BT HFP). */
    var outputMode: OutputMode
        get() =
            OutputMode.entries
                .getOrElse(prefs.getInt(KEY_OUTPUT_MODE, OutputMode.STANDARD.ordinal)) {
                    OutputMode.STANDARD
                }
        set(value) = prefs.edit().putInt(KEY_OUTPUT_MODE, value.ordinal).apply()

    /** The "Silent during phone call" setting. Defaults to false, as in the original. */
    var silentDuringCall: Boolean
        get() = prefs.getBoolean(KEY_SILENT_DURING_CALL, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_DURING_CALL, value).apply()

    /** Whether weekly background database updates via WorkManager are enabled. */
    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, value).apply()

    /** ISO timestamp when database updates were last checked. */
    var lastUpdateCheckAt: String?
        get() = prefs.getString(KEY_LAST_UPDATE_CHECK_AT, null)
        set(value) = prefs.edit().putString(KEY_LAST_UPDATE_CHECK_AT, value).apply()

    /** Status or error message of the last sync operation. */
    var lastUpdateStatus: String?
        get() = prefs.getString(KEY_LAST_UPDATE_STATUS, null)
        set(value) = prefs.edit().putString(KEY_LAST_UPDATE_STATUS, value).apply()

    /** Custom database update URL (manifest or direct database). Empty means default. */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value).apply()

    /** Remote endpoint for crowdsourced hazard submissions. Empty means default service. */
    var hazardReportUrl: String
        get() = prefs.getString(KEY_HAZARD_REPORT_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HAZARD_REPORT_URL, value).apply()

    /** ISO timestamp when the hazard outbox was last synchronized. */
    var lastHazardSyncAt: String?
        get() = prefs.getString(KEY_LAST_HAZARD_SYNC_AT, null)
        set(value) = prefs.edit().putString(KEY_LAST_HAZARD_SYNC_AT, value).apply()

    /** Status or message from the last hazard outbox sync attempt. */
    var lastHazardSyncStatus: String?
        get() = prefs.getString(KEY_LAST_HAZARD_SYNC_STATUS, null)
        set(value) = prefs.edit().putString(KEY_LAST_HAZARD_SYNC_STATUS, value).apply()

    /** Whether to use the proprietary ContraCam binary archive database instead of default OSM/SpeedCam. */
    var useContraCamDb: Boolean
        get() = prefs.getBoolean(KEY_USE_CONTRACAM_DB, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_CONTRACAM_DB, value).apply()

    /** ISO timestamp when ContraCam binary archive was last downloaded/imported. */
    var lastContraCamSyncAt: String?
        get() = prefs.getString(KEY_LAST_CONTRACAM_SYNC_AT, null)
        set(value) = prefs.edit().putString(KEY_LAST_CONTRACAM_SYNC_AT, value).apply()

    /** Status or message from the last ContraCam archive sync operation. */
    var lastContraCamStatus: String?
        get() = prefs.getString(KEY_LAST_CONTRACAM_STATUS, null)
        set(value) = prefs.edit().putString(KEY_LAST_CONTRACAM_STATUS, value).apply()

    /** Number of cameras imported from ContraCam binary archive. */
    var contraCamCameraCount: Int
        get() = prefs.getInt(KEY_CONTRACAM_CAMERA_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_CONTRACAM_CAMERA_COUNT, value).apply()

    /** Auto-start radar detection when car Bluetooth connects. */
    var autoStartOnBluetooth: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_BLUETOOTH, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ON_BLUETOOTH, value).apply()

    /** Auto-stop radar detection when car Bluetooth disconnects. */
    var autoStopOnBluetooth: Boolean
        get() = prefs.getBoolean(KEY_AUTO_STOP_ON_BLUETOOTH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_STOP_ON_BLUETOOTH, value).apply()

    /** Bluetooth device filter MAC address or identifier; empty means any car audio device. */
    var bluetoothDeviceFilter: String
        get() = prefs.getString(KEY_BLUETOOTH_DEVICE_FILTER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BLUETOOTH_DEVICE_FILTER, value).apply()

    /** User-friendly display name of the selected Bluetooth device filter. */
    var bluetoothDeviceFilterName: String
        get() = prefs.getString(KEY_BLUETOOTH_DEVICE_FILTER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BLUETOOTH_DEVICE_FILTER_NAME, value).apply()

    /** Grace period in seconds to wait before stopping detection after BT disconnects. */
    var bluetoothStopDelaySeconds: Int
        get() = prefs.getInt(KEY_BLUETOOTH_STOP_DELAY_SECONDS, DEFAULT_STOP_DELAY_SECONDS)
        set(value) = prefs.edit().putInt(KEY_BLUETOOTH_STOP_DELAY_SECONDS, value).apply()

    /**
     * The profile detection runs under right now.
     *
     * Rebuilt from the stored choices on every call rather than cached, so that changing a
     * setting takes effect on the next fix without the service having to be told.
     *
     * @param headsetConnected whether a Bluetooth hands-free device is connected, which
     *   switches the Common profile to the Car one.
     */
    fun profile(headsetConnected: Boolean = false): AlertProfile {
        val base =
            AlertProfile(
                captureDistanceKm = captureDistanceKm,
                // A connected hands-free device implies a car, which switches the Common
                // profile to the Car one. An explicit non-Common choice is never overridden.
                transport = TransportProfile.select(transport, headsetConnected),
            )
        return quickSetting.apply(base)
    }

    companion object {
        const val NAME = "speedcam-profile"
        const val KEY_ENABLED = "enabled"
        const val KEY_QUICK_SETTING = "quickSetting"
        const val KEY_TRANSPORT = "transport"
        const val KEY_CAPTURE_DISTANCE_KM = "captureDistanceKm"
        const val KEY_SILENT_DURING_CALL = "silentDuringCall"
        const val KEY_CAMERA_COUNT = "cameraCount"
        const val KEY_OUTPUT_MODE = "outputMode"
        const val KEY_HUD_ENABLED = "hudEnabled"
        const val KEY_HUD_AUTO_HIDE = "hudAutoHide"
        const val KEY_AUTO_UPDATE_ENABLED = "autoUpdateEnabled"
        const val KEY_LAST_UPDATE_CHECK_AT = "lastUpdateCheckAt"
        const val KEY_LAST_UPDATE_STATUS = "lastUpdateStatus"
        const val KEY_UPDATE_URL = "updateUrl"
        const val KEY_HAZARD_REPORT_URL = "hazardReportUrl"
        const val KEY_LAST_HAZARD_SYNC_AT = "lastHazardSyncAt"
        const val KEY_LAST_HAZARD_SYNC_STATUS = "lastHazardSyncStatus"
        const val KEY_USE_CONTRACAM_DB = "useContraCamDb"
        const val KEY_LAST_CONTRACAM_SYNC_AT = "lastContraCamSyncAt"
        const val KEY_LAST_CONTRACAM_STATUS = "lastContraCamStatus"
        const val KEY_CONTRACAM_CAMERA_COUNT = "contraCamCameraCount"
        const val KEY_AUTO_START_ON_BLUETOOTH = "autoStartOnBluetooth"
        const val KEY_AUTO_STOP_ON_BLUETOOTH = "autoStopOnBluetooth"
        const val KEY_BLUETOOTH_DEVICE_FILTER = "bluetoothDeviceFilter"
        const val KEY_BLUETOOTH_DEVICE_FILTER_NAME = "bluetoothDeviceFilterName"
        const val KEY_BLUETOOTH_STOP_DELAY_SECONDS = "bluetoothStopDelaySeconds"
        const val DEFAULT_STOP_DELAY_SECONDS = 20
    }
}
