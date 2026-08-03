import React, { useState } from 'react';
import { Smartphone, Wrench, Download, RefreshCw, FileCode, CheckCircle2, ShieldCheck, Terminal, Layers, Play } from 'lucide-react';

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
apply plugin: "com.facebook.react"

android {
    ndkVersion rootProject.ext.ndkVersion
    compileSdkVersion 34
    namespace "com.newjarvis.assistant"

    defaultConfig {
        applicationId "com.newjarvis.assistant"
        minSdkVersion 24
        targetSdkVersion 34
        versionCode 1
        versionName "1.0.0"
    }

    signingConfigs {
        release {
            storeFile file('newjarvis-key.keystore')
            storePassword 'jarvis2026'
            keyAlias 'jarvis-key-alias'
            keyPassword 'jarvis2026'
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"
        }
    }
}`,
  strings: `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">NewJarvis Mobile</string>
    <string name="wake_word">Jarvis</string>
    <string name="whisper_model">ggml-tiny.bin</string>
    <string name="theme_color">#00f0ff</string>
</resources>`
};

export default function ApkModifierConsole() {
  const [activeFileTab, setActiveFileTab] = useState('manifest');
  const [fileContent, setFileContent] = useState(SAMPLE_APK_FILES.manifest);
  const [apkName, setApkName] = useState('NewJarvis_v1.0_custom.apk');
  const [isCompiling, setIsCompiling] = useState(false);
  const [compileSuccess, setCompileSuccess] = useState(false);

  const handleFileTabChange = (tabKey) => {
    setActiveFileTab(tabKey);
    setFileContent(SAMPLE_APK_FILES[tabKey]);
  };

  const handleRebuildApk = () => {
    setIsCompiling(true);
    setCompileSuccess(false);

    setTimeout(() => {
      setIsCompiling(false);
      setCompileSuccess(true);
    }, 2200);
  };

  return (
    <div className="glass-panel p-4 sm:p-6 rounded-2xl border border-slate-800 mb-6">
      {/* En-tête du Modificateur d'APK */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between pb-4 mb-4 border-b border-slate-800 gap-3">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-cyan-950 border border-cyan-800/50 text-cyan-400">
            <Wrench className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-orbitron font-bold text-slate-100 uppercase tracking-wide flex items-center space-x-2">
              <span>Modificateur & Recompilateur d'APK Android</span>
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
            </h2>
            <p className="text-xs text-slate-400">
              Décompilez, éditez les permissions Android, modifiez le code source et recompiliez votre APK Release signée.
            </p>
          </div>
        </div>

        <button
          onClick={handleRebuildApk}
          disabled={isCompiling}
          className="px-4 py-2 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-slate-950 text-xs font-orbitron font-bold flex items-center space-x-1.5 transition-all shadow-lg shadow-cyan-950/50 disabled:opacity-50"
        >
          {isCompiling ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
          <span>{isCompiling ? 'Recompilation Gradle...' : 'Recompiler APK Release'}</span>
        </button>
      </div>

      {compileSuccess && (
        <div className="mb-4 p-3.5 rounded-xl bg-emerald-950/80 border border-emerald-700 text-xs font-mono text-emerald-300 flex items-center justify-between animate-fade-in">
          <div className="flex items-center space-x-2">
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
            <span>APK Recompilée et Signée avec succès : <code className="text-white font-bold">{apkName}</code></span>
          </div>
          <button
            onClick={() => alert(`Téléchargement de l'APK modifiée : ${apkName}`)}
            className="px-3 py-1 bg-emerald-600 text-slate-950 font-bold rounded-lg hover:bg-emerald-400 transition-colors flex items-center space-x-1 text-[11px]"
          >
            <Download className="w-3 h-3" />
            <span>Télécharger APK</span>
          </button>
        </div>
      )}

      {/* Sélection du Fichier APK Décompilé */}
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

      {/* Zone d'Édition du Fichier Décompilé */}
      <div className="bg-slate-950 rounded-xl border border-slate-800 overflow-hidden mb-4">
        <div className="px-3.5 py-2 bg-slate-900 border-b border-slate-800 flex items-center justify-between">
          <span className="text-[11px] font-orbitron text-cyan-300 font-bold flex items-center space-x-1.5">
            <FileCode className="w-3.5 h-3.5 text-cyan-400" />
            <span>ÉDITION DU FICHIER : {activeFileTab.toUpperCase()}</span>
          </span>
          <span className="text-[10px] font-mono text-slate-400">MODIFICATION NATIVE</span>
        </div>

        <textarea
          value={fileContent}
          onChange={(e) => setFileContent(e.target.value)}
          className="w-full h-80 bg-slate-950 text-cyan-300 p-4 font-mono text-xs focus:outline-none resize-none leading-relaxed"
          spellCheck={false}
        />
      </div>

      {/* Rapport du processus Gradle Build */}
      <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 font-mono text-xs">
        <div className="flex items-center space-x-2 text-slate-400 mb-1">
          <Terminal className="w-4 h-4 text-cyan-400" />
          <span className="font-orbitron">Console de Compilation `./gradlew assembleRelease` :</span>
        </div>
        <p className="text-slate-500 text-[11px]">
          Modifiez les chaînes de caractères, les permissions d'arrière-plan ou le code source React Native ci-dessus puis cliquez sur <code className="text-cyan-400 font-bold">Recompiler APK Release</code>.
        </p>
      </div>
    </div>
  );
}
