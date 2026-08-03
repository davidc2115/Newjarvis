import React from 'react';
import { Code, Terminal, FileText, Layers, CheckCircle2, Copy } from 'lucide-react';

const STRUCTURE_NODES = [
  { path: 'android/app/build.gradle', type: 'Configuration', desc: 'Dépendances natives, signature Keystore & options APK release' },
  { path: 'src/assets/models/ggml-tiny.bin', type: 'Modèle', desc: 'Modèle GGUF local Whisper pour la transcription sans internet' },
  { path: 'src/services/WakeWordService.ts', type: 'Service', desc: 'Moteur Porcupine pour la détection du mot-clé "Hey Jarvis"' },
  { path: 'src/services/WhisperService.ts', type: 'Service', desc: 'Pont whisper.rn pour exécuter le STT localement sur GPU/NPU' },
  { path: 'src/services/AIRouter.ts', type: 'Service', desc: 'Dispatch logique vers Groq, OpenAI, Gemini, Claude, Mistral...' },
  { path: 'src/services/TTSService.ts', type: 'Service', desc: 'Synthèse vocale via Android TextToSpeech natif' },
  { path: 'src/components/JarvisHUD.tsx', type: 'Interface', desc: 'Visualiseur SVG/Reanimated réactif avec 6 variantes de couleur' },
  { path: 'src/components/ApiKeyManager.tsx', type: 'Interface', desc: 'Clés chiffrées AES-256 et liens directs vers les portails dev' },
  { path: 'src/services/MediaSaver.ts', type: 'Service', desc: 'Enregistrement des images dans la galerie MediaStore d\'Android' }
];

export default function ReactNativeArchitecture() {
  return (
    <div className="glass-panel p-5 rounded-2xl border border-slate-800 mb-6">
      <div className="flex items-center justify-between pb-3 mb-4 border-b border-slate-800">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-emerald-950 border border-emerald-800/50 text-emerald-400">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide">
              Structure du Code Source React Native (APK Android)
            </h2>
            <p className="text-xs text-slate-400">
              Architecture modulaire prête pour la compilation de l'APK Release.
            </p>
          </div>
        </div>

        <span className="text-[10px] font-orbitron px-2.5 py-1 rounded-full bg-emerald-950 text-emerald-300 border border-emerald-800">
          REACT NATIVE v0.74+
        </span>
      </div>

      {/* Arborescence des Fichiers */}
      <div className="space-y-2 mb-4">
        {STRUCTURE_NODES.map((node, i) => (
          <div key={i} className="p-2.5 rounded-xl bg-slate-900/80 border border-slate-800 flex items-center justify-between text-xs font-mono">
            <div className="flex items-center space-x-2.5">
              <FileText className="w-4 h-4 text-cyan-400 shrink-0" />
              <span className="text-slate-200 font-bold">{node.path}</span>
            </div>
            <div className="flex items-center space-x-3">
              <span className="text-[10px] text-slate-400 hidden sm:inline">{node.desc}</span>
              <span className="text-[9px] font-orbitron px-2 py-0.5 rounded bg-slate-950 text-cyan-300 border border-slate-800">
                {node.type}
              </span>
            </div>
          </div>
        ))}
      </div>

      {/* Instructions de Compilation de l'APK */}
      <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800">
        <div className="flex items-center space-x-2 text-xs font-orbitron text-amber-400 mb-2">
          <Terminal className="w-4 h-4" />
          <span>COMMANDES DE COMPILATION DE L'APK RELEASE ANDROID :</span>
        </div>

        <pre className="p-3 rounded-lg bg-slate-900 text-cyan-300 text-[11px] font-mono overflow-x-auto border border-slate-800">
{`# 1. Installation des dépendances React Native
npm install @picovoice/porcupine-react-native whisper.rn react-native-tts @react-native-camera-roll/camera-roll react-native-encrypted-storage

# 2. Compilation de l'APK Release Android
cd android
./gradlew assembleRelease

# L'APK sera généré dans :
# android/app/build/outputs/apk/release/app-release.apk`}
        </pre>
      </div>
    </div>
  );
}
