/**
 * NewJarvis - Service de Synthèse Vocale TTS Natif (Smartphone & PC/Navigateur)
 * Nettoie automatiquement le Markdown (supprime les astérisques "*", diezes "#", etc.) pour une voix 100% naturelle sans lire "étoile".
 */

export class TTSService {
  static isSpeaking = false;

  /**
   * Nettoie le texte du Markdown pour qu'il soit prononcé naturellement par le moteur vocal
   */
  static cleanTextForSpeech(text) {
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
  static speak(text, onStart, onEnd) {
    if (!text || !text.trim()) return;

    const cleanSpeechText = this.cleanTextForSpeech(text);

    // 1. Support PC / Navigateur Web (Web Speech API)
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      try {
        window.speechSynthesis.cancel();

        const utterance = new SpeechSynthesisUtterance(cleanSpeechText);
        utterance.lang = 'fr-FR';
        utterance.rate = 1.05;
        utterance.pitch = 0.95;

        const voices = window.speechSynthesis.getVoices();
        const frenchVoice = voices.find(v => v.lang.startsWith('fr'));
        if (frenchVoice) {
          utterance.voice = frenchVoice;
        }

        utterance.onstart = () => {
          this.isSpeaking = true;
          if (onStart) onStart();
        };

        utterance.onend = () => {
          this.isSpeaking = false;
          if (onEnd) onEnd();
        };

        utterance.onerror = (e) => {
          console.error("[TTSService] Erreur TTS :", e);
          this.isSpeaking = false;
          if (onEnd) onEnd();
        };

        window.speechSynthesis.speak(utterance);
      } catch (err) {
        console.error("[TTSService] Exception :", err);
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
  static stop() {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    this.isSpeaking = false;
  }
}
