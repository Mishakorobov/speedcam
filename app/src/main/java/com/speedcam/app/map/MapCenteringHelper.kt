package com.speedcam.app.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.speedcam.core.detect.Camera
import com.speedcam.core.geo.LatLon

/**
 * Resolves map center coordinates dynamically using a fallback hierarchy:
 * 1. Active GPS fix from the foreground detection service.
 * 2. Last known location from the platform [LocationManager].
 * 3. Centroid of the loaded camera database cluster.
 * 4. Default fallback (Moscow).
 */
object MapCenteringHelper {
    const val DEFAULT_FALLBACK_LAT = 55.7558
    const val DEFAULT_FALLBACK_LON = 37.6173

    fun findInitialCenter(
        context: Context,
        uiLat: Double?,
        uiLon: Double?,
        cameras: List<Camera>,
    ): Pair<LatLon, Boolean> {
        // 1. Live fix from service
        if (uiLat != null && uiLon != null) {
            return LatLon(uiLat, uiLon) to true
        }

        // 2. Platform last-known location
        getLastKnownLocation(context)?.let {
            return it to true
        }

        // 3. Camera cluster centroid from Room database
        findCameraClusterCentroid(cameras)?.let {
            return it to true
        }

        // 4. Default fallback
        return LatLon(DEFAULT_FALLBACK_LAT, DEFAULT_FALLBACK_LON) to false
    }

    fun getLastKnownLocation(context: Context): LatLon? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val hasFine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return null

        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val best = listOfNotNull(gps, net).maxByOrNull { it.time }
        return best?.let { LatLon(it.latitude, it.longitude) }
    }

    fun findCameraClusterCentroid(cameras: List<Camera>): LatLon? {
        if (cameras.isEmpty()) return null
        val sample = if (cameras.size > 2000) cameras.take(2000) else cameras
        val avgLat = sample.map { it.position.lat }.average()
        val avgLon = sample.map { it.position.lon }.average()
        return LatLon(avgLat, avgLon)
    }
}
