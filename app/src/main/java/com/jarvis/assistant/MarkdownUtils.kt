package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View

/**
 * Rendu markdown minimal (gras **texte**, italique *texte*, code `texte`,
 * titres #, listes - et numérotées) pour éviter d'afficher les symboles
 * bruts (astérisques, dièses, tirets) dans les bulles de chat ou à l'oral.
 *
 * Ajoute aussi des liens cliquables (téléphone → composeur, email → nouveau
 * message, adresse → GPS par défaut du téléphone) directement dans le texte
 * affiché — utile pour les fiches contact, résultats SMS/emails, etc. sans
 * changer leur format d'affichage existant.
 */
object MarkdownUtils {

    private val headerRegex = Regex("^#{1,6}\\s+(.*)$", RegexOption.MULTILINE)
    private val bulletRegex = Regex("^[-*]\\s+", RegexOption.MULTILINE)
    private val numberedListRegex = Regex("^\\d+\\.\\s+", RegexOption.MULTILINE)
    private val combinedRegex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`(.+?)`|\\*(.+?)\\*")
    private val boldOnlyRegex = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val italicOnlyRegex = Regex("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)")
    private val codeOnlyRegex = Regex("`(.+?)`")

    // Téléphone : formats français les plus courants (0X XX XX XX XX / +33 X XX XX XX XX),
    // avec séparateurs espace/point/tiret optionnels. Volontairement restrictif (plutôt que
    // "tout ce qui ressemble à des chiffres") pour éviter de rendre cliquables des dates,
    // identifiants ou codes postaux par erreur.
    private val phoneRegex = Regex(
        "(?<![\\w.])(?:\\+33[\\s.-]?[1-9](?:[\\s.-]?\\d{2}){4}|0[1-9](?:[\\s.-]?\\d{2}){4})(?![\\w.])"
    )
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    // Adresses : repose sur les marqueurs emoji que JARVIS utilise lui-même de façon
    // cohérente pour afficher une adresse (fiches contact — voir PeopleController). Une
    // détection générique d'adresse en texte libre est trop peu fiable (pas de motif fixe
    // en français) ; se limiter à ce que l'app génère elle-même garantit zéro faux positif.
    private val addressColonRegex = Regex("(?:🏠|🏗️)[^:\\n]*:\\s*([^\\n]+)")
    private val addressInlineRegex = Regex("🏠\\s+([^\\n—]+)")

    /**
     * [context] est optionnel : quand fourni, les liens cliquables (tel/mail/itinéraire) ne
     * sont posés QUE si l'utilisateur a explicitement demandé à JARVIS de les activer
     * (Prefs.isContactLinksEnabled — via enable_contact_links). Avant ce correctif, ces liens
     * étaient posés automatiquement sur chaque message contenant un numéro/email/adresse, ce
     * que l'utilisateur ne voulait pas — désormais c'est un comportement opt-in, désactivé par
     * défaut.
     */
    fun toSpannable(raw: String, context: Context? = null): CharSequence {
        var text = headerRegex.replace(raw) { "**${it.groupValues[1]}**" }
        text = bulletRegex.replace(text) { "• " }

        val builder = SpannableStringBuilder()
        var lastEnd = 0
        for (match in combinedRegex.findAll(text)) {
            builder.append(text.substring(lastEnd, match.range.first))
            val boldContent = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val codeContent = match.groupValues[3]
            val italicContent = match.groupValues[4]
            when {
                codeContent.isNotEmpty() -> {
                    val start = builder.length
                    builder.append(codeContent)
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                boldContent.isNotEmpty() -> {
                    val start = builder.length
                    builder.append(boldContent)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val start = builder.length
                    builder.append(italicContent)
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            lastEnd = match.range.last + 1
        }
        builder.append(text.substring(lastEnd))
        if (context != null && Prefs.isContactLinksEnabled(context)) {
            applyContactLinks(builder)
        }
        return builder
    }

    /** Rend cliquables les numéros de téléphone, emails et adresses détectés dans [builder]. */
    private fun applyContactLinks(builder: SpannableStringBuilder) {
        val text = builder.toString()
        val claimed = mutableListOf<IntRange>()
        fun isClaimed(range: IntRange) = claimed.any { it.first <= range.last && range.first <= it.last }

        fun addClickSpan(range: IntRange, onClick: (View) -> Unit) {
            claimed.add(range)
            builder.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onClick(widget)
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                }
            }, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Adresses en premier (peuvent contenir des chiffres qu'on ne veut pas re-matcher comme téléphone).
        for (regex in listOf(addressColonRegex, addressInlineRegex)) {
            for (match in regex.findAll(text)) {
                val group = match.groups[1] ?: continue
                val range = group.range
                if (range.isEmpty() || isClaimed(range)) continue
                val address = group.value.trim()
                addClickSpan(range) { view -> LocationController.openMaps(view.context, address) }
            }
        }

        for (match in emailRegex.findAll(text)) {
            val range = match.range
            if (isClaimed(range)) continue
            val email = match.value
            addClickSpan(range) { view ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                try { view.context.startActivity(intent) } catch (_: Exception) { }
            }
        }

        for (match in phoneRegex.findAll(text)) {
            val range = match.range
            if (isClaimed(range)) continue
            val phone = match.value
            addClickSpan(range) { view ->
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
                try { view.context.startActivity(intent) } catch (_: Exception) { }
            }
        }
    }

    fun stripForSpeech(raw: String): String {
        var text = boldOnlyRegex.replace(raw) { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        text = italicOnlyRegex.replace(text) { it.groupValues[1] }
        text = codeOnlyRegex.replace(text) { it.groupValues[1] }
        text = headerRegex.replace(text) { it.groupValues[1] }
        text = bulletRegex.replace(text, "")
        text = numberedListRegex.replace(text, "")
        text = text.replace("*", "").replace("_", "").replace("#", "")
        return text
    }
}
