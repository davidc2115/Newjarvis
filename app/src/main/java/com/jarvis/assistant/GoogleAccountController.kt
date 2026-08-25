package com.jarvis.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom

/**
 * Connexion Google DIRECTEMENT depuis l'appli (demande explicite de l'utilisateur), avec
 * possibilité de lier PLUSIEURS comptes Google différents. Deux briques bien distinctes chez
 * Google (voir developer.android.com/identity/authorization -- "Authentication and
 * authorization calls must be two separate and distinct flows") :
 *
 *  1) AUTHENTIFICATION -- "qui est l'utilisateur" -- via Credential Manager (API officielle
 *     actuelle, remplace l'ancienne GoogleSignInClient dépréciée). Affiche le sélecteur de
 *     compte natif du système.
 *  2) AUTORISATION -- "accès aux données Gmail/Agenda" -- via AuthorizationClient
 *     (play-services-auth), demande un consentement séparé par scope, indépendant du choix
 *     de compte.
 *
 * CE QUI RESTE OBLIGATOIRE ET NE PEUT PAS ÊTRE CONTOURNÉ : un ID client OAuth (Google Cloud
 * Console) pour les DEUX étapes -- c'est une exigence de l'infrastructure OAuth de Google
 * elle-même (authentification server-verified via ID token), pas une limite de ce code. Voir
 * le guide donné à l'utilisateur (SettingsActivity + réponse chat) pour la procédure exacte,
 * strictement nécessaire une seule fois, avant que ce contrôleur puisse fonctionner.
 *
 * MULTI-COMPTES : appeler signIn(onlyAuthorized = false) affiche TOUS les comptes Google du
 * téléphone (pas seulement ceux déjà liés à JARVIS), ce qui permet à l'utilisateur d'en choisir
 * un nouveau à chaque appel -- chaque compte ainsi choisi est mémorisé dans Prefs
 * (loadGoogleAccounts/saveGoogleAccounts). Changer de compte ACTIF nécessite de rappeler
 * signIn() -- Android impose ce choix explicite par design (pas de bascule silencieuse entre
 * identités sans interaction utilisateur, voir "Authorization from a non-default account").
 */
object GoogleAccountController {

    data class LinkedAccount(val email: String, val displayName: String)

    // Scopes Gmail (lecture + envoi) + Agenda (accès complet lecture/écriture) -- demande
    // explicite de l'utilisateur : "accès complet ... mail, agenda". gmail.modify couvrirait
    // aussi la suppression/l'archivage si besoin plus tard ; on démarre avec readonly+send pour
    // limiter la portée du consentement demandé à l'écran système.
    private val GMAIL_CALENDAR_SCOPES = listOf(
        Scope("https://www.googleapis.com/auth/gmail.readonly"),
        Scope("https://www.googleapis.com/auth/gmail.send"),
        Scope("https://www.googleapis.com/auth/calendar")
    )

    private fun generateNonce(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(randomBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    /**
     * Étape 1/2 -- AUTHENTIFICATION. [onlyAuthorized] = false affiche le sélecteur avec TOUS
     * les comptes du téléphone (nécessaire pour ajouter un nouveau compte) ; true ne propose
     * que ceux déjà utilisés dans JARVIS (connexion silencieuse au prochain lancement).
     */
    suspend fun signIn(
        context: Context,
        webClientId: String,
        onlyAuthorized: Boolean = false
    ): GoogleIdTokenCredential {
        val credentialManager = CredentialManager.create(context)
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(onlyAuthorized)
            .setServerClientId(webClientId)
            .setNonce(generateNonce())
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = credentialManager.getCredential(context, request)
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data)
        }
        throw IllegalStateException("Type d'identifiant inattendu reçu de Credential Manager.")
    }

    /**
     * Étape 1/2 (REPLI) -- AUTHENTIFICATION via l'ancienne API GoogleSignInClient
     * (play-services-auth, déjà une dépendance du projet -- aucun ajout nécessaire).
     *
     * Utilisée à la place de signIn() (Credential Manager) car ce dernier reste bloqué
     * INDÉFINIMENT sur certains téléphones Xiaomi/MIUI -- ni succès, ni erreur, confirmé par
     * l'utilisateur (timeout de 20s déclenché) alors que le compte Google système et Play
     * Services étaient tous les deux en ordre. Recherche : MIUI a des restrictions documentées
     * qui interfèrent avec les opérations liées aux "credentials" système, y compris pour des
     * apps tierces bien configurées (voir ex. autofill de gestionnaires de mots de passe cassé
     * sur MIUI). GoogleSignInClient est une API bien plus ancienne (donc bien plus largement
     * testée/compatible), même si Google la présente comme remplacée par Credential Manager
     * pour les nouveaux projets -- elle reste pleinement fonctionnelle et documentée
     * officiellement (developer.android.com/identity/sign-in/legacy-gsi).
     *
     * requestIdToken(webClientId) : même rôle que setServerClientId() côté Credential Manager --
     * demande un ID token vérifiable serveur, garanti émis pour CE client OAuth Web précis.
     */
    fun getLegacySignInIntent(context: Context, webClientId: String): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    /**
     * Complète getLegacySignInIntent() une fois l'écran de sélection de compte système validé.
     * Lance ApiException en cas d'échec/annulation -- le code (ex. 10 = DEVELOPER_ERROR, signe
     * que le SHA-1/package n'est pas enregistré comme client OAuth "Android" côté Cloud
     * Console ; 12501 = annulé par l'utilisateur ; 7 = erreur réseau) aide à diagnostiquer
     * précisément la cause, voir GoogleSignInStatusCodes pour le mapping complet.
     */
    fun handleLegacySignInResult(data: Intent?): GoogleSignInAccount {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return task.getResult(ApiException::class.java)
    }

    /**
     * Étape 2/2 -- AUTORISATION (accès Gmail/Agenda) pour le compte qui vient de faire
     * signIn() (Google fixe automatiquement ce compte comme "compte par défaut" de l'appli --
     * voir la doc officielle). Peut nécessiter un écran de consentement système supplémentaire
     * ([AuthorizationResult.hasResolution] = true, la 1ère fois ou si les scopes changent) : le
     * cas échéant on lance [pendingIntentLauncher], sinon [onGranted] est appelé directement
     * avec le jeton d'accès.
     */
    fun requestAuthorization(
        activity: Activity,
        pendingIntentLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onGranted: (accessToken: String?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GMAIL_CALENDAR_SCOPES)
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result: AuthorizationResult ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    pendingIntentLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    onGranted(result.accessToken)
                }
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    /** Complète requestAuthorization() une fois l'écran de consentement système validé. */
    fun handleAuthorizationResult(context: Context, data: Intent?): String? {
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            result.accessToken
        } catch (e: ApiException) {
            null
        }
    }

    /**
     * Déconnecte JARVIS de Credential Manager (voir SettingsActivity) : n'annule PAS les
     * autorisations Gmail/Agenda déjà accordées côté Google (voir doc officielle -- il faut
     * AuthorizationClient.revokeAccess() pour ça, pas exposé ici pour l'instant car pas demandé).
     */
    suspend fun clearCredentialState(context: Context) {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
