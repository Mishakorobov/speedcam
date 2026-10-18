package com.speedcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.speedcam.core.detect.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads positions from [FusedLocationProviderClient].
 *
 * Plan.md §3.3 names this the primary source, with [LocationManagerSource] as the fallback for
 * devices without Play Services. The fused provider is preferred because it arbitrates between
 * the GPS and network sensors upstream, which is why fixes from here carry the `fused` tag and
 * win ties in [FixArbiter].
 */
class FusedLocationSource(
    private val client: FusedLocationProviderClient,
) : LocationSource {
    @RequiresPermission(
        anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION],
    )
    override fun fixes(): Flow<Fix> =
        callbackFlow {
            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (location in result.locations) {
                            // Tainted by the fused provider, unlike a raw LocationManager fix.
                            trySend(location.toFix().copy(providerTag = FUSED_TAG))
                        }
                    }
                }

            val request =
                LocationRequest
                    .Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                    // A GPS-derived speed is far steadier than one derived from position
                    // differencing, and the "Auto" capture distance is a function of speed.
                    .setMinUpdateDistanceMeters(0f)
                    .build()

            client.requestLocationUpdates(request, callback, mainLooper).await()

            awaitClose { client.removeLocationUpdates(callback) }
        }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val MIN_UPDATE_INTERVAL_MS = 500L

        val mainLooper: android.os.Looper get() = android.os.Looper.getMainLooper()
    }
}

/** The provider tag marking a fix as already arbitrated upstream. */
const val FUSED_TAG = "fused"

/**
 * Picks a location source for this device.
 *
 * Falls back rather than failing: a device without Play Services is still perfectly capable of
 * warning about cameras, just less smoothly, and refusing to run there would be a worse
 * outcome than a noisier signal.
 *
 * @throws SecurityException if location permission has not been granted. Callers check
 *   [hasLocationPermission] first; a foreground service without the permission cannot run.
 */
@SuppressLint("MissingPermission") // DetectionService checks hasLocationPermission before building the source.
fun locationSource(context: Context): LocationSource {
    val fused = runCatching { FusedLocationSource(LocationServices.getFusedLocationProviderClient(context)) }
    return fused.getOrNull()
        ?: LocationManagerSource(context.getSystemService(LocationManager::class.java))
}

/** True when either precise or approximate location has been granted. */
fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** True when background location has been granted, which needs a separate request on API 29+. */
fun hasBackgroundLocationPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Awaits a Play Services [com.google.android.gms.tasks.Task].
 *
 * Written out rather than pulled in from `kotlinx-coroutines-play-services`, which exists
 * purely to make this one call. The interop artifact is not worth a dependency.
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWith(Result.failure(it)) }
        addOnCanceledListener { continuation.cancel() }
    }
