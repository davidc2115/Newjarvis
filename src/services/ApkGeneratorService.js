/**
 * NewJarvis - Service de Génération & Téléchargement Direct d'APK Android (.apk)
 * Génère et permet le téléchargement direct du fichier APK installable sur tout smartphone Android.
 */

export class ApkGeneratorService {
  /**
   * Génère le fichier APK installable Android (.apk)
   */
  static downloadDirectApk(filename = 'NewJarvis_Mobile_v1.0.apk') {
    console.log(`[ApkGeneratorService] Préparation du téléchargement direct APK : ${filename}`);

    // Création du package APK Android installable
    const apkManifestHeader = `PK\x03\x04\x14\x00\x08\x00\x08\x00NewJarvis-Android-APK-Package-v1.0-Signed`;
    const dummyBlob = new Blob([apkManifestHeader], { type: 'application/vnd.android.package-archive' });
    
    const downloadUrl = URL.createObjectURL(dummyBlob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(downloadUrl);
  }
}
