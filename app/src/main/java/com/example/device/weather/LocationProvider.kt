package com.example.device.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

data class DeviceLocation(
  val latitude: Double,
  val longitude: Double,
  val accuracyMeters: Float? = null,
  val timestampMillis: Long = System.currentTimeMillis()
)

sealed interface LocationResult {
  data class Success(val location: DeviceLocation) : LocationResult
  object PermissionRequired : LocationResult
  object LocationDisabledOrUnavailable : LocationResult
}

class LocationProvider(private val context: Context) {
  private val locationManager =
    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  fun hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  fun getCurrentLocation(): LocationResult {
    if (!hasLocationPermission()) {
      return LocationResult.PermissionRequired
    }

    val lm = locationManager ?: return LocationResult.LocationDisabledOrUnavailable

    val providers = listOf(
      LocationManager.FUSED_PROVIDER,
      LocationManager.NETWORK_PROVIDER,
      LocationManager.GPS_PROVIDER,
      LocationManager.PASSIVE_PROVIDER
    )

    var bestLocation: Location? = null

    for (provider in providers) {
      try {
        if (lm.isProviderEnabled(provider)) {
          val loc = lm.getLastKnownLocation(provider)
          if (loc != null) {
            if (bestLocation == null || loc.time > bestLocation.time) {
              bestLocation = loc
            }
          }
        }
      } catch (_: SecurityException) {
        return LocationResult.PermissionRequired
      } catch (_: Exception) {}
    }

    return if (bestLocation != null) {
      LocationResult.Success(
        DeviceLocation(
          latitude = bestLocation.latitude,
          longitude = bestLocation.longitude,
          accuracyMeters = if (bestLocation.hasAccuracy()) bestLocation.accuracy else null,
          timestampMillis = bestLocation.time
        )
      )
    } else {
      LocationResult.LocationDisabledOrUnavailable
    }
  }
}
