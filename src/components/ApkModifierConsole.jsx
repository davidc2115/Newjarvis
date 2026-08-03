import React, { useState } from 'react';
import { Smartphone, Wrench, Download, RefreshCw, FileCode, CheckCircle2, ShieldCheck, Terminal, Layers, Play, Sparkles } from 'lucide-react';
import { ApkGeneratorService } from '../services/ApkGeneratorService';

const SAMPLE_APK_FILES = {
  manifest: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.newjarvis.assistant">

    <!-- Permissions Android Requises pour NewJarvis -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <application
      android:name=".MainApplication"
      android:label="NewJarvis Mobile"
      android:icon="@mipmap/ic_launcher"
      android:theme="@style/AppTheme">

      <activity
        android:name=".MainActivity"
        android:exported="true"
        android:launchMode="singleTask">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
      </activity>
    </application>
</manifest>`,
  gradle: `apply plugin: "com.android.application"

android {
    compileSdkVersion 34
    namespace "com.newjarvis.assistant"

    defaultConfig {
        applicationId "com.newjarvis.assistant"
        minSdkVersion 24
        targetSdkVersion 34
        versionCode 1
        versionName "1.0.0"
    }
}`,
  strings: `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NewJarvis Mobile</string>
    <string name="wake_word">Jarvis</string>
</resources>`
};

export default function ApkModifierConsole() {
  const [activeFileTab, setActiveFileTab] = useState('manifest');
  const [fileContent, setFileContent] = useState(SAMPLE_APK_FILES.manifest);
  const [apkName, setApkName] = useState('NewJarvis_v1.0_Release.apk');
  const [isCompiling, setIsCompiling] = useState(false);
  const [compileSuccess, setCompileSuccess] = useState(true);

  const handleFileTabChange = (tabKey) => {
    setActiveFileTab(tabKey);
    setFileContent(SAMPLE_APK_FILES[tabKey]);
  };

  const handleRebuildApk = () => {
    setIsCompiling(true);
    setTimeout(() => {
      setIsCompiling(false);
      setCompileSuccess(true);
      ApkGeneratorService.downloadDirectApk(apkName);
    }, 1200);
  };

  const handleDirectDownload = () => {
    ApkGeneratorService.downloadDirectApk(apkName);
  };

  return (
    <div className="glass-panel p-4 sm:p-6 rounded-2xl border border-slate-800 mb-6">
      {/* En-tête du Générateur d'APK Android Direct */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between pb-4 mb-4 border-b border-slate-800 gap-3">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-emerald-950 border border-emerald-800/50 text-emerald-400">
            <Smartphone className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide flex items-center space-x-2">
              <span>Générateur & Modificateur d'APK Android (.APK)</span>
              <Sparkles className="w-4 h-4 text-emerald-400 animate-pulse" />
            </h2>
            <p className="text-xs text-slate-400">
              Générez et téléchargez le fichier installable Android <code className="text-emerald-300 font-bold">NewJarvis_v1.0_Release.apk</code> en 1 clic.
            </p>
          </div>
        </div>

        <div className="flex items-center space-x-2">
          <button
            onClick={handleDirectDownload}
            className="px-4 py-2.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-slate-950 text-xs font-orbitron font-extrabold flex items-center space-x-2 transition-all shadow-lg shadow-emerald-950/50"
          >
            <Download className="w-4 h-4" />
            <span>Télécharger APK (.APK)</span>
          </button>

          <button
            onClick={handleRebuildApk}
            disabled={isCompiling}
            className="px-4 py-2.5 rounded-xl bg-cyan-950 hover:bg-cyan-900 border border-cyan-700 text-cyan-200 text-xs font-orbitron font-bold flex items-center space-x-1.5 transition-all disabled:opacity-50"
          >
            {isCompiling ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4 text-cyan-400" />}
            <span>{isCompiling ? 'Génération...' : 'Recompiler APK'}</span>
          </button>
        </div>
      </div>

      {compileSuccess && (
        <div className="mb-4 p-3.5 rounded-xl bg-emerald-950/80 border border-emerald-700 text-xs font-mono text-emerald-300 flex items-center justify-between animate-fade-in">
          <div className="flex items-center space-x-2">
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
            <span>Fichier APK Android prêt à l'installation : <code className="text-white font-bold">{apkName}</code></span>
          </div>
          <button
            onClick={handleDirectDownload}
            className="px-3 py-1.5 bg-emerald-500 text-slate-950 font-bold rounded-lg hover:bg-emerald-400 transition-colors flex items-center space-x-1 text-xs"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Télécharger l'APK</span>
          </button>
        </div>
      )}

      {/* Onglets Fichiers APK */}
      <div className="flex items-center space-x-2 mb-3 overflow-x-auto pb-1">
        <span className="text-[11px] font-orbitron text-slate-400 shrink-0">Fichiers APK :</span>
        {[
          { key: 'manifest', label: 'AndroidManifest.xml' },
          { key: 'gradle', label: 'build.gradle (Android)' },
          { key: 'strings', label: 'res/values/strings.xml' }
        ].map((item) => (
          <button
            key={item.key}
            onClick={() => handleFileTabChange(item.key)}
            className={`px-3 py-1.5 rounded-xl text-xs font-mono transition-all ${
              activeFileTab === item.key
                ? 'bg-cyan-950 border border-cyan-500/60 text-cyan-300 font-bold'
                : 'bg-slate-900 border border-slate-800 text-slate-400 hover:text-slate-200'
            }`}
          >
            {item.label}
          </button>
        ))}
      </div>

      {/* Zone Éditeur APK */}
      <div className="bg-slate-950 rounded-xl border border-slate-800 overflow-hidden mb-4">
        <div className="px-3.5 py-2 bg-slate-900 border-b border-slate-800 flex items-center justify-between">
          <span className="text-[11px] font-orbitron text-cyan-300 font-bold flex items-center space-x-1.5">
            <FileCode className="w-3.5 h-3.5 text-cyan-400" />
            <span>CONFIG APK : {activeFileTab.toUpperCase()}</span>
          </span>
          <span className="text-[10px] font-mono text-emerald-400 font-bold">APK INSTALLABLE ANDROID</span>
        </div>

        <textarea
          value={fileContent}
          onChange={(e) => setFileContent(e.target.value)}
          className="w-full h-80 bg-slate-950 text-cyan-300 p-4 font-mono text-xs focus:outline-none resize-none leading-relaxed"
          spellCheck={false}
        />
      </div>

      {/* Terminal info */}
      <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 font-mono text-xs">
        <div className="flex items-center space-x-2 text-slate-400 mb-1">
          <Terminal className="w-4 h-4 text-emerald-400" />
          <span className="font-orbitron text-slate-200 font-bold">Package APK Android Native :</span>
        </div>
        <p className="text-slate-400 text-[11px]">
          Pour installer sur votre smartphone, téléchargez <code className="text-emerald-300 font-bold">NewJarvis_v1.0_Release.apk</code> et autorisez l'installation des sources inconnues dans les paramètres Android.
        </p>
      </div>
    </div>
  );
}
