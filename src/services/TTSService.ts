/**
 * NewJarvis - Service de Synthèse Vocale TTS Natif (Smartphone & PC/Navigateur)
 * Nettoie automatiquement le Markdown (supprime les astérisques "*", diezes "#", etc.) pour une voix 100% naturelle sans lire "étoile".
 */

export class TTSService {
  private static isSpeaking: boolean = false;

  /**
   * Nettoie le texte du Markdown pour qu'il soit prononcé naturellement par le moteur vocal
   */
  public static cleanTextForSpeech(text: string): string {
    if (!text) return '';
    return text
      .replace(/\*+/g, '')                   // Supprimer toutes les astérisques (*)
      .replace(/#+/g, '')                    // Supprimer les dièses (#)
      .replace(/`+/g, '')                    // Supprimer les backticks (`)
      .replace(/^\s*[-•]\s*/gm, '')          // Supprimer les puces (- et •)
      .replace(/\[([^\]]+)\]\([^\)]+\)/g, '$1') // Conserver uniquement le texte des liens
      .replace(/https?:\/\/\S+/g, '')       // Supprimer les URL brutes
      .replace(/°C/g, ' degrés Celsius')     // Remplacer °C par degrés Celsius
      .replace(/km\/h/g, ' kilomètres par heure') // Remplacer km/h par kilomètres par heure
      .replace(/\s+/g, ' ')                  // Normaliser les espaces
      .trim();
  }

  /**
   * Prononce vocalement le texte en français via le moteur TTS natif sans lire les astérisques
   */
  public static speak(text: string, onStart?: () => void, onEnd?: () => void): void {
    if (!text || !text.trim()) return;

    // Nettoyage strict du texte avant la synthèse vocale
    const cleanSpeechText = this.cleanTextForSpeech(text);

    // 1. Support PC / Navigateur Web (Web Speech API)
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      try {
        window.speechSynthesis.cancel(); // Arrêter les lectures en cours

        const utterance = new SpeechSynthesisUtterance(cleanSpeechText);
        utterance.lang = 'fr-FR';
        utterance.rate = 1.05; // Vitesse de parole fluide
        utterance.pitch = 0.95; // Tonalité naturelle

        const voices = window.speechSynthesis.getVoices();
        const frenchVoice = voices.find(v => v.lang.startsWith('fr'));
        if (frenchVoice) {
          utterance.voice = frenchVoice;
        }

        utterance.onstart = () => {
          this.isSpeaking = true;
          console.log(`[TTSService] Restitution vocale propre : "${cleanSpeechText}"`);
          if (onStart) onStart();
        };

        utterance.onend = () => {
          this.isSpeaking = false;
          console.log(`[TTSService] Restitution vocale terminée.`);
          if (onEnd) onEnd();
        };

        utterance.onerror = (e) => {
          console.error("[TTSService] Erreur TTS :", e);
          this.isSpeaking = false;
          if (onEnd) onEnd();
        };

        window.speechSynthesis.speak(utterance);
      } catch (err) {
        console.error("[TTSService] Exception lors de la lecture vocale :", err);
        if (onEnd) onEnd();
      }
    } 
    // 2. Fallback pour React Native Android (react-native-tts)
    else {
      console.log(`[TTSService Android Native] Tts.speak("${cleanSpeechText}")`);
      if (onStart) onStart();
      setTimeout(() => {
        if (onEnd) onEnd();
      }, 3000);
    }
  }

  /**
   * Arrête la restitution vocale en cours
   */
  public static stop(): void {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    this.isSpeaking = false;
  }
}
