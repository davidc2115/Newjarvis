package com.jarvis.assistant

import android.app.ActivityManager
import android.content.Context

/**
 * Demande utilisateur explicite : "afficher en haut de chat et en vocal un compteur de RAM
 * utilisée" — particulièrement utile depuis l'ajout des modèles IA locaux (GGUF/MediaPipe,
 * jusqu'à plusieurs Go de RAM) pour que l'utilisateur voie tout de suite si le téléphone
 * approche de sa limite, sans devoir ouvrir les réglages Android.
 *
 * RAM SYSTÈME (pas seulement celle de JARVIS) : ActivityManager.MemoryInfo reflète l'usage
 * global du téléphone (tous les processus, modèle IA local chargé y compris), ce qui est plus
 * utile ici que Debug.getMemoryInfo/TotalPss qui ne mesurerait que le processus JARVIS lui-même
 * et manquerait donc l'essentiel du signal recherché (le modèle IA local tourne dans CE même
 * processus via JNI, donc en pratique les deux mesures convergent surtout pour ce cas précis,
 * mais l'usage système reste le signal le plus fiable/simple à interpréter pour l'utilisateur).
 */
object RamMonitor {

    /** Étiquette compacte du type "RAM 3.2/6.0 Go (54%)", prête à afficher directement. */
    fun usageLabel(context: Context): String {
        val info = rawMemoryInfo(context) ?: return "RAM : indisponible"
        val usedBytes = info.totalMem - info.availMem
        val usedGb = usedBytes / GB
        val totalGb = info.totalMem / GB
        val percent = if (info.totalMem > 0) ((usedBytes * 100) / info.totalMem).toInt() else 0
        return "RAM %.1f/%.1f Go (%d%%)".format(usedGb, totalGb, percent)
    }

    /** true si le système signale un niveau de mémoire bas (utile pour avertir/colorer en rouge). */
    fun isLowMemory(context: Context): Boolean = rawMemoryInfo(context)?.lowMemory ?: false

    private const val GB = 1024.0 * 1024.0 * 1024.0

    private fun rawMemoryInfo(context: Context): ActivityManager.MemoryInfo? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info
    }
}
