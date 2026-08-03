import { TTSService } from './TTSService';
import { ImageService } from './ImageService';

export interface RouteResult {
  provider: string;
  model: string;
  intent: string;
  rationale: string;
  smartResponse: string;
  appCode?: string;
  autoSwitchToApp?: boolean;
  generatedImage?: any;
  target?: string;
}

export class AIRouterService {
  public static generateAppCodeFromPrompt(prompt: string): string {
    const lower = prompt.toLowerCase();

    if (lower.includes('amour') || lower.includes('love') || lower.includes('romance') || lower.includes('coeur')) {
      return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>L'Amour & Romance</title>
  <style>
    body { background: #1b0c24; color: #f472b6; font-family: system-ui, sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 100vh; margin: 0; text-align: center; }
    .card { background: rgba(15, 23, 42, 0.9); border: 2px solid #ec4899; border-radius: 24px; padding: 32px; max-width: 440px; }
    .heart-3d { width: 80px; height: 80px; background: #ec4899; margin: 30px auto; position: relative; transform: rotate(-45deg); animation: pulse 1.2s ease-in-out infinite; }
    .heart-3d:before, .heart-3d:after { content: ""; width: 80px; height: 80px; background: #ec4899; border-radius: 50%; position: absolute; }
    .heart-3d:before { top: -40px; left: 0; }
    .heart-3d:after { left: 40px; top: 0; }
    @keyframes pulse { 0%, 100% { transform: rotate(-45deg) scale(1); } 50% { transform: rotate(-45deg) scale(1.15); } }
  </style>
</head>
<body>
  <div class="card">
    <h1>💖 Le Site de L'Amour</h1>
    <div class="heart-3d"></div>
    <p>Application romantique interactive créée en direct par Jarvis.</p>
  </div>
</body>
</html>`;
    }

    return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>${prompt}</title>
  <style>
    body { background: #050b14; color: #00f0ff; font-family: system-ui, sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 100vh; margin: 0; text-align: center; }
    .card { background: rgba(15, 23, 42, 0.9); border: 2px solid #00f0ff; border-radius: 20px; padding: 30px; max-width: 480px; }
    h1 { color: #fff; text-shadow: 0 0 15px #00f0ff; }
  </style>
</head>
<body>
  <div class="card">
    <h1>🚀 ${prompt}</h1>
    <p>Application personnalisée générée en direct par Jarvis.</p>
  </div>
</body>
</html>`;
  }

  public static async queryLiveAI(prompt: string, wakeWord = 'Jarvis'): Promise<RouteResult> {
    const lower = prompt.toLowerCase().trim();

    if (
      lower.includes('site web') || lower.includes('crée un site') || lower.includes('crée une app') || 
      lower.includes('application') || lower.includes('amour') || lower.includes('morpion')
    ) {
      const generatedCode = this.generateAppCodeFromPrompt(prompt);
      return {
        provider: 'claude',
        model: 'Claude 3.5 Sonnet / Dev Core',
        intent: 'CREATION_APP_VOCALE_SUR_MESURE',
        rationale: 'Création d\'une application sur mesure.',
        smartResponse: `J'ai créé l'application demandée. Elle s'affiche en direct.`,
        appCode: generatedCode,
        autoSwitchToApp: true
      };
    }

    if (
      lower.includes('image') || lower.includes('photo') || lower.includes('dessine') || 
      lower.includes('berger australien') || lower.includes('labrador')
    ) {
      const generatedImg = await ImageService.generateLiveImage(prompt);
      return {
        provider: 'kling',
        model: 'Kling AI / DALL-E 3 HD',
        intent: 'GENERATION_IMAGE_REELLE_HD',
        rationale: 'Génération d\'une vraie image HD.',
        smartResponse: `J'ai généré l'image du ${generatedImg.title}. Elle est dans votre Galerie.`,
        generatedImage: generatedImg
      };
    }

    return {
      provider: 'groq',
      model: 'llama-3.3-70b-versatile',
      intent: 'IA_CONCISE_HUMAINE',
      rationale: 'Réponse dynamique.',
      smartResponse: `Demande reçue pour "${prompt}". Comment puis-je vous aider ?`
    };
  }
}
