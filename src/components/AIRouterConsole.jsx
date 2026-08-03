import React, { useState } from 'react';
import { Cpu, Zap, Search, Image as ImageIcon, Music, Code, Compass, ArrowRight } from 'lucide-react';

const SAMPLE_PROMPTS = [
  {
    text: "Jarvis, quelle est la météo en direct à Paris et les dernières actus ?",
    intent: "Recherche Web Temps Réel",
    icon: Search,
    target: "Serper API + Google Gemini 1.5 Flash",
    reason: "Requête nécessitant des données web en direct et de l'information actualisée."
  },
  {
    text: "Écris un script Python complexe pour synchroniser deux bases de données SQL.",
    intent: "Raisonnement & Code Informatique",
    icon: Code,
    target: "OpenAI GPT-4o / Claude 3.5 Sonnet",
    reason: "Tâche de programmation complexe nécessitant une grande capacité de raisonnement."
  },
  {
    text: "Donne-moi une réponse ultra rapide sur la définition de la physique quantique.",
    intent: "Inférence Ultra-Rapide (Faible Latence)",
    icon: Zap,
    target: "Groq (Llama 3.3 70B)",
    reason: "Question directe demandant un temps de réponse instantané (< 200ms)."
  },
  {
    text: "Génère une image futuriste d'une armure Jarvis avec réacteur bleu lumineux.",
    intent: "Génération Visuelle (Image/Vidéo)",
    icon: ImageIcon,
    target: "Kling AI / OpenAI DALL-E 3",
    reason: "Commande de création artistique ou rendu graphique visuel."
  },
  {
    text: "Compose un morceau de musique électro synthwave de 30 secondes.",
    intent: "Génération Audio & Musique",
    icon: Music,
    target: "Meta MusicGen (Replicate API)",
    reason: "Requête de création sonore ou composition musicale par IA."
  }
];

export default function AIRouterConsole({ onSimulatePrompt }) {
  const [selectedPrompt, setSelectedPrompt] = useState(SAMPLE_PROMPTS[0]);

  return (
    <div className="glass-panel p-5 rounded-2xl border border-slate-800 mb-6">
      <div className="flex items-center justify-between pb-3 mb-4 border-b border-slate-800">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-purple-950 border border-purple-800/50 text-purple-400">
            <Cpu className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide">
              Module d'Aiguillage Intelligent d'IA (AI Smart Router)
            </h2>
            <p className="text-xs text-slate-400">
              Analyse automatique de l'intention et routage dynamique vers le meilleur modèle (OpenAI, Gemini, Groq, Claude, Mistral, Serper, Kling, MusicGen).
            </p>
          </div>
        </div>

        <span className="text-[10px] font-orbitron px-2.5 py-1 rounded-full bg-purple-900/40 text-purple-300 border border-purple-700/50">
          MOTEUR D'AIGUILLAGE v2
        </span>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Sélecteur de Prompts d'Exemple */}
        <div className="space-y-2">
          <label className="text-xs font-orbitron font-semibold text-slate-300 block">
            Sélectionner un Prompt pour Tester le Routage :
          </label>
          <div className="space-y-2">
            {SAMPLE_PROMPTS.map((p, idx) => {
              const IconComp = p.icon;
              const isSelected = selectedPrompt.text === p.text;
              return (
                <button
                  key={idx}
                  onClick={() => {
                    setSelectedPrompt(p);
                    if (onSimulatePrompt) onSimulatePrompt(p);
                  }}
                  className={`w-full text-left p-2.5 rounded-xl border transition-all text-xs flex items-center justify-between ${
                    isSelected
                      ? 'bg-purple-950/60 border-purple-500 text-purple-200'
                      : 'bg-slate-900/60 border-slate-800 hover:border-slate-700 text-slate-300'
                  }`}
                >
                  <div className="flex items-center space-x-2.5 pr-2">
                    <IconComp className="w-4 h-4 text-purple-400 shrink-0" />
                    <span className="truncate italic">"{p.text}"</span>
                  </div>
                  <ArrowRight className="w-3.5 h-3.5 text-purple-400 shrink-0" />
                </button>
              );
            })}
          </div>
        </div>

        {/* Panneau de Diagnostic en Temps Réel */}
        <div className="p-4 rounded-xl bg-slate-950 border border-purple-900/50 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between text-[11px] font-orbitron text-purple-400 mb-2">
              <span className="flex items-center space-x-1.5">
                <Compass className="w-4 h-4 animate-spin-slow" />
                <span>DIAGNOSTIC DU ROUTEUR D'IA</span>
              </span>
              <span className="text-emerald-400 font-mono">200 OK</span>
            </div>

            <div className="space-y-2.5 font-mono text-xs">
              <div className="p-2 rounded bg-slate-900 border border-slate-800">
                <span className="text-slate-500 text-[10px] block">PROMPT ENTRANT :</span>
                <p className="text-slate-200 mt-0.5">"{selectedPrompt.text}"</p>
              </div>

              <div className="p-2 rounded bg-slate-900 border border-slate-800">
                <span className="text-slate-500 text-[10px] block">INTENTION DÉTECTÉE :</span>
                <p className="text-purple-300 font-bold mt-0.5">{selectedPrompt.intent}</p>
              </div>

              <div className="p-2 rounded bg-purple-950/50 border border-purple-700/60">
                <span className="text-slate-400 text-[10px] block">MODÈLE IA SÉLECTIONNÉ :</span>
                <p className="text-cyan-300 font-bold font-orbitron text-sm mt-0.5">
                  {selectedPrompt.target}
                </p>
              </div>
            </div>
          </div>

          <p className="text-[11px] text-slate-400 mt-3 pt-2 border-t border-slate-900 italic">
            💡 Justification : {selectedPrompt.reason}
          </p>
        </div>
      </div>
    </div>
  );
}
