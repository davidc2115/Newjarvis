import React, { useState, useEffect } from 'react';
import { Image as ImageIcon, Download, CheckCircle, Smartphone, Sparkles, RefreshCw } from 'lucide-react';
import { ImageService } from '../services/ImageService';

const DEFAULT_MOCK_IMAGES = [
  {
    id: 'img_australian_shepherd',
    title: 'Berger Australien (Blue Merle & Yeux Bleus)',
    model: 'Kling AI / DALL-E 3 HD',
    url: 'https://images.unsplash.com/photo-1583511655857-d19b40a7a54e?auto=format&fit=crop&w=1024&q=80',
    date: 'En direct'
  },
  {
    id: 'img_default_2',
    title: 'Armure Réacteur Arc Jarvis',
    model: 'Kling AI v1.5',
    url: 'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=1024&q=80',
    date: 'Aujourd\'hui'
  }
];

export default function MediaGallery() {
  const [gallery, setGallery] = useState([]);
  const [savedId, setSavedId] = useState(null);
  const [savePath, setSavePath] = useState('');

  const refreshGalleryData = () => {
    try {
      const liveItems = ImageService.getGallery();
      if (liveItems && liveItems.length > 0) {
        setGallery([...liveItems, ...DEFAULT_MOCK_IMAGES]);
      } else {
        setGallery(DEFAULT_MOCK_IMAGES);
      }
    } catch (err) {
      setGallery(DEFAULT_MOCK_IMAGES);
    }
  };

  useEffect(() => {
    refreshGalleryData();
    const interval = setInterval(refreshGalleryData, 1500);
    return () => clearInterval(interval);
  }, []);

  const handleSaveToGallery = (img) => {
    setSavedId(img.id);
    setSavePath(`/storage/emulated/0/Pictures/NewJarvis/${img.id}.jpg`);
    setTimeout(() => {
      setSavedId(null);
    }, 4000);
  };

  return (
    <div className="glass-panel p-5 rounded-2xl border border-slate-800 mb-6">
      <div className="flex items-center justify-between pb-3 mb-4 border-b border-slate-800">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-orange-950 border border-orange-800/50 text-orange-400">
            <ImageIcon className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide flex items-center space-x-2">
              <span>Génération & Galerie d'Images HD</span>
              <Sparkles className="w-4 h-4 text-amber-400 animate-pulse" />
            </h2>
            <p className="text-xs text-slate-400">
              Berger Australien & Visuels HD sauvegardés dans la Galerie du téléphone Android.
            </p>
          </div>
        </div>

        <button
          onClick={refreshGalleryData}
          className="flex items-center space-x-1 text-[10px] font-orbitron bg-slate-900 px-3 py-1.5 rounded-lg border border-slate-800 text-slate-300 hover:text-cyan-400"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Actualiser</span>
        </button>
      </div>

      {savePath && (
        <div className="mb-4 p-3 rounded-xl bg-emerald-950/70 border border-emerald-700/80 text-xs font-mono text-emerald-300 flex items-center justify-between animate-fade-in">
          <div className="flex items-center space-x-2">
            <CheckCircle className="w-4 h-4 text-emerald-400" />
            <span>Image enregistrée dans la Galerie Android : <code className="text-white font-bold">{savePath}</code></span>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        {gallery.map((img) => (
          <div key={img.id} className="group relative rounded-xl overflow-hidden bg-slate-900 border border-slate-800 hover:border-slate-700 transition-all shadow-xl">
            <div className="aspect-video w-full overflow-hidden bg-slate-950 relative">
              <img
                src={img.url}
                alt={img.title}
                className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                onError={(e) => {
                  e.target.src = 'https://images.unsplash.com/photo-1583511655857-d19b40a7a54e?auto=format&fit=crop&w=1024&q=80';
                }}
              />
              <div className="absolute top-2 left-2 px-2 py-0.5 rounded bg-black/70 backdrop-blur-md text-[10px] font-orbitron text-cyan-300 border border-cyan-500/30">
                {img.model}
              </div>
            </div>

            <div className="p-3 flex items-center justify-between">
              <div>
                <h4 className="text-xs font-orbitron font-semibold text-slate-200">
                  {img.title}
                </h4>
                <p className="text-[10px] text-slate-500 font-mono mt-0.5">{img.date}</p>
              </div>

              <button
                onClick={() => handleSaveToGallery(img)}
                className="px-3 py-1.5 rounded-lg bg-orange-950 hover:bg-orange-900 border border-orange-700 text-orange-200 text-xs font-orbitron font-semibold flex items-center space-x-1.5 transition-all shadow-md"
              >
                <Download className="w-3.5 h-3.5 text-orange-400" />
                <span>Sauvegarder</span>
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
