import React from 'react';
import { Palette, Check, Sparkles } from 'lucide-react';

const COLOR_OPTIONS = [
  { id: 'galaxy', name: 'Galaxie Néon 🌌', hex: 'linear-gradient(135deg, #00f0ff, #a855f7, #ff0055)', bg: 'bg-gradient-to-r from-cyan-400 via-purple-500 to-rose-500', border: 'border-purple-400' },
  { id: 'supernova', name: 'Supernova Cosmique 💥', hex: 'linear-gradient(135deg, #fbbf24, #f43f5e, #3b82f6)', bg: 'bg-gradient-to-r from-amber-400 via-rose-500 to-blue-500', border: 'border-amber-400' },
  { id: 'aurora', name: 'Aurore Boréale ❇️', hex: 'linear-gradient(135deg, #10b981, #06b6d4, #ec4899)', bg: 'bg-gradient-to-r from-emerald-400 via-cyan-500 to-pink-500', border: 'border-emerald-400' },
  { id: 'blue', name: 'Bleu Arc ⚡', hex: '#00f0ff', bg: 'bg-cyan-500', border: 'border-cyan-400' },
  { id: 'red', name: 'Rouge Alerte 🚨', hex: '#ff0055', bg: 'bg-rose-600', border: 'border-rose-500' },
  { id: 'purple', name: 'Violet Quantique 🔮', hex: '#a855f7', bg: 'bg-purple-600', border: 'border-purple-400' },
  { id: 'green', name: 'Vert Bio-Matrix 🧬', hex: '#10b981', bg: 'bg-emerald-500', border: 'border-emerald-400' },
  { id: 'orange', name: 'Orange Plasma 🟧', hex: '#f97316', bg: 'bg-orange-500', border: 'border-orange-400' },
  { id: 'grey', name: 'Gris Titane ⚙️', hex: '#94a3b8', bg: 'bg-slate-400', border: 'border-slate-300' },
];

export default function ThemeSelector({ activeColor, onChangeColor }) {
  return (
    <div className="glass-panel p-4 rounded-2xl mb-6 border border-slate-800">
      <div className="flex items-center space-x-2 mb-3">
        <Sparkles className="w-4 h-4 text-cyan-400" />
        <h3 className="text-xs uppercase tracking-wider font-orbitron font-semibold text-slate-300">
          Palette de Couleurs & Thèmes Galactiques (9 Styles)
        </h3>
      </div>

      <div className="grid grid-cols-3 sm:grid-cols-9 gap-2">
        {COLOR_OPTIONS.map((c) => {
          const isSelected = activeColor === c.id;
          return (
            <button
              key={c.id}
              onClick={() => onChangeColor(c.id)}
              className={`flex flex-col items-center p-2 rounded-xl border transition-all duration-200 ${
                isSelected
                  ? `bg-slate-800/90 ${c.border} ring-2 ring-offset-2 ring-offset-slate-950 ring-${c.id}`
                  : 'bg-slate-900/50 border-slate-800 hover:border-slate-700'
              }`}
            >
              <div className="relative flex items-center justify-center">
                <span 
                  className={`w-7 h-7 rounded-full shadow-lg ${c.bg} flex items-center justify-center`}
                  style={{ background: c.hex.includes('gradient') ? c.hex : undefined }}
                >
                  {isSelected && <Check className="w-4 h-4 text-black font-bold" />}
                </span>
              </div>
              <span className="text-[9px] sm:text-[10px] font-orbitron mt-1.5 text-slate-300 text-center font-medium truncate w-full">
                {c.name}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
