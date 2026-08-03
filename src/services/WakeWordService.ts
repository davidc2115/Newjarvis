/**
 * NewJarvis - Service de Détection de Mot-Clé (Wake Word)
 * Utilise @picovoice/porcupine-react-native ou Vosk avec mot-clé personnalisable.
 */

export class WakeWordService {
  private static isListening: boolean = false;
  private static currentWakeWord: string = "Jarvis";

  /**
   * Récupère le mot-clé actuellement configuré (défaut: "Jarvis")
   */
  public static getWakeWord(): string {
    const saved = localStorage.getItem('newjarvis_wakeword');
    if (saved) {
      this.currentWakeWord = saved;
    }
    return this.currentWakeWord;
  }

  /**
   * Définit et sauvegarde un nouveau mot-clé personnalisé
   */
  public static setWakeWord(newWord: string): void {
    if (!newWord.trim()) return;
    this.currentWakeWord = newWord.trim();
    localStorage.setItem('newjarvis_wakeword', this.currentWakeWord);
    console.log(`[WakeWordService] Mot-clé personnalisé mis à jour : "${this.currentWakeWord}"`);
  }

  /**
   * Démarre l'écoute en arrière-plan avec le mot-clé configuré
   */
  public static async startListening(onKeywordDetected: () => void): Promise<void> {
    const keyword = this.getWakeWord();
    console.log(`[WakeWordService] Démarrage de l'écoute du mot-clé "${keyword}"...`);
    this.isListening = true;
    
    // Dans l'application React Native Android :
    // PorcupineManager.fromBuiltInKeywords(accessKey, [keyword], (keywordIndex) => {
    //   if (keywordIndex === 0) onKeywordDetected();
    // });
  }

  public static async stopListening(): Promise<void> {
    console.log("[WakeWordService] Arrêt de la boucle audio en arrière-plan.");
    this.isListening = false;
  }
}
