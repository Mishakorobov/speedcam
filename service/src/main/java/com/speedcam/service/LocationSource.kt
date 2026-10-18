package com.speedcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.speedcam.core.detect.Fix
import com.speedcam.core.geo.LatLon
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A stream of positions for the detection pipeline.
 *
 * Emits the domain [Fix] rather than an Android [Location] so that everything downstream of
 * the provider is testable without a device, and so the translation from platform types
 * happens in exactly one place.
 */
interface LocationSource {
    /**
     * Starts emitting. The flow is cold: collecting is what turns the receiver on, and
     * cancelling the collector turns it off again.
     */
    fun fixes(): Flow<Fix>

    companion object {
        /** For devices or emulator images with no location hardware. */
        fun empty(): LocationSource =
            object : LocationSource {
                override fun fixes(): Flow<Fix> = emptyFlow()
            }
    }
}

/**
 * Reads positions from the platform [LocationManager].
 *
 * The fallback for devices without Play Services, and the only thing that works on an emulator
 * image with no Google APIs. Both GPS and network providers are requested: GPS alone gives a
 * fix every few seconds at best and drops out under tree cover, while the network provider is
 * far coarser but arrives more often. [FixArbiter] is what makes combining them safe.
 */
class LocationManagerSource(
    private val manager: LocationManager,
) : LocationSource {
    @RequiresPermission(
        anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION],
    )
    override fun fixes(): Flow<Fix> =
        callbackFlow {
            val listener = LocationListener { location -> trySend(location.toFix()) }

            PROVIDERS.forEach { provider ->
                // A last known location seeds the stream so the first detection is not delayed
                // by however long the first live fix takes to arrive.
                runCatching { lastKnown(provider) }
                    .getOrNull()
                    ?.let { trySend(it.toFix()) }

                // A missing last known location is not a reason to skip the provider: it may
                // still produce live fixes.
                runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        MIN_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            }

            awaitClose { runCatching { manager.removeUpdates(listener) } }
        }

    /**
     * Permission is already held; the class-level annotation exists so that every *caller* has
     * to prove it rather than this method trusting its own comment.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnown(provider: String): android.location.Location? = manager.getLastKnownLocation(provider)

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val MIN_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_M = 0f
    }
}

/**
 * Converts a platform location into the domain type.
 *
 * The provider string is kept as the tag rather than discarded. [FixArbiter] gives a fix that
 * has already been arbitrated upstream authority over one that has not, and only Play Services
 * can honestly be marked as such — a raw `LocationManager` fix from a single provider must not
 * claim the same standing.
 */
fun Location.toFix(): Fix =
    Fix(
        position = LatLon(latitude, longitude),
        timestampMillis = time,
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        speedMs = if (hasSpeed()) speed else 0f,
        headingDeg = if (hasBearing()) bearing.toDouble() else 0.0,
        altitudeM = if (hasAltitude()) altitude else 0.0,
        providerTag = provider,
    )
