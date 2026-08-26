package com.jarvis.assistant

import android.content.Context

/**
 * RouterController — point d'entrée UNIQUE pour piloter la box internet, quel que
 * soit le fournisseur (Freebox, Livebox/Orange, SFR Box, Bbox/Bouygues). Route
 * chaque appel vers le bon contrôleur selon Prefs.getBoxVendor(), pour que le
 * reste de l'app (JarvisCommandParser, etc.) n'ait jamais à connaître le
 * fournisseur choisi par l'utilisateur.
 *
 * Capacités par fournisseur (honnêtement différentes — jamais de faux-semblant) :
 *  - status / wifiSet / listDevices / reboot : les 4 fournisseurs.
 *  - redirection de ports (configurePortForward/removePortForward) : Freebox et
 *    Livebox uniquement (API confirmée). Bbox/SFR Box : message honnête.
 *  - stockage interne/USB (storageInfo) : Freebox uniquement (seule à exposer
 *    cette info via son API). Autres : message honnête.
 */
object RouterController {

    enum class Vendor { FREEBOX, LIVEBOX, SFR, BBOX }

    fun vendor(context: Context): Vendor = when (Prefs.getBoxVendor(context).uppercase()) {
        "LIVEBOX" -> Vendor.LIVEBOX
        "SFR" -> Vendor.SFR
        "BBOX" -> Vendor.BBOX
        else -> Vendor.FREEBOX
    }

    fun vendorLabel(context: Context): String = when (vendor(context)) {
        Vendor.FREEBOX -> "Freebox"
        Vendor.LIVEBOX -> "Livebox"
        Vendor.SFR -> "SFR Box"
        Vendor.BBOX -> "Bbox"
    }

    fun isConfigured(context: Context): Boolean = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.isConfigured(context)
        Vendor.LIVEBOX -> LiveboxController.isConfigured(context)
        Vendor.SFR -> SfrBoxController.isConfigured(context)
        Vendor.BBOX -> BboxController.isConfigured(context)
    }

    suspend fun status(context: Context): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.status(context)
        Vendor.LIVEBOX -> LiveboxController.status(context)
        Vendor.SFR -> SfrBoxController.status(context)
        Vendor.BBOX -> BboxController.status(context)
    }

    suspend fun wifiSet(context: Context, enable: Boolean): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.wifiSet(context, enable)
        Vendor.LIVEBOX -> LiveboxController.wifiSet(context, enable)
        Vendor.SFR -> SfrBoxController.wifiSet(context, enable)
        Vendor.BBOX -> BboxController.wifiSet(context, enable)
    }

    /** Seule la Freebox expose un endpoint dedie a l'etat Wi-Fi seul (SSID, canal...) ;
     * pour les autres fournisseurs on retombe honnetement sur le statut general. */
    suspend fun wifiStatus(context: Context): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.wifiStatus(context)
        else -> status(context) + "\n\nℹ️ L'état Wi-Fi détaillé (SSID, canal...) n'est pas disponible séparément pour ${vendorLabel(context)} — état général de la box ci-dessus."
    }

    suspend fun listDevices(context: Context): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.listDevices(context)
        Vendor.LIVEBOX -> LiveboxController.listDevices(context)
        Vendor.SFR -> SfrBoxController.listDevices(context)
        Vendor.BBOX -> BboxController.listDevices(context)
    }

    suspend fun reboot(context: Context): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.reboot(context)
        Vendor.LIVEBOX -> LiveboxController.reboot(context)
        Vendor.SFR -> SfrBoxController.reboot(context)
        Vendor.BBOX -> BboxController.reboot(context)
    }

    suspend fun configurePortForward(context: Context, wanPort: Int, lanPort: Int, comment: String = "Site JARVIS"): String =
        when (vendor(context)) {
            Vendor.FREEBOX -> FreeboxController.configurePortForward(context, wanPort, lanPort, comment)
            Vendor.LIVEBOX -> LiveboxController.configurePortForward(context, wanPort, lanPort, comment)
            Vendor.SFR -> SfrBoxController.portForwardUnavailableMessage()
            Vendor.BBOX -> BboxController.portForwardUnavailableMessage()
        }

    suspend fun removePortForward(context: Context, wanPort: Int): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.removePortForward(context, wanPort)
        Vendor.LIVEBOX -> LiveboxController.removePortForward(context, wanPort)
        Vendor.SFR -> SfrBoxController.portForwardUnavailableMessage()
        Vendor.BBOX -> BboxController.portForwardUnavailableMessage()
    }

    suspend fun storageInfo(context: Context): String = when (vendor(context)) {
        Vendor.FREEBOX -> FreeboxController.storageInfo(context)
        Vendor.LIVEBOX -> LiveboxController.storageUnavailableMessage()
        Vendor.SFR -> SfrBoxController.storageUnavailableMessage()
        Vendor.BBOX -> BboxController.storageUnavailableMessage()
    }
}
