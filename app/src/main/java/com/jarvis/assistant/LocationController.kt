package com.jarvis.assistant

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * Lot 4 "contrôle téléphone" : position GPS/réseau actuelle, via LocationManager (pas de
 * dépendance Google Play Services -- reste cohérent avec le projet minimal). ACCESS_FINE_LOCATION
 * est demandée à l'exécution par MainActivity avant tout appel ici.
 */
object LocationController {

    fun getCurrentLocation(
        context: Context,
        onResult: (lat: Double, lon: Double) -> Unit,
        onError: (String) -> Unit
    ) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return onError("Service de localisation indisponible sur cet appareil.")

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onError("Aucune source de localisation activée (GPS et réseau tous les deux désactivés).")
            return
        }

        // Réutilise une dernière position connue si elle date de moins de 5 minutes --
        // évite d'attendre un relevé GPS complet à chaque fois.
        val lastKnown = providers
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 5 * 60 * 1000) {
            onResult(lastKnown.latitude, lastKnown.longitude)
            return
        }

        val provider = if (providers.contains(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            providers.first()
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                onResult(location.latitude, location.longitude)
            }
        }

        try {
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            onError("Permission de localisation manquante.")
        } catch (e: Exception) {
            onError("Échec de la demande de position : ${e.message}")
        }
    }
}
