package com.comedyplatform.mictracker.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.comedyplatform.mictracker.core.rules.GeoPoint
import com.comedyplatform.mictracker.core.rules.GeoRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turns a mic's address into map coordinates with the phone's built-in
 * geocoder (android.location.Geocoder — no API key).
 *
 * Spec 6.8 / 6.9:
 *   - try "{address}, {borough}, New York, NY", then "{address}, New York, NY"
 *   - accept only results inside NYC (rule 18)
 *   - at most one request per second (enforced here for every caller)
 *
 * All functions are safe to call from the main thread and never throw:
 * "no result" and "geocoder unavailable/failed" both return null.
 */
class GeocodingService(context: Context) {

    private val appContext = context.applicationContext

    /** Serializes requests so the 1-per-second limit holds across callers. */
    private val rateLimitMutex = Mutex()
    private var lastRequestAtMillis = 0L

    /** False on devices without a geocoding backend (some emulators, de-Googled phones). */
    val isAvailable: Boolean get() = Geocoder.isPresent()

    /**
     * Coordinates for a mic, or null. Tries the borough-qualified query first,
     * then the plain one.
     */
    suspend fun geocodeMic(address: String?, borough: String?): GeoPoint? {
        if (!isAvailable) return null
        for (query in GeoRules.geocodeQueries(address, borough)) {
            val point = geocodeQuery(query)
            if (point != null) return point
        }
        return null
    }

    /** One rate-limited lookup of [query]; returns the first NYC result or null. */
    suspend fun geocodeQuery(query: String): GeoPoint? = rateLimitMutex.withLock {
        val waitMillis = MIN_INTERVAL_MILLIS - (System.currentTimeMillis() - lastRequestAtMillis)
        if (waitMillis > 0) delay(waitMillis)
        try {
            lookup(query)
                .asSequence()
                .filter { it.hasLatitude() && it.hasLongitude() }
                .map { GeoPoint(it.latitude, it.longitude) }
                .firstOrNull { GeoRules.isInNyc(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException (no network / backend), IllegalArgumentException, ...
            null
        } finally {
            lastRequestAtMillis = System.currentTimeMillis()
        }
    }

    private suspend fun lookup(query: String): List<Address> {
        val geocoder = Geocoder(appContext, Locale.US)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: asynchronous listener API.
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(
                    query,
                    MAX_RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.toList())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    },
                )
            }
        } else {
            // API 26–32: the blocking call, run off the main thread.
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, MAX_RESULTS)?.toList() ?: emptyList()
            }
        }
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 1000L
        const val MAX_RESULTS = 3
    }
}
