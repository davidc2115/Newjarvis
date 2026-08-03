import React, { useState, useEffect } from 'react';
import { Image as ImageIcon, Download, CheckCircle, Sparkles, RefreshCw, Trash2, ExternalLink, PlusCircle, Send } from 'lucide-react';
import { ImageService } from '../services/ImageService';
import { MediaSaverService } from '../services/MediaSaverService';

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
  const [savePath, setSavePath] = useState('');
  const [customPrompt, setCustomPrompt] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);

  const refreshGalleryData = () => {
    try {
      const liveItems = ImageService.getGallery();
      if (liveItems && liveItems.length > 0) {
        // Filtrer les doublons éventuels
        const existingIds = new Set(liveItems.map(i => i.id));
        const filteredMocks = DEFAULT_MOCK_IMAGES.filter(m => !existingIds.has(m.id));
        setGallery([...liveItems, ...filteredMocks]);
      } else {
        setGallery(DEFAULT_MOCK_IMAGES);
      }
    } catch (err) {
      setGallery(DEFAULT_MOCK_IMAGES);
    }
  };

  useEffect(() => {
    refreshGalleryData();
    const interval = setInterval(refreshGalleryData, 2000);
    return () => clearInterval(interval);
  }, []);

  // Déclencher la sauvegarde réelle du fichier image
  const handleSaveToGallery = async (img) => {
    const filename = `${img.title.toLowerCase().replace(/[^a-z0-9]/g, '_')}_${img.id}.jpg`;
    setSavePath(`Téléchargement en cours...`);
    
    const target = await MediaSaverService.saveToGallery(img.url, filename);
    setSavePath(target);

    setTimeout(() => {
      setSavePath('');
    }, 6000);
  };

  // Supprimer une image de la galerie
  const handleDelete = (id) => {
    ImageService.deleteImage(id);
    refreshGalleryData();
  };

  // Générer directement une image depuis la galerie
  const handleGenerateDirect = async (e) => {
    if (e) e.preventDefault();
    if (!customPrompt.trim() || isGenerating) return;

    setIsGenerating(true);
    const p = customPrompt.trim();
    setCustomPrompt('');
    
    try {
      await ImageService.generateLiveImage(p);
      refreshGalleryData();
    } catch (err) {
      console.error("Erreur génération image:", err);
    } finally {
      setIsGenerating(false);
    }
  };

  return (
    <div className="glass-panel p-4 sm:p-5 rounded-2xl border border-slate-800 mb-6">
      {/* En-tête de la Galerie */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-3 mb-4 border-b border-slate-800 gap-2">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-orange-950 border border-orange-800/50 text-orange-400 shrink-0">
            <ImageIcon className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-xs sm:text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide flex items-center space-x-2">
              <span>Galerie d'Images HD Android</span>
              <Sparkles className="w-4 h-4 text-amber-400 animate-pulse" />
            </h2>
            <p className="text-[11px] text-slate-400">
              Berger Australien & Visuels HD enregistrés dans la Galerie du téléphone.
            </p>
          </div>
        </div>

        <button
          onClick={refreshGalleryData}
          className="flex items-center justify-center space-x-1 text-[11px] font-orbitron bg-slate-900 px-3 py-1.5 rounded-xl border border-slate-800 text-slate-300 hover:text-cyan-400"
        >
          <RefreshCw className="w-3.5 h-3.5" />
          <span>Actualiser</span>
        </button>
      </div>

      {/* Barre de Génération Rapide d'Image */}
      <form onSubmit={handleGenerateDirect} className="mb-4">
        <div className="flex space-x-2">
          <input
            type="text"
            value={customPrompt}
            onChange={(e) => setCustomPrompt(e.target.value)}
            placeholder="Ex: Berger australien aux yeux bleus, voiture de sport, paysage..."
            className="flex-1 bg-slate-950 border border-slate-800 rounded-xl px-3.5 py-2 text-xs sm:text-sm text-slate-200 placeholder-slate-600 focus:outline-none focus:border-amber-500 font-sans"
          />
          <button
            type="submit"
            disabled={!customPrompt.trim() || isGenerating}
            className="px-4 py-2 bg-gradient-to-r from-amber-500 to-orange-600 hover:from-amber-400 hover:to-orange-500 text-slate-950 font-orbitron font-bold text-xs rounded-xl flex items-center space-x-1.5 disabled:opacity-40 shrink-0"
          >
            {isGenerating ? (
              <RefreshCw className="w-4 h-4 animate-spin" />
            ) : (
              <PlusCircle className="w-4 h-4" />
            )}
            <span>{isGenerating ? 'Génération...' : 'Générer Image'}</span>
          </button>
        </div>
      </form>

      {/* Message de Confirmation de Sauvegarde dans la Galerie */}
      {savePath && (
        <div className="mb-4 p-3 rounded-xl bg-emerald-950/90 border border-emerald-600 text-xs font-mono text-emerald-200 flex items-center justify-between animate-fade-in shadow-lg">
          <div className="flex items-center space-x-2">
            <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0" />
            <span>Fichier téléchargé & enregistré dans la Galerie : <code className="text-white font-bold">{savePath}</code></span>
          </div>
        </div>
      )}

      {/* Grille d'Images HD */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        {gallery.map((img) => (
          <div key={img.id} className="group relative rounded-xl overflow-hidden bg-slate-900 border border-slate-800 hover:border-amber-500/50 transition-all shadow-xl flex flex-col justify-between">
            <div>
              <div className="aspect-video w-full overflow-hidden bg-slate-950 relative">
                <img
                  src={img.url}
                  alt={img.title}
                  className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                  onError={(e) => {
                    e.target.src = 'https://images.unsplash.com/photo-1583511655857-d19b40a7a54e?auto=format&fit=crop&w=1024&q=80';
                  }}
                />
                <div className="absolute top-2 left-2 px-2 py-0.5 rounded bg-black/75 backdrop-blur-md text-[10px] font-orbitron text-amber-300 border border-amber-500/30">
                  {img.model}
                </div>

                <a
                  href={img.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="absolute top-2 right-2 p-1.5 rounded-lg bg-black/75 text-slate-300 hover:text-white border border-slate-700"
                  title="Ouvrir l'image plein écran"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                </a>
              </div>

              <div className="p-3">
                <h4 className="text-xs font-orbitron font-semibold text-slate-100">
                  {img.title}
                </h4>
                <p className="text-[10px] text-slate-400 font-mono mt-0.5">{img.date}</p>
              </div>
            </div>

            {/* Actions de Téléchargement & Suppression */}
            <div className="p-3 pt-0 flex items-center justify-between gap-2 border-t border-slate-800/60 mt-2">
              <button
                onClick={() => handleDelete(img.id)}
                className="p-2 rounded-lg bg-slate-950 hover:bg-rose-950 border border-slate-800 hover:border-rose-700 text-slate-400 hover:text-rose-300 transition-all"
                title="Supprimer la photo"
              >
                <Trash2 className="w-3.5 h-3.5" />
              </button>

              <button
                onClick={() => handleSaveToGallery(img)}
                className="flex-1 py-1.5 px-3 rounded-lg bg-gradient-to-r from-orange-600 to-amber-600 hover:from-orange-500 hover:to-amber-500 text-slate-950 text-xs font-orbitron font-bold flex items-center justify-center space-x-1.5 transition-all shadow-md"
              >
                <Download className="w-3.5 h-3.5" />
                <span>Enregistrer en Galerie</span>
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
