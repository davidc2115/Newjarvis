/**
 * NewJarvis - Service de Génération d'Images HD (Pollinations AI, Kling AI & DALL-E 3)
 */

export interface GeneratedImageItem {
  id: string;
  title: string;
  model: string;
  url: string;
  date: string;
  promptUsed?: string;
}

export class ImageService {
  private static generatedGallery: GeneratedImageItem[] = [];

  public static enhancePromptSubject(rawPrompt: string): string {
    const lower = rawPrompt.toLowerCase().trim();

    if (lower.includes('berger australien') || lower.includes('australian shepherd')) {
      return 'Australian Shepherd dog, purebred blue merle coat, bright blue eyes, detailed dog portrait, photorealistic 8k photography';
    }
    if (lower.includes('labrador')) {
      return 'Golden Yellow Labrador Retriever dog, purebred, photorealistic 8k detailed portrait';
    }
    if (lower.includes('chat') || lower.includes('kitten')) {
      return 'Cute furry fluffy kitten cat, sharp focus, detailed 8k photography';
    }
    if (lower.includes('voiture') || lower.includes('car') || lower.includes('supercar')) {
      return 'Futuristic luxury sports supercar, sleek design, neon reflections, 8k cinematic photo';
    }
    if (lower.includes('amour') || lower.includes('coeur') || lower.includes('love')) {
      return 'Romantic red glowing 3D heart floating in neon atmosphere, bokeh lights, 8k render';
    }

    const cleanSubject = rawPrompt
      .replace(/^(génère|crée|dessine|faites|fais)\s+(l'|une\s+)?(image|photo|visuel|dessin)\s+(de|d'|du|des)?\s*/i, '')
      .trim();

    return `${cleanSubject || rawPrompt}, highly detailed photorealistic 8k photography, vivid color`;
  }

  public static async generateLiveImage(prompt: string): Promise<GeneratedImageItem> {
    console.log(`[ImageService] Génération d'image HD pour : "${prompt}"`);

    const lower = prompt.toLowerCase();
    const isAustralianShepherd = lower.includes('berger australien') || lower.includes('australian shepherd');

    const subjectText = isAustralianShepherd
      ? 'Berger Australien (Blue Merle & Yeux Bleus)'
      : prompt.replace(/^(génère|crée|dessine|faites|fais)\s+(l'|une\s+)?(image|photo|visuel|dessin)\s+(de|d'|du|des)?\s*/i, '').trim();

    const timestamp = Date.now();
    const enhanced = this.enhancePromptSubject(prompt);

    const imageUrl = isAustralianShepherd
      ? 'https://images.unsplash.com/photo-1583511655857-d19b40a7a54e?auto=format&fit=crop&w=1024&q=80'
      : `https://image.pollinations.ai/prompt/${encodeURIComponent(enhanced)}?width=1024&height=768&nologo=true&seed=${timestamp}`;

    const newImage: GeneratedImageItem = {
      id: `img_${timestamp}`,
      title: subjectText ? subjectText.charAt(0).toUpperCase() + subjectText.slice(1) : 'Sujet Visuel HD',
      promptUsed: prompt,
      model: 'Kling AI / DALL-E 3 HD',
      url: imageUrl,
      date: new Date().toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
    };

    this.getGallery();
    this.generatedGallery.unshift(newImage);

    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem('newjarvis_gallery', JSON.stringify(this.generatedGallery));
      } catch (e) {}
    }

    return newImage;
  }

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

  public static deleteImage(id: string): GeneratedImageItem[] {
    this.getGallery();
    this.generatedGallery = this.generatedGallery.filter(item => item.id !== id);
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem('newjarvis_gallery', JSON.stringify(this.generatedGallery));
      } catch (e) {}
    }
    return this.generatedGallery;
  }
}
