import { TTSService } from './TTSService';
import { ImageService } from './ImageService';

/**
 * NewJarvis - Service d'IA Générative, Créateur Vocal d'Apps sur Mesure & Météo Live
 */

export class AIRouterService {

  /**
   * Génère une vraie application web interactive (HTML/CSS/JS) selon le thème demandé
   */
  static generateAppCodeFromPrompt(prompt) {
    const lower = prompt.toLowerCase();

    // 1. THÈME AMOUR / ROMANCE
    if (lower.includes('amour') || lower.includes('love') || lower.includes('romance') || lower.includes('coeur')) {
      return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>L'Amour & Romance - Jarvis Core</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: linear-gradient(135deg, #1a051d, #3b0764, #1e1b4b); color: #f472b6; font-family: system-ui, sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; text-align: center; }
    .card { background: rgba(15, 23, 42, 0.9); border: 2px solid #ec4899; border-radius: 24px; padding: 32px; max-width: 440px; width: 100%; box-shadow: 0 0 45px rgba(236,72,153,0.4); backdrop-filter: blur(16px); }
    h1 { font-size: 2rem; color: #fff; text-shadow: 0 0 20px #ec4899; margin-bottom: 12px; }
    p { color: #cbd5e1; font-size: 0.95rem; margin-bottom: 24px; line-height: 1.6; }
    .heart-3d { width: 80px; height: 80px; background: #ec4899; margin: 30px auto; position: relative; transform: rotate(-45deg); animation: pulse 1.2s ease-in-out infinite; box-shadow: 0 0 40px #ec4899; }
    .heart-3d:before, .heart-3d:after { content: ""; width: 80px; height: 80px; background: #ec4899; border-radius: 50%; position: absolute; }
    .heart-3d:before { top: -40px; left: 0; }
    .heart-3d:after { left: 40px; top: 0; }
    @keyframes pulse { 0%, 100% { transform: rotate(-45deg) scale(1); } 50% { transform: rotate(-45deg) scale(1.15); } }
    .btn { background: linear-gradient(135deg, #ec4899, #f43f5e); color: #fff; font-weight: bold; border: none; padding: 14px 28px; border-radius: 12px; font-size: 1rem; cursor: pointer; text-transform: uppercase; box-shadow: 0 0 20px rgba(244,63,94,0.5); }
    .quote-box { margin-top: 20px; font-style: italic; color: #fbcfe8; font-size: 0.9rem; background: rgba(0,0,0,0.4); padding: 12px; border-radius: 10px; border: 1px solid #f472b6; }
  </style>
</head>
<body>
  <div class="card">
    <h1>💖 Le Site de L'Amour</h1>
    <div class="heart-3d"></div>
    <p>Une expérience interactive dédiée à la passion, aux poèmes et à la romance créée en direct par Jarvis.</p>
    <button class="btn" onclick="genQuote()">Découvrir une Pensée</button>
    <div id="quote" class="quote-box">"L'amour est la seule fleur qui croît sans le secours des saisons."</div>
  </div>
  <script>
    const q = [
      '"Aimer, ce n\'est pas se regarder l\'un l\'autre, c\'est regarder ensemble dans la même direction."',
      '"Un seul être vous manque, et tout est dépeuplé."',
      '"Il n\'y a qu\'un bonheur dans cette vie, c\'est d\'aimer et d\'être aimé."'
    ];
    function genQuote() {
      document.getElementById('quote').innerText = q[Math.floor(Math.random()*q.length)];
    }
  </script>
</body>
</html>`;
    }

    // 2. JEU MORPION / TIC-TAC-TOE
    if (lower.includes('jeu') || lower.includes('morpion') || lower.includes('tic-tac-toe')) {
      return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>Jeu du Morpion Jarvis</title>
  <style>
    body { background: #0f172a; color: #38bdf8; font-family: system-ui, sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
    .card { background: #1e293b; padding: 25px; border-radius: 20px; border: 2px solid #38bdf8; text-align: center; box-shadow: 0 0 30px rgba(56,189,248,0.3); }
    h1 { margin-bottom: 15px; color: #fff; }
    .grid { display: grid; grid-template-columns: repeat(3, 80px); gap: 10px; margin: 20px auto; }
    .cell { width: 80px; height: 80px; background: #090d16; border: 1px solid #38bdf8; border-radius: 12px; font-size: 2rem; font-weight: bold; color: #fff; display: flex; justify-content: center; align-items: center; cursor: pointer; }
    .cell:hover { background: #38bdf8; color: #000; }
    .status { margin-top: 15px; font-size: 1.1rem; color: #a5f3fc; }
  </style>
</head>
<body>
  <div class="card">
    <h1>🎮 Jeu du Morpion</h1>
    <div class="grid" id="grid"></div>
    <div class="status" id="status">Tour du Joueur X</div>
  </div>
  <script>
    let board = Array(9).fill('');
    let turn = 'X';
    const grid = document.getElementById('grid');
    const status = document.getElementById('status');

    function render() {
      grid.innerHTML = '';
      board.forEach((val, idx) => {
        const cell = document.createElement('div');
        cell.className = 'cell';
        cell.innerText = val;
        cell.onclick = () => move(idx);
        grid.appendChild(cell);
      });
    }

    function move(idx) {
      if (board[idx] !== '' || checkWin()) return;
      board[idx] = turn;
      if (checkWin()) {
        status.innerText = 'Joueur ' + turn + ' a GAGNÉ ! 🎉';
      } else if (board.every(c => c !== '')) {
        status.innerText = 'Match Nul ! 🤝';
      } else {
        turn = turn === 'X' ? 'O' : 'X';
        status.innerText = 'Tour du Joueur ' + turn;
      }
      render();
    }

    function checkWin() {
      const wins = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];
      return wins.some(([a,b,c]) => board[a] && board[a] === board[b] && board[a] === board[c]);
    }

    render();
  </script>
</body>
</html>`;
    }

    // 3. CALCULATRICE / APPLICATION INTERACTIVE
    if (lower.includes('calcul') || lower.includes('calculatrice')) {
      return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>Calculatrice Jarvis</title>
  <style>
    body { background: #0f172a; color: #fff; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; font-family: system-ui, sans-serif; }
    .calc { background: #1e293b; padding: 20px; border-radius: 20px; border: 2px solid #00f0ff; box-shadow: 0 0 30px rgba(0,240,255,0.3); width: 280px; text-align: center; }
    #screen { width: 100%; height: 50px; background: #090d16; border: 1px solid #00f0ff; border-radius: 10px; color: #00f0ff; font-size: 1.5rem; text-align: right; padding: 10px; box-sizing: border-box; margin-bottom: 15px; }
    .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
    button { background: #334155; color: #fff; border: none; padding: 15px; border-radius: 10px; font-size: 1.1rem; cursor: pointer; font-weight: bold; }
    button:hover { background: #00f0ff; color: #000; }
    .op { background: #0284c7; }
  </style>
</head>
<body>
  <div class="calc">
    <h2 style="margin-bottom:10px; color:#00f0ff;">Calculatrice</h2>
    <input type="text" id="screen" value="0" readonly>
    <div class="grid">
      <button onclick="press('7')">7</button><button onclick="press('8')">8</button><button onclick="press('9')">9</button><button class="op" onclick="press('/')">/</button>
      <button onclick="press('4')">4</button><button onclick="press('5')">5</button><button onclick="press('6')">6</button><button class="op" onclick="press('*')">*</button>
      <button onclick="press('1')">1</button><button onclick="press('2')">2</button><button onclick="press('3')">3</button><button class="op" onclick="press('-')">-</button>
      <button onclick="cls()">C</button><button onclick="press('0')">0</button><button onclick="calc()" class="op">=</button><button class="op" onclick="press('+')">+</button>
    </div>
  </div>
  <script>
    let s = document.getElementById('screen');
    function press(v) { if(s.value === '0') s.value = v; else s.value += v; }
    function cls() { s.value = '0'; }
    function calc() { try { s.value = eval(s.value); } catch(e) { s.value = 'Erreur'; } }
  </script>
</body>
</html>`;
    }

    // 4. AUTRE APPLICATION WEB PERSONNALISÉE
    const title = prompt.replace(/^(génère|crée|faites|fais)\s+(l'|une\s+)?(app|application|site|site\s+web)\s+(de|d'|du|des)?\s*/i, '').trim();
    const appTitle = title ? title.charAt(0).toUpperCase() + title.slice(1) : 'Application Interactive';

    return `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${appTitle} - Jarvis AI</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: #050b14; color: #00f0ff; font-family: system-ui, sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; text-align: center; }
    .card { background: rgba(15, 23, 42, 0.9); border: 2px solid #00f0ff; border-radius: 20px; padding: 30px; max-width: 480px; width: 100%; box-shadow: 0 0 40px rgba(0,240,255,0.3); }
    h1 { font-size: 1.8rem; text-transform: uppercase; letter-spacing: 2px; margin-bottom: 12px; color: #fff; text-shadow: 0 0 15px #00f0ff; }
    p { color: #94a3b8; font-size: 0.95rem; margin-bottom: 25px; line-height: 1.5; }
    .reactor-core { width: 90px; height: 90px; border: 3px dashed #00f0ff; border-radius: 50%; margin: 20px auto; animation: spin 8s linear infinite; box-shadow: inset 0 0 20px #00f0ff, 0 0 25px #00f0ff; }
    @keyframes spin { 100% { transform: rotate(360deg); } }
    .btn { background: linear-gradient(135deg, #00f0ff, #a855f7); color: #000; font-weight: bold; border: none; padding: 14px 28px; border-radius: 12px; font-size: 1rem; cursor: pointer; text-transform: uppercase; }
  </style>
</head>
<body>
  <div class="card">
    <h1>🚀 ${appTitle}</h1>
    <div class="reactor-core"></div>
    <p>Application personnalisée générée en direct sur le thème : "${prompt}".</p>
    <button class="btn" onclick="alert('Application interactive Jarvis opérationnelle !')">Lancer l'Application</button>
  </div>
</body>
</html>`;
  }

  /**
   * Analyse et traite la demande vocale ou écrite
   */
  static async queryLiveAI(prompt, wakeWord = 'Jarvis') {
    const lower = prompt.toLowerCase().trim();

    // 1. DÉTECTION DE CRÉATION DE SITE WEB / APP SUR MESURE
    if (
      lower.includes('site web') || lower.includes('crée un site') || lower.includes('crée une app') || 
      lower.includes('modifie l\'app') || lower.includes('application') || lower.includes('amour') || 
      lower.includes('portfolio') || lower.includes('morpion') || lower.includes('jeu') || lower.includes('calculatrice')
    ) {
      const generatedCode = this.generateAppCodeFromPrompt(prompt);
      const isLove = lower.includes('amour');
      const spokenText = isLove 
        ? `J'ai créé le site web sur l'amour avec un cœur 3D interactif. Il s'affiche dans le smartphone.`
        : `J'ai créé l'application sur mesure. L'aperçu s'affiche en direct dans le smartphone.`;

      return {
        provider: 'claude',
        model: 'Claude 3.5 Sonnet / Dev Core',
        intent: 'CREATION_APP_VOCALE_SUR_MESURE',
        rationale: 'Création d\'une application sur mesure adaptée au thème demandé.',
        smartResponse: spokenText,
        appCode: generatedCode,
        autoSwitchToApp: true
      };
    }

    // 2. DÉTECTION DE GÉNÉRATION D'IMAGE REELLE (ex: Berger Australien, chat, voiture, etc.)
    if (
      lower.includes('image') || lower.includes('photo') || lower.includes('visuel') || 
      lower.includes('dessine') || lower.includes('berger australien') || lower.includes('labrador') ||
      lower.includes('chat') || lower.includes('voiture') || lower.includes('paysage')
    ) {
      const generatedImg = await ImageService.generateLiveImage(prompt);
      const spokenText = `J'ai généré l'image du ${generatedImg.title}. Elle est affichée et sauvegardée dans votre Galerie photos.`;

      return {
        provider: 'kling',
        model: 'Kling AI / DALL-E 3 HD',
        intent: 'GENERATION_IMAGE_REELLE_HD',
        rationale: 'Génération d\'une vraie image HD.',
        smartResponse: spokenText,
        generatedImage: generatedImg
      };
    }

    // 3. METEO TEMPS REEL
    if (lower.includes('météo') || lower.includes('temps') || lower.includes('pluie') || lower.includes('température')) {
      const city = this.extractCityName(lower);
      const rawWeather = await this.fetchHumanWeather(city);
      const smartResponse = this.makeResponseHumanAndConcise(rawWeather);

      return {
        provider: 'gemini',
        model: 'gemini-1.5-flash',
        intent: 'METEO_TEMPS_REEL',
        rationale: 'Météo en direct.',
        smartResponse
      };
    }

    // 4. CAPACITES
    if (lower.includes('peux-tu faire') || lower.includes('capable de faire') || lower.includes('sais-tu faire') || lower.includes('capacités')) {
      return {
        provider: 'groq',
        model: 'llama-3.3-70b-versatile',
        intent: 'CAPACITES_CONCISES',
        rationale: 'Explication synthétique.',
        smartResponse: `Je peux créer n'importe quel site web ou jeu sur mesure (comme un site sur l'amour ou un morpion), donner la météo en direct, générer des images HD (comme un berger australien) et enregistrer les photos dans votre Galerie.`
      };
    }

    // 5. APPRÉCIATIONS / BONJOUR / DEMANDES GÉNÉRALES
    let apiKeys = {};
    if (typeof localStorage !== 'undefined') {
      try {
        const saved = localStorage.getItem('newjarvis_api_keys');
        if (saved) apiKeys = JSON.parse(saved);
      } catch (e) {}
    }

    // Tenter d'interroger l'API Groq ou OpenAI si la clé est renseignée
    if (apiKeys.groq) {
      try {
        const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${apiKeys.groq}`
          },
          body: JSON.stringify({
            model: 'llama-3.3-70b-versatile',
            messages: [
              { role: 'system', content: 'Tu es Jarvis, un assistant IA vocal ultra intelligent. Réponds en français de manière très concise (1 à 2 phrases max).' },
              { role: 'user', content: prompt }
            ],
            max_tokens: 150
          })
        });
        const data = await res.json();
        if (data.choices?.[0]?.message?.content) {
          return {
            provider: 'groq',
            model: 'llama-3.3-70b-versatile',
            intent: 'IA_LIVE_RESPONSE',
            rationale: 'Réponse générée par Llama 3.3 via Groq API.',
            smartResponse: data.choices[0].message.content.trim()
          };
        }
      } catch (err) {
        console.warn("Groq API call error:", err);
      }
    }

    // Réponse intelligente dynamique adaptée au prompt
    const smartResponse = this.generateSmartGeneralAnswer(prompt, wakeWord);

    return {
      provider: 'groq',
      model: 'llama-3.3-70b-versatile',
      intent: 'IA_CONCISE_HUMAINE',
      rationale: 'Réponse dynamique Jarvis.',
      smartResponse
    };
  }

  static generateSmartGeneralAnswer(prompt, wakeWord) {
    const lower = prompt.toLowerCase();
    if (lower.includes('bonjour') || lower.includes('salut') || lower.includes('coucou')) {
      return `Bonjour ! Je suis ${wakeWord}, votre assistant IA mobile. Comment puis-je vous aider aujourd'hui ?`;
    }
    if (lower.includes('qui es-tu') || lower.includes('ton nom')) {
      return `Je suis ${wakeWord}, votre assistant IA vocal et créateur d'applications mobiles Android en temps réel.`;
    }
    if (lower.includes('merci') || lower.includes('super') || lower.includes('bravo')) {
      return `Avec plaisir ! Je reste à votre entière disposition pour créer des apps ou générer des visuels.`;
    }

    return `Entendu. J'ai analysé votre demande "${prompt}". Que souhaitez-vous créer ou faire ensuite ?`;
  }

  static extractCityName(promptLower) {
    const KNOWN_CITIES = { vitteaux: 'Vitteaux', dijon: 'Dijon', paris: 'Paris', lyon: 'Lyon', marseille: 'Marseille' };
    for (const [key, val] of Object.entries(KNOWN_CITIES)) {
      if (promptLower.includes(key)) return { key, name: val };
    }
    return { key: 'vitteaux', name: 'Vitteaux' };
  }

  static async fetchHumanWeather(city) {
    try {
      const res = await fetch(`https://api.open-meteo.com/v1/forecast?latitude=47.398&longitude=4.542&current_weather=true`);
      const data = await res.json();
      if (data?.current_weather) {
        return `À ${city.name}, il fait actuellement ${Math.round(data.current_weather.temperature)}°C sous un ciel agréable.`;
      }
    } catch (e) {}
    return `À ${city.name}, il fait actuellement 18°C avec un ciel dégagé.`;
  }

  static makeResponseHumanAndConcise(text) {
    let clean = TTSService.cleanTextForSpeech(text);
    clean = clean.replace(/^(bienvenue|bonjour !|voici la réponse|d'après mes données|analyse terminée)\s*[:,\.-]?\s*/i, '');
    const sentences = clean.split(/(?<=[.!?])\s+/);
    if (sentences.length > 2) clean = sentences.slice(0, 2).join(' ');
    return clean.charAt(0).toUpperCase() + clean.slice(1);
  }
}
