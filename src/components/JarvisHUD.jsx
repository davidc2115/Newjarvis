import React, { useState, useEffect } from 'react';
import { Layers, Sparkles, Circle, Cpu, Zap, Globe, Monitor, Code, Smartphone, Wifi, Battery, Signal } from 'lucide-react';

const THEME_STYLES = {
  galaxy: {
    primary: '#00f0ff',
    secondary: '#a855f7',
    tertiary: '#ff0055',
    glow: 'rgba(0, 240, 255, 0.6)',
    glowDeep: 'rgba(168, 85, 247, 0.5)',
    bgGlow: 'radial-gradient(circle, rgba(0,240,255,0.3) 0%, rgba(168,85,247,0.2) 40%, rgba(255,0,85,0.1) 70%, transparent 90%)',
    sphericalGradient: 'radial-gradient(circle at 35% 35%, #ffffff 0%, #00f0ff 25%, #a855f7 55%, #ff0055 85%, #09090b 100%)',
  },
  blue: {
    primary: '#00f0ff',
    secondary: '#0077ff',
    glow: 'rgba(0, 240, 255, 0.5)',
    bgGlow: 'radial-gradient(circle, rgba(0,240,255,0.25) 0%, rgba(0,119,255,0.05) 60%, transparent 80%)',
    sphericalGradient: 'radial-gradient(circle at 35% 35%, #ffffff 0%, #00f0ff 25%, #0055ff 60%, #001133 90%)',
  },
  red: {
    primary: '#ff0055',
    secondary: '#ff3300',
    glow: 'rgba(255, 0, 85, 0.5)',
    bgGlow: 'radial-gradient(circle, rgba(255,0,85,0.25) 0%, rgba(255,51,0,0.05) 60%, transparent 80%)',
    sphericalGradient: 'radial-gradient(circle at 35% 35%, #ffffff 0%, #ff0055 25%, #990022 60%, #330005 90%)',
  },
  purple: {
    primary: '#c084fc',
    secondary: '#7c3aed',
    glow: 'rgba(192, 132, 252, 0.5)',
    bgGlow: 'radial-gradient(circle, rgba(192,132,252,0.25) 0%, rgba(124,58,237,0.05) 60%, transparent 80%)',
    sphericalGradient: 'radial-gradient(circle at 35% 35%, #ffffff 0%, #c084fc 25%, #6d28d9 60%, #2e1065 90%)',
  },
  green: {
    primary: '#34d399',
    secondary: '#059669',
    glow: 'rgba(52, 211, 153, 0.5)',
    bgGlow: 'radial-gradient(circle, rgba(52,211,153,0.25) 0%, rgba(5,150,105,0.05) 60%, transparent 80%)',
    sphericalGradient: 'radial-gradient(circle at 35% 35%, #ffffff 0%, #34d399 25%, #047857 60%, #022c22 90%)',
  },
  orange: {
    primary: '#fb923c',
    secondary: '#ea580c',
    glow: 'rgba(251, 146, 60, 0.5)',
    bgGlow: 'radial-gradient(circle, rgba(251,146,60,0.25) 0%, rgba(234,88,12,0.05) 60%, transparent 80%)',
    sphericalGradient: 'radial-gradient(circle at 35% 35%, #ffffff 0%, #fb923c 25%, #c2410c 60%, #431407 90%)',
  }
};

