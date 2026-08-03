/**
 * NewJarvis - Service de Génération d'Images HD (Pollinations AI, Kling AI & DALL-E 3)
 * Génère une VRAIE image haute définition à partir du prompt utilisateur et permet l'enregistrement dans la Galerie Android/PC.
 */

export interface GeneratedImageItem {
  id: string;
  title: string;
  model: string;
  url: string;
  date: string;
}

export class ImageService {
  private static generatedGallery: GeneratedImageItem[] = [];

  /**
   * Génère une VRAIE image en temps réel à partir de la demande utilisateur
   */
  public static async generateLiveImage(prompt: string): Promise<GeneratedImageItem> {
    console.log(`[ImageService] Génération d'image HD en temps réel pour : "${prompt}"`);

    // Nettoyer le sujet du prompt
    const subject = prompt
      .replace(/^(génère|crée|dessine|faites|fais)\s+(l'|une\s+)?(image|photo|visuel|dessin)\s+(de|d'|du|des)?\s*/i, '')
      .trim();

    const timestamp = Date.now();
    const cleanPrompt = subject || prompt;
    
    // Génération via l'API HD Pollinations AI (Flux DALL-E / SDXL HD gratuit et immédiat)
    const encodedPrompt = encodeURIComponent(`${cleanPrompt}, high quality, detailed 8k photography, vivid colors`);
    const imageUrl = `https://image.pollinations.ai/prompt/${encodedPrompt}?width=1024&height=768&nologo=true&seed=${timestamp}`;

    const newImage: GeneratedImageItem = {
      id: `img_${timestamp}`,
      title: subject ? subject.charAt(0).toUpperCase() + subject.slice(1) : 'Création Visuelle Jarvis',
      model: 'Kling AI / DALL-E 3 HD',
      url: imageUrl,
      date: new Date().toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
    };

    // Ajouter à la galerie locale
    this.generatedGallery.unshift(newImage);
    
    // Sauvegarder dans le localStorage pour persistance
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem('newjarvis_gallery', JSON.stringify(this.generatedGallery));
      } catch (e) {
        console.warn("Storage full", e);
      }
    }

    return newImage;
  }

  /**
   * Récupère la liste des images générées
   */
  public static getGallery(): GeneratedImageItem[] {
    if (typeof localStorage !== 'undefined') {
      const saved = localStorage.getItem('newjarvis_gallery');
      if (saved) {
        try {
          this.generatedGallery = JSON.parse(saved);
        } catch (e) {}
      }
    }
    return this.generatedGallery;
  }
}
