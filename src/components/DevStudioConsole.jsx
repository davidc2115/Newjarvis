import React, { useState } from 'react';
import { Code, Play, Download, ExternalLink, Sparkles, RefreshCw, Monitor, Smartphone, Terminal, FileCode, Check } from 'lucide-react';

const SAMPLE_PROJECTS = [
  {
    id: 'portfolio',
    title: 'Site Web Cyberpunk Jarvis',
    description: 'Landing page responsive avec néons, animations CSS et visualiseur réacteur.',
    html: `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>Jarvis Cyber Core</title>
  <style>
    body { background: #050b14; color: #00f0ff; font-family: 'Courier New', monospace; text-align: center; padding: 40px 20px; }
    h1 { font-size: 2.5rem; text-shadow: 0 0 20px #00f0ff; letter-spacing: 4px; }
    .reactor { width: 120px; height: 120px; border: 4px dashed #00f0ff; border-radius: 50%; margin: 30px auto; animation: spin 10s linear infinite; box-shadow: 0 0 30px #00f0ff; }
    @keyframes spin { 100% { transform: rotate(360deg); } }
    .btn { background: #00f0ff; color: #000; font-weight: bold; border: none; padding: 12px 24px; border-radius: 8px; cursor: pointer; text-transform: uppercase; font-family: inherit; }
    .btn:hover { background: #fff; box-shadow: 0 0 20px #fff; }
  </style>
</head>
<body>
  <h1>JARVIS CYBER CORE v3</h1>
  <div class="reactor"></div>
  <p>Tous les systèmes sont en ligne. Prêt pour le déploiement.</p>
  <button class="btn" onclick="alert('Système Jarvis Opérationnel !')">Activer le Réacteur</button>
</body>
</html>`
  },
  {
    id: 'calculator',
    title: 'Application Calculatrice Neumorphique',
    description: 'Interface de calculatrice scientifique interactive.',
    html: `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>Calculatrice Jarvis</title>
  <style>
    body { background: #0f172a; color: #fff; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; font-family: sans-serif; }
    .calc { background: #1e293b; padding: 20px; border-radius: 20px; border: 1px solid #334155; box-shadow: 0 10px 30px rgba(0,0,0,0.5); width: 260px; }
    #screen { width: 100%; height: 50px; background: #090d16; border: 1px solid #334155; border-radius: 10px; color: #00f0ff; font-size: 1.5rem; text-align: right; padding: 10px; box-sizing: border-box; margin-bottom: 15px; }
    .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
    button { background: #334155; color: #fff; border: none; padding: 15px; border-radius: 10px; font-size: 1.1rem; cursor: pointer; }
    button:hover { background: #00f0ff; color: #000; }
    .op { background: #0284c7; }
  </style>
</head>
<body>
  <div class="calc">
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
    function press(v) { if (s.value==='0') s.value=''; s.value += v; }
    function cls() { s.value='0'; }
    function calc() { try { s.value = eval(s.value); } catch(e) { s.value='Erreur'; } }
  </script>
</body>
</html>`
  }
];

