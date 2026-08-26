package com.jarvis.assistant

import android.content.Context
import android.location.Geocoder
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KmlExportController — exporte les fiches contacts (clients, ou toute
 * autre catégorie) de PeopleController vers un fichier .kml, importable
 * directement dans Google Maps ("Vos endroits" → "Cartes" → "Importer")
 * ou Google Earth.
 *
 * Les fiches sans coordonnées GPS mais avec une adresse texte sont
 * géocodées automatiquement (API Android Geocoder, nécessite Internet côté
 * service Google — c'est la même API que "ouvrir Maps" ailleurs dans
 * l'app). Une fiche sans adresse NI coordonnées est simplement ignorée
 * (impossible à placer sur une carte).
 */
object KmlExportController {

    data class Result(val success: Boolean, val message: String, val filePath: String? = null)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())

    fun exportToKml(context: Context, category: String = "client"): Result {
        val contacts = PeopleController.getContactsByCategory(context, category)
        if (contacts.isEmpty()) {
            return Result(false, "❌ Aucune fiche dans la catégorie « $category » à exporter.")
        }

        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
        var geocodedCount = 0
        var skippedCount = 0

        val placemarks = StringBuilder()
        contacts.forEach { contact ->
            var lat = contact.latitude
            var lng = contact.longitude

            if ((lat == null || lng == null) && !contact.address.isNullOrBlank() && geocoder != null) {
                try {
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocationName(contact.address, 1)
                    if (!results.isNullOrEmpty()) {
                        lat = results[0].latitude
                        lng = results[0].longitude
                        geocodedCount++
                    }
                } catch (_: Exception) {
                    // pas de réseau ou service de géocodage indisponible — on ignore cette fiche
                }
            }

            if (lat == null || lng == null) {
                skippedCount++
                return@forEach
            }

            placemarks.append(
                """
                |  <Placemark>
                |    <name>${xmlEscape(contact.name)}</name>
                |    <description>${xmlEscape(contact.address ?: "")}</description>
                |    <Point><coordinates>$lng,$lat,0</coordinates></Point>
                |  </Placemark>
                |
                """.trimMargin()
            )
        }

        if (placemarks.isEmpty()) {
            return Result(false, "❌ Aucune fiche « $category » n'a pu être géolocalisée (ni GPS enregistré, ni adresse géocodable).")
        }

        val kml = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<kml xmlns="http://www.opengis.net/kml/2.2">
            |<Document>
            |  <name>JARVIS — ${xmlEscape(category)}</name>
            |$placemarks</Document>
            |</kml>
        """.trimMargin()

        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "JARVIS-Export")
                .also { it.mkdirs() }
            val file = File(dir, "${category}_${dateFormat.format(Date())}.kml")
            file.writeText(kml)

            val extra = buildString {
                if (geocodedCount > 0) append(" ($geocodedCount adresse(s) géocodée(s) automatiquement)")
                if (skippedCount > 0) append(" — $skippedCount fiche(s) ignorée(s) faute de localisation")
            }
            Result(true, "🗺️ Export KML créé : ${contacts.size - skippedCount} client(s) exporté(s).$extra\n📁 ${file.absolutePath}\n\nImporte-le dans Google Maps via « Vos endroits » → « Cartes » → « Importer une carte ».", file.absolutePath)
        } catch (e: Exception) {
            Result(false, "❌ Erreur lors de l'écriture du fichier KML : ${e.message}")
        }
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
