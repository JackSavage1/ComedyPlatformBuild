package com.comedyplatform.mictracker.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.comedyplatform.mictracker.core.rules.GeoPoint

/**
 * The map's "My location" button, kept deliberately simple (spec 6.5:
 * When-In-Use permission; the app works fine if it's denied).
 *
 * We use the platform LocationManager (no Google Play services needed):
 *   1. the freshest "last known" location from any enabled provider, else
 *   2. on Android 11+, one fresh fix via getCurrentLocation().
 * Every call is wrapped so a missing permission or provider just yields null.
 */
internal object MyLocation {

    /** Asking for both lets the user pick "precise" or "approximate" (Android 12+). */
    val permissions: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** True when either precise or approximate location is granted. */
    fun hasPermission(context: Context): Boolean =
        permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The most recent cached location, or null (no permission, location off, or none cached yet). */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): GeoPoint? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers: List<String> = try {
            manager.getProviders(true)
        } catch (e: RuntimeException) {
            emptyList()
        }
        var best: Location? = null
        for (provider in providers) {
            val location: Location? = try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null // e.g. GPS with only "approximate" permission
            } catch (e: IllegalArgumentException) {
                null
            }
            if (location != null && (best == null || location.time > best.time)) best = location
        }
        return best?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /**
     * Asks for one fresh location fix (Android 11+). [onResult] runs on the
     * main thread with the point, or null if it couldn't be found.
     */
    @SuppressLint("MissingPermission")
    fun requestCurrent(context: Context, onResult: (GeoPoint?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !hasPermission(context)) {
            onResult(null)
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            onResult(null)
            return
        }
        // Network is quick and works indoors; GPS is the fallback.
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { name ->
            try {
                manager.isProviderEnabled(name)
            } catch (e: RuntimeException) {
                false
            }
        }
        if (provider == null) {
            onResult(null)
            return
        }
        try {
            manager.getCurrentLocation(provider, null, context.mainExecutor) { location: Location? ->
                onResult(location?.let { GeoPoint(it.latitude, it.longitude) })
            }
        } catch (e: SecurityException) {
            onResult(null)
        } catch (e: IllegalArgumentException) {
            onResult(null)
        }
    }
}