const DEFAULT_LOVE_APP = `<!DOCTYPE html>
<html lang="fr">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>L'Amour - Jarvis</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: linear-gradient(135deg, #1a051d, #3b0764, #1e1b4b); color: #f472b6; font-family: 'Rajdhani', system-ui, sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; text-align: center; }
    .card { background: rgba(15, 23, 42, 0.85); border: 2px solid #ec4899; border-radius: 24px; padding: 25px; max-width: 320px; width: 100%; box-shadow: 0 0 35px rgba(236,72,153,0.4); backdrop-filter: blur(16px); }
    h1 { font-size: 1.6rem; color: #fff; text-shadow: 0 0 15px #ec4899; margin-bottom: 10px; }
    p { color: #cbd5e1; font-size: 0.85rem; margin-bottom: 20px; line-height: 1.5; }
    .heart-3d { width: 70px; height: 70px; background: #ec4899; margin: 25px auto; position: relative; transform: rotate(-45deg); animation: pulse 1.2s ease-in-out infinite; box-shadow: 0 0 30px #ec4899; }
    .heart-3d:before, .heart-3d:after { content: ""; width: 70px; height: 70px; background: #ec4899; border-radius: 50%; position: absolute; }
    .heart-3d:before { top: -35px; left: 0; }
    .heart-3d:after { left: 35px; top: 0; }
    @keyframes pulse { 0%, 100% { transform: rotate(-45deg) scale(1); } 50% { transform: rotate(-45deg) scale(1.15); } }
    .btn { background: linear-gradient(135deg, #ec4899, #f43f5e); color: #fff; font-weight: bold; border: none; padding: 12px 20px; border-radius: 10px; font-size: 0.9rem; cursor: pointer; text-transform: uppercase; }
  </style>
</head>
<body>
  <div class="card">
    <h1>💖 L'Amour par Jarvis</h1>
    <div style="height:30px;"></div>
    <div class="heart-3d"></div>
    <p>Site web interactif sur l'amour généré en direct par Jarvis.</p>
    <button class="btn" onclick="alert('L\'amour est l\'énergie la plus puissante du monde !')">Pensée d'Amour</button>
  </div>
</body>
</html>`;

