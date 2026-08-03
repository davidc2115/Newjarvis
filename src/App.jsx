import React, { useState } from 'react';
import JarvisHUD from './components/JarvisHUD';
import ThemeSelector from './components/ThemeSelector';
import ApiKeyManager from './components/ApiKeyManager';
import AIRouterConsole from './components/AIRouterConsole';
import VoiceController from './components/VoiceController';
import MediaGallery from './components/MediaGallery';
import DevStudioConsole from './components/DevStudioConsole';
import ApkModifierConsole from './components/ApkModifierConsole';
import ReactNativeArchitecture from './components/ReactNativeArchitecture';
import { WakeWordService } from './services/WakeWordService';
import { Cpu, ShieldCheck, Activity, Smartphone, Github, Play, MessageSquare, Code, Wrench } from 'lucide-react';

export default function App() {
  const [colorTheme, setColorTheme] = useState('galaxy');
  const [wakeWord, setWakeWord] = useState(() => WakeWordService.getWakeWord());
  const [hudState, setHudState] = useState('idle'); // idle, wakeword, listening, routing, processing, speaking
  const [audioLevel, setAudioLevel] = useState(15);
  const [activeTab, setActiveTab] = useState('hud'); // hud, devstudio, apkmodifier, keys, router, gallery, architecture
  const [lastSpeech, setLastSpeech] = useState('');
  const [liveAppCode, setLiveAppCode] = useState(null);
  const [hudViewMode, setHudViewMode] = useState('orb');

  const handleAppGeneratedFromVoice = (code) => {
    setLiveAppCode(code);
    setActiveTab('hud'); // Basculer sur l'écran principal pour voir l'aperçu à la place de l'orbe
    setHudViewMode('appPreview');
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col justify-between selection:bg-cyan-500 selection:text-black pb-20 sm:pb-6">
      {/* En-tête / Mobile Top Bar */}
      <header className="border-b border-slate-800/80 bg-slate-900/80 backdrop-blur-xl sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-3.5 py-3 flex items-center justify-between">
          <div className="flex items-center space-x-2.5">
            <div className="relative">
              <span className="w-2.5 h-2.5 rounded-full bg-cyan-400 block animate-ping absolute top-0 right-0" />
              <div className="p-1.5 rounded-xl bg-slate-950 border border-cyan-500/40 text-cyan-400 font-orbitron font-extrabold text-xs">
                NJ
              </div>
            </div>
            <div>
              <h1 className="text-xs sm:text-base font-orbitron font-black tracking-wider text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 via-purple-400 via-pink-400 to-amber-400">
                NEWJARVIS MOBILE
              </h1>
              <p className="text-[9px] sm:text-[10px] text-slate-400 font-mono">
                Créateur d'Apps & Modificateur d'APK Android • Orbe 3D
              </p>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            <a
              href="https://github.com/Davidc2115/Newjarvis"
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center space-x-1.5 px-2.5 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 border border-slate-700 text-[11px] font-orbitron font-semibold text-slate-300 transition-all"
            >
              <Github className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">Davidc2115/Newjarvis</span>
            </a>
          </div>
        </div>
      </header>

      {/* Barre d'Onglets Horizontale Optimisée Smartphone */}
      <div className="bg-slate-950 border-b border-slate-800/80 sticky top-[53px] z-40">
        <div className="max-w-7xl mx-auto px-2 flex items-center space-x-1.5 overflow-x-auto py-2 scrollbar-none">
          {[
            { id: 'hud', label: 'Orbe 3D', icon: Activity },
            { id: 'devstudio', label: 'Studio Code 💻', icon: Code },
            { id: 'apkmodifier', label: 'Modifier APK 📦', icon: Wrench },
            { id: 'keys', label: 'Clés API', icon: ShieldCheck },
            { id: 'router', label: 'Aiguillage IA', icon: Cpu },
            { id: 'gallery', label: 'Galerie', icon: Smartphone },
            { id: 'architecture', label: 'Code Native', icon: Play },
          ].map((tab) => {
            const IconComp = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`px-3 py-1.5 rounded-xl text-[11px] font-orbitron font-semibold flex items-center space-x-1.5 whitespace-nowrap transition-all touch-manipulation ${
                  isActive
                    ? 'bg-cyan-950 border border-cyan-500/60 text-cyan-300 shadow-md shadow-cyan-950/50'
                    : 'bg-slate-900/60 border border-slate-800/80 text-slate-400 hover:text-slate-200'
                }`}
              >
                <IconComp className="w-3.5 h-3.5" />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Zone de Contenu Principal */}
      <main className="max-w-7xl mx-auto w-full px-3 sm:px-4 py-3 sm:py-6 flex-1">
        {/* Onglet 1 : Visualiseur HUD Orbe 3D Galactique & Aperçu Live à la place de l'Orbe */}
        {activeTab === 'hud' && (
          <div className="space-y-4 sm:space-y-6">
            {/* Sélecteur de Thème de Couleur Multi-Couleurs & Galaxie */}
            <ThemeSelector activeColor={colorTheme} onChangeColor={setColorTheme} />

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 sm:gap-6 items-start">
              {/* Colonne Gauche : Saisie Clavier Smartphone & Contrôleur Vocal avec Mot-Clé Personnalisable */}
              <div className="lg:col-span-1 space-y-4">
                <VoiceController
                  wakeWord={wakeWord}
                  onWakeWordChange={setWakeWord}
                  onStateChange={setHudState}
                  onAudioLevelChange={setAudioLevel}
                  onSpeechResult={setLastSpeech}
                  onAppGenerated={handleAppGeneratedFromVoice}
                />

                {lastSpeech && (
                  <div className="glass-panel p-3.5 rounded-xl border border-cyan-500/30 text-xs font-mono">
                    <div className="flex items-center space-x-1.5 text-cyan-400 font-orbitron font-bold mb-1">
                      <MessageSquare className="w-3.5 h-3.5" />
                      <span>RÉPONSE VOCALE ({wakeWord.toUpperCase()}) :</span>
                    </div>
                    <p className="text-slate-200 italic">"{lastSpeech}"</p>
                  </div>
                )}
              </div>

              {/* Colonne Centrale : Orbe 3D Galactique OU Aperçu App Live à la place de l'Orbe */}
              <div className="lg:col-span-2 flex flex-col items-center justify-center glass-panel-glow rounded-3xl p-3 sm:p-6 border">
                <JarvisHUD
                  colorTheme={colorTheme}
                  state={hudState}
                  audioLevel={audioLevel}
                  wakeWord={wakeWord}
                  liveAppCode={liveAppCode}
                  activeViewMode={hudViewMode}
                  onViewModeChange={setHudViewMode}
                />
              </div>
            </div>
          </div>
        )}

        {/* Onglet 2 : Studio de Code & Créateur d'Apps/Sites Web */}
        {activeTab === 'devstudio' && <DevStudioConsole />}

        {/* Onglet 3 : Modificateur d'APK Android */}
        {activeTab === 'apkmodifier' && <ApkModifierConsole />}

        {/* Onglet 4 : Gestionnaire de Clés API */}
        {activeTab === 'keys' && <ApiKeyManager />}

        {/* Onglet 5 : Module d'Aiguillage d'IA */}
        {activeTab === 'router' && (
          <AIRouterConsole
            onSimulatePrompt={(p) => {
              setHudState('routing');
              setTimeout(() => setHudState('idle'), 2000);
            }}
          />
        )}

        {/* Onglet 6 : Galerie & Enregistrement des Médias */}
        {activeTab === 'gallery' && <MediaGallery />}

        {/* Onglet 7 : Architecture du Code React Native Android */}
        {activeTab === 'architecture' && <ReactNativeArchitecture />}
      </main>

      {/* BARRE DE NAVIGATION NATIVE EN BAS DE L'ÉCRAN SMARTPHONE (MOBILE BOTTOM NAVIGATION DOCK) */}
      <nav className="fixed bottom-0 left-0 right-0 bg-slate-950/95 backdrop-blur-2xl border-t border-slate-800/90 z-50 py-1.5 px-3">
        <div className="max-w-md mx-auto flex items-center justify-around">
          {[
            { id: 'hud', label: 'Orbe 3D', icon: Activity },
            { id: 'devstudio', label: 'Studio Code', icon: Code },
            { id: 'apkmodifier', label: 'Modif APK', icon: Wrench },
            { id: 'keys', label: 'Clés API', icon: ShieldCheck },
            { id: 'gallery', label: 'Galerie', icon: Smartphone }
          ].map((tab) => {
            const IconComp = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex flex-col items-center py-1 px-2 rounded-xl transition-all ${
                  isActive
                    ? 'text-cyan-400 font-bold scale-105'
                    : 'text-slate-500 hover:text-slate-300'
                }`}
              >
                <IconComp className={`w-5 h-5 ${isActive ? 'text-cyan-400 animate-pulse' : ''}`} />
                <span className="text-[9px] font-orbitron mt-0.5">{tab.label}</span>
              </button>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
