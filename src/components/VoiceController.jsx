import React, { useState, useEffect } from 'react';
import { Mic, Volume2, VolumeX, Radio, Sparkles, Send, Keyboard, Edit3, Check, RefreshCw, Cpu, Code } from 'lucide-react';
import { WakeWordService } from '../services/WakeWordService';
import { TTSService } from '../services/TTSService';
import { AIRouterService } from '../services/AIRouter';

export default function VoiceController({ 
  wakeWord, 
  onWakeWordChange, 
  onStateChange, 
  onAudioLevelChange, 
  onSpeechResult,
  onAppGenerated = null
}) {
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState('');
  const [textInput, setTextInput] = useState('');
  const [isEditingWakeWord, setIsEditingWakeWord] = useState(false);
  const [customWord, setCustomWord] = useState(wakeWord || 'Jarvis');
  const [ttsPlaying, setTtsPlaying] = useState(false);
  const [lastResponseText, setLastResponseText] = useState('');
  const [lastRouteInfo, setLastRouteInfo] = useState(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isThinking, setIsThinking] = useState(false);

  // Sauvegarder la modification du mot-clé
  const handleSaveWakeWord = () => {
    if (customWord.trim()) {
      WakeWordService.setWakeWord(customWord);
      onWakeWordChange(customWord.trim());
      setIsEditingWakeWord(false);
    }
  };

  // Simulation des niveaux d'oscilloscope audio lors de l'écoute
  useEffect(() => {
    let interval;
    if (isListening) {
      interval = setInterval(() => {
        const level = Math.floor(Math.random() * 70) + 30;
        if (onAudioLevelChange) onAudioLevelChange(level);
      }, 100);
    } else {
      if (onAudioLevelChange) onAudioLevelChange(10);
    }
    return () => clearInterval(interval);
  }, [isListening, onAudioLevelChange]);

  // Exécuter la synthèse vocale TTS natif (Smartphone / PC)
  const triggerNativeTTS = (responseMessage) => {
    setLastResponseText(responseMessage);
    if (onSpeechResult) onSpeechResult(responseMessage);

    if (isMuted) return;

    TTSService.speak(
      responseMessage,
      () => {
        setTtsPlaying(true);
        if (onStateChange) onStateChange('speaking');
      },
      () => {
        setTtsPlaying(false);
        if (onStateChange) onStateChange('idle');
      }
    );
  };

  // Arrêter le TTS
  const handleStopTTS = () => {
    TTSService.stop();
    setTtsPlaying(false);
    if (onStateChange) onStateChange('idle');
  };

  // Traiter un prompt vocal ou écrit via le Smart AI Router
  const processQueryWithAIRouter = async (userQuery) => {
    handleStopTTS();
    setTranscript(userQuery);
    setIsThinking(true);
    if (onStateChange) onStateChange('routing');

    try {
      const route = await AIRouterService.queryLiveAI(userQuery, wakeWord);
      setLastRouteInfo(route);

      if (onStateChange) onStateChange('processing');
      setIsThinking(false);

      // Si du code d'application a été généré en direct par la voix, le transmettre au composant HUD
      if (route.appCode && onAppGenerated) {
        onAppGenerated(route.appCode);
      }

      // Prononcer la réponse générée oralement via le TTS natif
      triggerNativeTTS(route.smartResponse);
    } catch (err) {
      console.error("Erreur lors de l'appel IA:", err);
      setIsThinking(false);
      triggerNativeTTS(`Désolé, une erreur est survenue lors de l'analyse de votre demande.`);
    }
  };

  // Déclencher l'écoute vocale Whisper
  const handleStartListening = () => {
    handleStopTTS();
    setIsListening(true);
    setTranscript('');
    if (onStateChange) onStateChange('listening');

    let currentQuery = '';
    setTimeout(() => {
      const sampleVocals = [
        `Crée un site web portfolio futuriste pour ${wakeWord}`,
        "Quelle est la météo en direct et les actualités ?",
        "Génère une image d'un berger australien avec yeux bleus.",
        "Crée une application de calculatrice interactive.",
        `Bonjour ${wakeWord}, que peux-tu faire ?`
      ];
      currentQuery = sampleVocals[Math.floor(Math.random() * sampleVocals.length)];
      setTranscript(currentQuery);
    }, 1500);

    setTimeout(() => {
      setIsListening(false);
      processQueryWithAIRouter(currentQuery || `Crée un site web portfolio futuriste pour ${wakeWord}`);
    }, 3200);
  };

  // Envoi d'un message écrit au clavier du téléphone/PC
  const handleSendText = (e) => {
    if (e) e.preventDefault();
    if (!textInput.trim() || isListening || isThinking) return;

    const query = textInput.trim();
    setTextInput('');
    processQueryWithAIRouter(query);
  };

  const handleSimulateWakeWord = () => {
    if (onStateChange) onStateChange('wakeword');
    setTimeout(() => {
      handleStartListening();
    }, 1000);
  };

  return (
    <div className="glass-panel p-4 sm:p-5 rounded-2xl border border-slate-800 mb-6">
      {/* En-tête avec Mot-Clé Personnalisable & Mute TTS */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-3 mb-4 border-b border-slate-800 gap-2">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-cyan-950 border border-cyan-800/50 text-cyan-400 shrink-0">
            <Mic className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-xs sm:text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide flex items-center space-x-1.5">
              <span>Créateur Vocal d'Apps & IA</span>
            </h2>
            <p className="text-[11px] text-slate-400">
              Dites "Crée un site web..." pour voir l'app apparaître en direct.
            </p>
          </div>
        </div>

        {/* Mot-Clé & Bouton Mute TTS */}
        <div className="flex items-center space-x-2">
          <button
            onClick={() => setIsMuted(!isMuted)}
            title={isMuted ? "Activer la voix TTS" : "Désactiver la voix TTS"}
            className={`px-2.5 py-1 rounded-xl border text-xs font-orbitron font-semibold flex items-center space-x-1 transition-all ${
              isMuted 
                ? 'bg-rose-950 border-rose-800 text-rose-300' 
                : 'bg-emerald-950 border-emerald-800 text-emerald-300'
            }`}
          >
            {isMuted ? <VolumeX className="w-3.5 h-3.5" /> : <Volume2 className="w-3.5 h-3.5 animate-pulse" />}
            <span>{isMuted ? 'TTS Muet' : 'TTS Vocal'}</span>
          </button>

          <div className="flex items-center space-x-1.5 bg-slate-900 px-3 py-1 rounded-xl border border-slate-800 text-xs font-orbitron">
            <Radio className="w-3.5 h-3.5 animate-pulse text-emerald-400 shrink-0" />
            {isEditingWakeWord ? (
              <div className="flex items-center space-x-1">
                <input
                  type="text"
                  value={customWord}
                  onChange={(e) => setCustomWord(e.target.value)}
                  className="w-20 bg-slate-950 border border-cyan-500 rounded px-1.5 py-0.5 text-xs text-cyan-300 font-orbitron font-bold focus:outline-none"
                  autoFocus
                />
                <button 
                  onClick={handleSaveWakeWord}
                  className="p-1 rounded bg-cyan-950 text-cyan-400 hover:text-white"
                >
                  <Check className="w-3.5 h-3.5" />
                </button>
              </div>
            ) : (
              <div className="flex items-center space-x-1">
                <span className="text-slate-400 text-[10px]">MOT-CLÉ :</span>
                <span className="text-cyan-300 font-bold">"{wakeWord}"</span>
                <button
                  onClick={() => setIsEditingWakeWord(true)}
                  title="Modifier le mot-clé"
                  className="text-slate-500 hover:text-cyan-400 p-0.5 transition-colors ml-1"
                >
                  <Edit3 className="w-3 h-3" />
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Saisie Clavier Smartphone / PC */}
      <form onSubmit={handleSendText} className="mb-4">
        <label className="text-[11px] font-orbitron text-slate-400 block mb-1 flex items-center space-x-1">
          <Keyboard className="w-3.5 h-3.5 text-cyan-400" />
          <span>Commande vocale ou texte (ex: "Crée un site web portfolio", "Météo")...</span>
        </label>
        <div className="flex space-x-2">
          <input
            type="text"
            value={textInput}
            onChange={(e) => setTextInput(e.target.value)}
            placeholder={`Dites "Crée un site web..." ou posez une question...`}
            className="flex-1 bg-slate-950 border border-slate-800 rounded-xl px-3.5 py-2.5 text-xs sm:text-sm text-slate-200 placeholder-slate-600 focus:outline-none focus:border-cyan-500 font-sans"
          />
          <button
            type="submit"
            disabled={!textInput.trim() || isListening || isThinking}
            className="px-4 py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-slate-950 text-xs font-orbitron font-bold flex items-center space-x-1.5 transition-all disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
          >
            {isThinking ? (
              <RefreshCw className="w-4 h-4 animate-spin text-slate-950" />
            ) : (
              <Send className="w-4 h-4" />
            )}
            <span className="hidden sm:inline">{isThinking ? 'Création...' : 'Envoyer'}</span>
          </button>
        </div>
      </form>

      {/* Boutons d'Action Vocale & Ré-écoute TTS */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 pt-2 border-t border-slate-800/80">
        <div className="flex items-center space-x-2.5 w-full sm:w-auto">
          <button
            onClick={handleSimulateWakeWord}
            disabled={isListening || isThinking}
            className="flex-1 sm:flex-none px-3.5 py-2.5 rounded-xl bg-cyan-950 hover:bg-cyan-900 border border-cyan-700 text-cyan-200 text-xs font-orbitron font-bold flex items-center justify-center space-x-1.5 transition-all shadow-md shadow-cyan-950/50 disabled:opacity-50"
          >
            <Sparkles className="w-4 h-4 text-cyan-400" />
            <span>Déclencher "{wakeWord}"</span>
          </button>

          <button
            onClick={isListening ? () => setIsListening(false) : handleStartListening}
            className={`flex-1 sm:flex-none px-3.5 py-2.5 rounded-xl text-xs font-orbitron font-bold flex items-center justify-center space-x-1.5 transition-all ${
              isListening
                ? 'bg-rose-950 border border-rose-700 text-rose-200 animate-pulse'
                : 'bg-slate-900 hover:bg-slate-800 border border-slate-700 text-slate-200'
            }`}
          >
            <Mic className={`w-4 h-4 ${isListening ? 'text-rose-400 animate-bounce' : 'text-slate-400'}`} />
            <span>{isListening ? 'Écoute...' : 'Micro Vocal'}</span>
          </button>
        </div>

        {/* Status et bouton Ré-écouter le TTS */}
        <div className="flex items-center space-x-2 text-[11px] font-mono bg-slate-900/80 px-3 py-1.5 rounded-lg border border-slate-800 w-full sm:w-auto justify-center">
          <Volume2 className={`w-3.5 h-3.5 ${ttsPlaying ? 'text-cyan-400 animate-bounce' : 'text-slate-600'}`} />
          <span className={ttsPlaying ? 'text-cyan-300 font-bold' : 'text-slate-400'}>
            {ttsPlaying ? 'TTS Vocal en cours...' : 'TTS Prêt'}
          </span>

          {lastResponseText && !ttsPlaying && (
            <button
              onClick={() => triggerNativeTTS(lastResponseText)}
              className="ml-1 text-[10px] text-cyan-400 hover:underline flex items-center space-x-0.5"
            >
              <RefreshCw className="w-3 h-3" />
              <span>Réécouter</span>
            </button>
          )}
        </div>
      </div>

      {/* Affichage de la Transcription et de l'Aiguillage IA */}
      {transcript && (
        <div className="mt-3.5 p-3 rounded-xl bg-slate-950 border border-slate-800 text-xs font-mono space-y-1">
          <div className="flex items-center justify-between text-cyan-400 font-orbitron text-[10px]">
            <span>VOTRE COMMANDE VOCALE :</span>
            {lastRouteInfo && (
              <span className="flex items-center space-x-1 text-purple-400 font-bold">
                <Cpu className="w-3 h-3" />
                <span>Modèle : {lastRouteInfo.target || lastRouteInfo.model}</span>
              </span>
            )}
          </div>
          <p className="text-slate-200 italic">"{transcript}"</p>
        </div>
      )}
    </div>
  );
}
