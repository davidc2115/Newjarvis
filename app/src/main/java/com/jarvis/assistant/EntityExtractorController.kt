package com.jarvis.assistant

import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Comprehension locale renforcee via ML Kit Entity Extraction (voir app/build.gradle pour la
 * note sur le caractere 100% on-device de cette bibliotheque) -- demande explicite utilisateur
 * de rester local tout en ameliorant la comprehension des demandes ("mais peux tu ajouter ce
 * quil faut pour une comprehension parfaite de mes demande"). Ne remplace PAS les regex de
 * CommandInterpreter (toujours verifiees en premier, deterministes et instantanees) : sert de
 * REPLI quand aucune regex n'a reconnu la phrase, avant de tomber sur le classifieur IA local
 * (plus lent, moins fiable sur des tournures inhabituelles) -- voir MainActivity.classifyThenReply.
 *
 * Contrairement aux regex maison (qui ne reconnaissent qu'un vocabulaire fixe de tournures de
 * date/heure francaises), le modele ML Kit comprend des formulations bien plus variees ("mardi
 * en huit", "dans trois semaines", "le premier du mois prochain"...) car il a ete entraine sur
 * un tres large corpus, plutot que sur une liste de motifs ecrits a la main.
 */
object EntityExtractorController {

    @Volatile private var extractor: EntityExtractor? = null
    @Volatile private var modelReady = false

    private fun getExtractor(): EntityExtractor {
        extractor?.let { return it }
        synchronized(this) {
            extractor?.let { return it }
            val created = EntityExtraction.getClient(
                EntityExtractorOptions.Builder(EntityExtractorOptions.FRENCH).build()
            )
            extractor = created
            return created
        }
    }

    /** Telecharge le modele francais si besoin (une seule fois, ensuite mis en cache par ML
     *  Kit lui-meme -- les appels suivants sont immediats). Ne leve jamais d'exception : false
     *  en cas d'echec (pas de reseau au premier lancement, appareil incompatible...), auquel
     *  cas les fonctions d'extraction ci-dessous renvoient simplement null (repli silencieux
     *  vers le classifieur IA local, jamais de plantage/blocage pour l'utilisateur). */
    private suspend fun ensureModelReady(): Boolean {
        if (modelReady) return true
        return try {
            suspendCancellableCoroutine<Boolean> { cont ->
                getExtractor().downloadModelIfNeeded()
                    .addOnSuccessListener {
                        modelReady = true
                        if (cont.isActive) cont.resume(true)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(false)
                    }
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun annotate(text: String): List<EntityAnnotation> {
        if (text.isBlank() || !ensureModelReady()) return emptyList()
        return try {
            suspendCancellableCoroutine<List<EntityAnnotation>> { cont ->
                val params = EntityExtractionParams.Builder(text).build()
                getExtractor().annotate(params)
                    .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Premier horodatage (ms epoch) trouve dans [text], ou null si aucune date/heure detectee
     *  -- [DateTimeEntity.getTimestampMillis] est deja resolu par ML Kit en tenant compte de la
     *  date/l'heure actuelles de l'appareil (equivalent de CalendarController.resolveLocalDate
     *  mais base sur un modele plutot que des regex). */
    suspend fun extractDateTimeMillis(text: String): Long? {
        for (annotation in annotate(text)) {
            for (entity in annotation.entities) {
                if (entity is DateTimeEntity) return entity.timestampMillis
            }
        }
        return null
    }

    /** Repli "planning" pour MainActivity.classifyThenReply : uniquement tente si le message
     *  contient deja un mot-cle d'agenda clair (voir CommandInterpreter.hasScheduleKeyword) --
     *  evite d'interpreter une date mentionnee dans un contexte qui n'a rien a voir avec le
     *  planning (ex. "j'ai achete ca le 3 aout" ne doit pas devenir une commande planning). */
    suspend fun classifyScheduleFallback(text: String): CommandInterpreter.Command? {
        if (!CommandInterpreter.hasScheduleKeyword(text)) return null
        val millis = extractDateTimeMillis(text) ?: return null
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        // Format ISO "aaaa-MM-jj" : CalendarController.resolveLocalDate le reconnait deja
        // nativement (voir son test Regex("^\\d{4}-\\d{2}-\\d{2}$")), donc EventsForDate n'a
        // besoin d'aucune modification pour accepter ce repli.
        return CommandInterpreter.Command.EventsForDate(date.toString())
    }

    /** Premier numero de telephone detecte dans [text] (texte brut, pas de normalisation --
     *  ML Kit ne fournit pas de sous-classe dediee pour Entity.TYPE_PHONE, contrairement a
     *  DateTimeEntity/MoneyEntity/etc., seulement la position dans le texte d'origine). Pas
     *  encore cablee sur une commande (voir tache comprehension) : disponible pour un futur
     *  repli "creer un contact"/"appelle ce numero" si les regex existantes s'averent
     *  insuffisantes en pratique. */
    suspend fun extractPhoneNumber(text: String): String? {
        for (annotation in annotate(text)) {
            if (annotation.entities.any { it.type == Entity.TYPE_PHONE }) {
                return text.substring(annotation.start, annotation.end)
            }
        }
        return null
    }

    /** Premiere adresse postale detectee dans [text] (meme principe que [extractPhoneNumber] --
     *  disponible pour un futur repli "ouvre le GPS vers..."). */
    suspend fun extractAddress(text: String): String? {
        for (annotation in annotate(text)) {
            if (annotation.entities.any { it.type == Entity.TYPE_ADDRESS }) {
                return text.substring(annotation.start, annotation.end)
            }
        }
        return null
    }
}