export default function JarvisHUD({ 
  colorTheme = 'galaxy', 
  state = 'idle', 
  audioLevel = 50, 
  wakeWord = 'Jarvis',
  liveAppCode = null,
  activeViewMode = 'orb',
  onViewModeChange = null
}) {
  const [viewMode, setViewMode] = useState(activeViewMode || 'orb');
  const [orbStyle, setOrbStyle] = useState('galacticSwarm3d');

  useEffect(() => {
    if (activeViewMode) setViewMode(activeViewMode);
  }, [activeViewMode]);

  useEffect(() => {
    if (liveAppCode) setViewMode('appPreview');
  }, [liveAppCode]);

  const theme = THEME_STYLES[colorTheme] || THEME_STYLES.galaxy;

  const isProcessing = state === 'processing' || state === 'routing';
  const isListening = state === 'listening' || state === 'wakeword';
  const isSpeaking = state === 'speaking';

  const pulseScale = isListening ? 1 + (audioLevel / 180) : isSpeaking ? 1.12 : 1;

  const particleNodes = Array.from({ length: 24 }).map((_, i) => {
    const angle = (i * 360) / 24;
    const radius = 65 + (i % 5) * 16;
    const color = i % 3 === 0 ? theme.primary : i % 3 === 1 ? theme.secondary : (theme.tertiary || theme.primary);
    return { angle, radius, color, size: 3 + (i % 4) };
  });

  const handleToggleMode = (mode) => {
    setViewMode(mode);
    if (onViewModeChange) onViewModeChange(mode);
  };

  return (
    <div className="relative flex flex-col items-center justify-center p-2 sm:p-4 my-1 w-full max-w-2xl mx-auto">
      {/* Barre Supérieure de Commutation : Mode Orbe 3D vs Simulateur Smartphone Live */}
      <div className="w-full flex items-center justify-between mb-3 bg-slate-900/90 p-1.5 rounded-xl border border-slate-800 backdrop-blur-md">
        <div className="flex items-center space-x-1">
          <button
            onClick={() => handleToggleMode('orb')}
            className={`px-3 py-1.5 rounded-lg text-xs font-orbitron font-bold flex items-center space-x-1.5 transition-all ${
              viewMode === 'orb'
                ? 'bg-cyan-950 border border-cyan-500/60 text-cyan-300 shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Sparkles className="w-3.5 h-3.5" />
            <span>Orbe 3D Galactique</span>
          </button>

          <button
            onClick={() => handleToggleMode('appPreview')}
            className={`px-3 py-1.5 rounded-lg text-xs font-orbitron font-bold flex items-center space-x-1.5 transition-all ${
              viewMode === 'appPreview'
                ? 'bg-purple-950 border border-purple-500/60 text-purple-300 shadow-md'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Smartphone className="w-3.5 h-3.5" />
            <span>Écran Smartphone Live 📱</span>
          </button>
        </div>

        {viewMode === 'orb' && (
          <select
            value={orbStyle}
            onChange={(e) => setOrbStyle(e.target.value)}
            className="bg-slate-950 border border-slate-800 text-[11px] font-orbitron text-cyan-300 rounded-lg px-2 py-1 focus:outline-none cursor-pointer"
          >
            <option value="galacticSwarm3d">Galaxie 3D</option>
            <option value="techenclair3d">Techenclair 3D</option>
            <option value="supernova">Supernova</option>
            <option value="arcReactor">Réacteur HUD</option>
          </select>
        )}
      </div>

      {/* Halo d'Arrière-Plan Volumétrique Multi-Couleurs */}
      <div 
        className="absolute w-72 h-72 sm:w-96 sm:h-96 rounded-full pointer-events-none transition-all duration-700 opacity-90"
        style={{ background: theme.bgGlow }}
      />

      {/* VUE 1 : ORBE 3D GALACTIQUE DE JARVIS */}
      {viewMode === 'orb' && (
        <div className="relative w-64 h-64 sm:w-80 sm:h-80 flex items-center justify-center my-2 transition-all">
          <div 
            className={`absolute w-72 h-72 sm:w-84 sm:h-84 rounded-full border-2 border-dashed transition-all duration-500 ${
              isProcessing ? 'animate-spin' : 'animate-spin-slow'
            }`}
            style={{ 
              borderColor: theme.primary, 
              opacity: 0.7,
              transform: 'rotateX(75deg) rotateY(25deg) scale(' + pulseScale + ')',
              boxShadow: `0 0 25px ${theme.glow}`
            }}
          />
          <div 
            className="absolute w-64 h-64 sm:w-76 sm:h-76 rounded-full border-2 border-dotted animate-spin-reverse"
            style={{ 
              borderColor: theme.secondary, 
              opacity: 0.6,
              transform: 'rotateX(35deg) rotateY(-50deg)'
            }}
          />
          <div 
            className="absolute w-64 h-64 sm:w-76 sm:h-76 rounded-full animate-spin-slow"
            style={{ transform: 'rotateX(60deg) rotateZ(120deg)' }}
          >
            {particleNodes.map((p, idx) => {
              const x = 128 + p.radius * Math.cos((p.angle * Math.PI) / 180);
              const y = 128 + p.radius * Math.sin((p.angle * Math.PI) / 180);
              return (
                <div 
                  key={idx}
                  className="absolute rounded-full shadow-lg transition-all duration-300"
                  style={{
                    left: `${x}px`,
                    top: `${y}px`,
                    width: `${p.size}px`,
                    height: `${p.size}px`,
                    backgroundColor: p.color,
                    boxShadow: `0 0 10px ${p.color}`
                  }}
                />
              );
            })}
          </div>

          <div 
            className="relative w-44 h-44 sm:w-52 sm:h-52 rounded-full transition-transform duration-300 flex items-center justify-center shadow-2xl"
            style={{ 
              background: theme.sphericalGradient,
              transform: `scale(${pulseScale})`,
              boxShadow: `0 0 50px ${theme.glow}, inset -12px -12px 25px rgba(0,0,0,0.85)`
            }}
          >
            <div 
              className={`w-24 h-24 sm:w-28 sm:h-28 rounded-full transition-all duration-300 ${
                isSpeaking ? 'animate-ping' : 'animate-pulse-glow'
              }`}
              style={{ 
                background: `radial-gradient(circle, #ffffff 0%, ${theme.primary} 40%, ${theme.secondary} 75%, transparent 100%)`,
                boxShadow: `0 0 40px ${theme.primary}`
              }}
            />
            <div className="absolute top-4 left-7 w-12 h-7 bg-white/50 rounded-full blur-[2px] transform -rotate-45 pointer-events-none" />
          </div>
        </div>
      )}

      {/* VUE 2 : RENDER REEL CHÂSSIS SMARTPHONE ANDROID / IPHONE (NOTCH & BARRE D'ETAT 5G) */}
      {viewMode === 'appPreview' && (
        <div className="relative w-72 sm:w-80 h-[460px] sm:h-[520px] rounded-[40px] p-3 bg-slate-900 border-4 border-slate-700 shadow-2xl shadow-purple-950/60 my-2 flex flex-col justify-between items-center transition-all">
          {/* Encoche Caméra Notch Smartphone */}
          <div className="w-28 h-4 bg-black rounded-b-xl absolute top-3 z-30 flex items-center justify-center space-x-2">
            <span className="w-2 h-2 rounded-full bg-slate-800" />
            <span className="w-2 h-2 rounded-full bg-blue-900" />
          </div>

          {/* Barre d'État Mobile 5G / Heure / Batterie */}
          <div className="w-full pt-1 px-4 flex items-center justify-between text-[9px] font-mono text-slate-400 z-20">
            <span>15:42</span>
            <div className="flex items-center space-x-1">
              <Signal className="w-2.5 h-2.5" />
              <Wifi className="w-2.5 h-2.5" />
              <Battery className="w-3 h-3 text-emerald-400" />
            </div>
          </div>

          {/* ÉCRAN TACTILE RÉEL DU SMARTPHONE (SANDBOX IFRAME 19:9) */}
          <div className="w-full h-full bg-black rounded-[28px] overflow-hidden my-1 border border-slate-800 relative z-10 shadow-inner">
            <iframe
              srcDoc={liveAppCode || DEFAULT_LOVE_APP}
              title="Aperçu Réel Écran Smartphone"
              className="w-full h-full border-0 bg-slate-950"
              sandbox="allow-scripts allow-modals"
            />
          </div>

          {/* Bouton Accueil Home Bar Smartphone */}
          <div className="w-32 h-1 bg-slate-500 rounded-full mt-1 z-20" />
        </div>
      )}

      {/* Badge d'État du Système avec Mot-Clé Personnalisé */}
      <div className="mt-3 flex items-center space-x-2.5 px-3.5 py-1.5 rounded-full border border-slate-800 bg-slate-900/90 backdrop-blur-md max-w-full text-center">
        <span 
          className="w-2.5 h-2.5 rounded-full animate-ping shrink-0"
          style={{ backgroundColor: theme.primary }}
        />
        <span className="text-[10px] sm:text-xs uppercase tracking-wider font-orbitron font-bold text-slate-200 truncate">
          {state === 'idle' && `VEILLE - DÉCLENCHEUR "${wakeWord.toUpperCase()}"`}
          {state === 'wakeword' && `MOT-CLÉ "${wakeWord.toUpperCase()}" DÉTECTÉ !`}
          {state === 'listening' && 'WHISPER LOCAL : TRANSCRIPTION HORS-LIGNE...'}
          {state === 'routing' && 'ROUTAGE IA & SMARTPHONE EN COURS...'}
          {state === 'processing' && 'RENDU DU SITE SUR LE SMARTPHONE...'}
          {state === 'speaking' && 'SYNTHÈSE VOCALE TTS ACTIVE...'}
        </span>
      </div>
    </div>
  );
}
