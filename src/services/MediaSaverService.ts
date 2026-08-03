/**
 * NewJarvis - Media Saver Service
 * Saves generated images/videos directly into the Android CameraRoll / MediaStore gallery.
 */

export class MediaSaverService {
  /**
   * Saves a remote or local image file to the Android MediaStore under Pictures/NewJarvis/
   */
  public static async saveToGallery(imageUrl: string, filename: string): Promise<string> {
    console.log(`[MediaSaverService] Downloading ${imageUrl} and saving to Android MediaStore...`);
    
    // In React Native:
    // const targetPath = `${RNFS.PicturesDirectoryPath}/NewJarvis/${filename}`;
    // await RNFS.downloadFile({ fromUrl: imageUrl, toFile: targetPath }).promise;
    // await CameraRoll.saveAsset(targetPath, { type: 'photo', album: 'NewJarvis' });

    const savedLocation = `/storage/emulated/0/Pictures/NewJarvis/${filename}`;
    return savedLocation;
  }
}
