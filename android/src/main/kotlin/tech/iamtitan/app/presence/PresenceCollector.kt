package tech.iamtitan.app.presence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds one [PresenceSample] from the phone's senses, honoring BOTH the opt-in [PresenceSettings]
 * AND the OS runtime grants. **AOSP `LocationManager` only — never FusedLocationProvider** (that
 * lives in Google Play Services, which AD-7 forbids and GrapheneOS doesn't have). Location uses
 * last-known fixes across the AOSP providers (battery-light for a periodic sampler); time/battery
 * are local. Returns null when nothing is collectable this cycle (no fields → backend stores
 * nothing). No cognition — pure collection (AG8).
 */
object PresenceCollector {

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    fun collect(context: Context, settings: PresenceSettings): PresenceSample? {
        var lat: Double? = null
        var lon: Double? = null
        var accuracy: Double? = null
        var tz: String? = null
        var localTime: String? = null
        var battery: Int? = null

        if (settings.locationEnabled && hasLocationPermission(context)) {
            lastKnown(context)?.let {
                lat = it.first
                lon = it.second
                accuracy = it.third
            }
        }
        if (settings.timeEnabled) {
            tz = ZoneId.systemDefault().id
            localTime = LocalTime.now().format(HHMM)
        }
        if (settings.batteryEnabled) {
            battery = batteryPercent(context)
        }

        if (lat == null && tz == null && battery == null) return null
        return PresenceSample(
            lat = lat, lon = lon, accuracy = accuracy, tz = tz, localTime = localTime,
            battery = battery, ts = System.currentTimeMillis() / 1000.0,
        )
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Most-recent last-known fix across the AOSP providers. (lat, lon, accuracy). */
    private fun lastKnown(context: Context): Triple<Double, Double, Double?>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: android.location.Location? = null
        for (p in providers) {
            val loc = try {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        return best?.let {
            Triple(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null)
        }
    }

    private fun batteryPercent(context: Context): Int? {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }
}
