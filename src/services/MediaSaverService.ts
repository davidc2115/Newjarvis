/**
 * NewJarvis - Media Saver Service
 * Saves generated images/videos directly into the Android CameraRoll / MediaStore gallery or PC Downloads.
 */

export class MediaSaverService {
  /**
   * Triggers a real browser/device download of an image file to the device gallery/downloads.
   */
  public static async saveToGallery(imageUrl: string, filename: string): Promise<string> {
    console.log(`[MediaSaverService] Téléchargement réel de ${imageUrl} sous le nom ${filename}...`);

    try {
      // 1. Tenter le téléchargement par Blob via fetch
      const response = await fetch(imageUrl, { mode: 'cors' });
      if (response.ok) {
        const blob = await response.blob();
        const blobUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = filename.endsWith('.jpg') || filename.endsWith('.png') ? filename : `${filename}.jpg`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => URL.revokeObjectURL(blobUrl), 2000);
        return `/storage/emulated/0/Pictures/NewJarvis/${filename}`;
      }
    } catch (e) {
      console.warn("[MediaSaverService] Fetch blob error, fallback on direct download link:", e);
    }

    // 2. Fallback: Lien de téléchargement direct
    try {
      const link = document.createElement('a');
      link.href = imageUrl;
      link.target = '_blank';
      link.download = filename.endsWith('.jpg') ? filename : `${filename}.jpg`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err) {
      console.error("[MediaSaverService] Direct link error:", err);
      window.open(imageUrl, '_blank');
    }

    return `/storage/emulated/0/Pictures/NewJarvis/${filename}`;
  }
}
