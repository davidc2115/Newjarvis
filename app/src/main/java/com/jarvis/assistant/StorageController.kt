package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Lot 5 "contrôle téléphone" : accès complet au stockage partagé (lecture, recherche,
 * suppression de fichiers).
 *
 * Depuis Android 10 (API 29), le "scoped storage" empêche un accès large au stockage même
 * avec READ/WRITE_EXTERNAL_STORAGE classiques -- il faut la permission spéciale
 * MANAGE_EXTERNAL_STORAGE ("Autoriser l'accès à tous les fichiers"), qui ne peut PAS être
 * accordée via le dialogue runtime habituel : Android oblige à passer par un écran Réglages
 * système dédié (Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) que l'utilisateur
 * doit approuver lui-même -- aucune app ne peut se l'accorder silencieusement, par design.
 * Sur Android 9 et moins (API < 29), WRITE_EXTERNAL_STORAGE (permission runtime classique)
 * suffit déjà pour un accès complet.
 */
object StorageController {

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            @Suppress("DEPRECATION")
            (context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    /** Intent vers l'écran Réglages système où l'utilisateur accorde (ou non) l'accès complet. */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    private const val MAX_SCANNED = 20000
    private const val MAX_RESULTS = 15
    private const val MAX_DEPTH = 8

    /** Recherche bornée (nombre de fichiers scannés/profondeur limités) par nom de fichier. */
    fun findFiles(query: String): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val results = mutableListOf<File>()
        var scanned = 0
        val lowerQuery = query.lowercase()

        fun walk(dir: File, depth: Int) {
            if (results.size >= MAX_RESULTS || scanned >= MAX_SCANNED || depth > MAX_DEPTH) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (results.size >= MAX_RESULTS || scanned >= MAX_SCANNED) return
                scanned++
                if (child.name.lowercase().contains(lowerQuery)) results.add(child)
                if (child.isDirectory && !child.name.startsWith(".")) walk(child, depth + 1)
            }
        }

        runCatching { walk(root, 0) }
        return results
    }

    /** Supprime un fichier (pas un dossier avec contenu, par sécurité contre les fausses manip). */
    fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) return false
            if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) return false
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
