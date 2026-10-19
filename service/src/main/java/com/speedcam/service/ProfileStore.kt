package com.speedcam.service

import android.content.Context
import android.content.SharedPreferences
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile

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

    /** How many cameras the last import found, shown in the settings and the list header. */
    var knownCameraCount: Int
        get() = prefs.getInt(KEY_CAMERA_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_CAMERA_COUNT, value).apply()

    /** The "Silent during phone call" setting. Defaults to false, as in the original. */
    var silentDuringCall: Boolean
        get() = prefs.getBoolean(KEY_SILENT_DURING_CALL, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_DURING_CALL, value).apply()

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
        const val KEY_HUD_ENABLED = "hudEnabled"
    }
}
