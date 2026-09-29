package com.itantra.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import com.itantra.app.link.GeoFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Best-effort location for an SOS, using the framework LocationManager (no
 * Play Services, no network service). Never blocks for long and never invents
 * coordinates: a recent cached fix, else a fresh fix within [timeoutMs], else a
 * cached fix up to [FALLBACK_MAX_AGE_MS] old, else no location.
 */
class SosLocator(context: Context, private val timeoutMs: Long = 5_000) {

    sealed interface Result {
        data class Found(val fix: GeoFix, val source: String) : Result

        data class Unavailable(val reason: String) : Result
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    suspend fun locate(): Result {
        if (!hasPermission()) return Result.Unavailable("location permission denied")
        if (manager == null) return Result.Unavailable("no location service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !manager.isLocationEnabled) {
            return Result.Unavailable("location is turned off")
        }
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        if (providers.isEmpty()) return Result.Unavailable("no location provider enabled")
        val cached = providers.mapNotNull { p ->
            try {
                manager.getLastKnownLocation(p)?.toFix()
            } catch (_: Exception) {
                null
            }
        }
        val now = System.currentTimeMillis()
        pickFix(cached, now, RECENT_MAX_AGE_MS)?.let { return Result.Found(it, "recent cached fix") }
        val fresh = withTimeoutOrNull(timeoutMs) { currentFix(providers) }
        if (fresh != null) return Result.Found(fresh, "current fix")
        pickFix(cached, System.currentTimeMillis(), FALLBACK_MAX_AGE_MS)?.let { return Result.Found(it, "older cached fix") }
        return Result.Unavailable("no location fix within ${timeoutMs / 1000} s")
    }

    /** First fix any enabled provider delivers; cancelled (and its requests removed) on timeout. */
    @SuppressLint("MissingPermission")
    private suspend fun currentFix(providers: List<String>): GeoFix? = suspendCancellableCoroutine { cont ->
        val done = { location: Location? ->
            if (location != null && cont.isActive) location.toFix()?.let { cont.resume(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            providers.forEach { p ->
                try {
                    manager!!.getCurrentLocation(p, signal, app.mainExecutor) { done(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation($p) failed: $e")
                }
            }
            cont.invokeOnCancellation { signal.cancel() }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = done(location)

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            providers.forEach { p ->
                try {
                    manager!!.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Log.w(TAG, "requestLocationUpdates($p) failed: $e")
                }
            }
            cont.invokeOnCancellation { manager!!.removeUpdates(listener) }
        }
    }

    companion object {
        private const val TAG = "Sos"
        const val RECENT_MAX_AGE_MS = 2 * 60_000L
        const val FALLBACK_MAX_AGE_MS = 30 * 60_000L

        /** The newest valid fix no older than [maxAgeMs] at [nowMs] (a little clock skew into the future is tolerated). */
        fun pickFix(fixes: List<GeoFix>, nowMs: Long, maxAgeMs: Long): GeoFix? = fixes
            .filter { it.isValid && it.fixTimeMs != null && nowMs - it.fixTimeMs in -60_000L..maxAgeMs }
            .maxByOrNull { it.fixTimeMs!! }

        private fun Location.toFix(): GeoFix? =
            GeoFix(latitude, longitude, time, if (hasAccuracy()) accuracy.toInt() else null).takeIf { it.isValid }
    }
}