export default function DevStudioConsole() {
  const [promptInput, setPromptInput] = useState('');
  const [activeCode, setActiveCode] = useState(SAMPLE_PROJECTS[0].html);
  const [isGenerating, setIsGenerating] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleGenerateApp = (e) => {
    if (e) e.preventDefault();
    if (!promptInput.trim()) return;

    setIsGenerating(true);

    setTimeout(() => {
      const generatedHtml = `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <title>${promptInput}</title>
  <style>
    body { background: #0a0f1d; color: #e2e8f0; font-family: 'Rajdhani', system-ui, sans-serif; padding: 40px; text-align: center; }
    .card { background: #131b2e; border: 1px solid #00f0ff; border-radius: 16px; padding: 30px; max-width: 500px; margin: 0 auto; box-shadow: 0 0 30px rgba(0,240,255,0.2); }
    h2 { color: #00f0ff; text-transform: uppercase; letter-spacing: 2px; }
    p { color: #94a3b8; line-height: 1.6; }
    .btn { background: linear-gradient(135deg, #00f0ff, #7c3aed); color: #fff; border: none; padding: 12px 28px; font-weight: bold; border-radius: 8px; cursor: pointer; }
  </style>
</head>
<body>
  <div class="card">
    <h2>🚀 ${promptInput}</h2>
    <p>Application générée en direct par le Studio de Code Jarvis. Code source optimisé HTML5 / CSS3 / JavaScript.</p>
    <button class="btn" onclick="alert('Application Jarvis Opérationnelle !')">Lancer l'Application</button>
  </div>
</body>
</html>`;

      setActiveCode(generatedHtml);
      setIsGenerating(false);
      setPromptInput('');
    }, 1800);
  };

  const handleCopyCode = () => {
    navigator.clipboard.writeText(activeCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 3000);
  };

  const handleDownloadFile = () => {
    const blob = new Blob([activeCode], { type: 'text/html' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'jarvis_app_project.html';
    a.click();
  };

  return (
    <div className="glass-panel p-4 sm:p-6 rounded-2xl border border-slate-800 mb-6">
      {/* En-tête du Studio de Code */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between pb-4 mb-4 border-b border-slate-800 gap-3">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-cyan-950 border border-cyan-800/50 text-cyan-400">
            <Code className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide flex items-center space-x-2">
              <span>Studio de Code & Créateur d'Apps / Sites Web</span>
              <Sparkles className="w-4 h-4 text-cyan-400 animate-pulse" />
            </h2>
            <p className="text-xs text-slate-400">
              Générez, éditez et prévisualisez en direct des sites web et applications interactives.
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-2">
          <button
            onClick={handleCopyCode}
            className="px-3 py-1.5 rounded-lg bg-slate-900 border border-slate-700 text-xs font-orbitron font-semibold text-slate-300 hover:text-cyan-400 flex items-center space-x-1"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <FileCode className="w-3.5 h-3.5" />}
            <span>{copied ? 'Copié !' : 'Copier le Code'}</span>
          </button>

          <button
            onClick={handleDownloadFile}
            className="px-3 py-1.5 rounded-lg bg-cyan-950 hover:bg-cyan-900 border border-cyan-700 text-xs font-orbitron font-bold text-cyan-200 flex items-center space-x-1"
          >
            <Download className="w-3.5 h-3.5 text-cyan-400" />
            <span>Exporter .HTML</span>
          </button>
        </div>
      </div>

      {/* Saisie d'Instruction de Code */}
      <form onSubmit={handleGenerateApp} className="mb-6">
        <label className="text-xs font-orbitron text-slate-300 block mb-1.5 flex items-center space-x-1.5">
          <Terminal className="w-4 h-4 text-cyan-400" />
          <span>Demander à Jarvis de créer ou modifier une App / Site Web :</span>
        </label>
        <div className="flex space-x-2">
          <input
            type="text"
            value={promptInput}
            onChange={(e) => setPromptInput(e.target.value)}
            placeholder="Ex: Crée un jeu d'arcade Snake en JavaScript, Crée une Landing Page Cyberpunk..."
            className="flex-1 bg-slate-950 border border-slate-800 rounded-xl px-4 py-2.5 text-xs sm:text-sm text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-mono"
          />
          <button
            type="submit"
            disabled={!promptInput.trim() || isGenerating}
            className="px-5 py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-slate-950 text-xs font-orbitron font-bold flex items-center space-x-2 transition-all disabled:opacity-40 shrink-0 shadow-lg shadow-cyan-950/50"
          >
            {isGenerating ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Sparkles className="w-4 h-4" />}
            <span>{isGenerating ? 'Génération...' : 'Créer l\'App'}</span>
          </button>
        </div>
      </form>

      {/* Projets d'Exemple Rapides */}
      <div className="flex items-center space-x-2 mb-4 overflow-x-auto pb-1">
        <span className="text-[11px] font-orbitron text-slate-400 shrink-0">Projets Rapides :</span>
        {SAMPLE_PROJECTS.map((proj) => (
          <button
            key={proj.id}
            onClick={() => setActiveCode(proj.html)}
            className="px-3 py-1 rounded-lg bg-slate-900/80 hover:bg-slate-800 border border-slate-800 text-[11px] font-mono text-cyan-300 whitespace-nowrap"
          >
            {proj.title}
          </button>
        ))}
      </div>

      {/* Grille 2 Colonnes : Éditeur de Code vs Aperçu en Direct */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Colonne Gauche : Éditeur de Code Source */}
        <div className="flex flex-col bg-slate-950 rounded-xl border border-slate-800 overflow-hidden">
          <div className="px-3.5 py-2 bg-slate-900 border-b border-slate-800 flex items-center justify-between">
            <span className="text-[11px] font-orbitron text-slate-300 flex items-center space-x-1.5">
              <FileCode className="w-3.5 h-3.5 text-cyan-400" />
              <span>CODE SOURCE (index.html)</span>
            </span>
            <span className="text-[10px] font-mono text-emerald-400">ÉDITEUR EN DIRECT</span>
          </div>

          <textarea
            value={activeCode}
            onChange={(e) => setActiveCode(e.target.value)}
            className="w-full h-80 bg-slate-950 text-cyan-300 p-3.5 font-mono text-xs focus:outline-none resize-none leading-relaxed"
            spellCheck={false}
          />
        </div>

        {/* Colonne Droite : Aperçu Interactif en Temps Réel */}
        <div className="flex flex-col bg-slate-950 rounded-xl border border-slate-800 overflow-hidden">
          <div className="px-3.5 py-2 bg-slate-900 border-b border-slate-800 flex items-center justify-between">
            <span className="text-[11px] font-orbitron text-slate-300 flex items-center space-x-1.5">
              <Monitor className="w-3.5 h-3.5 text-cyan-400" />
              <span>APERÇU EN DIRECT (Rendu HTML/CSS/JS)</span>
            </span>
            <span className="text-[10px] font-mono text-cyan-400">EXECUTING SANDBOX</span>
          </div>

          <iframe
            srcDoc={activeCode}
            title="Jarvis App Live Preview"
            className="w-full h-80 bg-white border-0"
            sandbox="allow-scripts allow-modals"
          />
        </div>
      </div>
    </div>
  );
}
