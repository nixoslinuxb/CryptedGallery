# CryptedGallery

An offline Android gallery and document viewer built with Kotlin and Jetpack Compose.
Media is browsed from device storage through a clean, privacy-oriented UI with an
app-level password lock.

## Features

- **Photos, videos, audio and documents** in one app
- **App lock** with password: the unlocked session survives a trip to the background
  and locks itself again after 5 minutes without interaction
- **Built-in viewers**
  - Images with pinch-to-zoom
  - Video player (ExoPlayer) with gesture volume/brightness control, share and delete
  - Audio player
  - PDF, EPUB, Office (DOC/DOCX/XLS/XLSX/PPT/PPTX) and text documents
  - SVG rendering
  - **DWG / DXF CAD drawings** with a built-in CAD viewer and generated thumbnails
  - PDF text extraction via iText 7
- **Sharing** single and multiple items through the system share sheet
- **Swipe actions** for quick delete/share in list screens

## Tech stack

| Layer | Library |
| --- | --- |
| Language | Kotlin 2.2 |
| UI | Jetpack Compose (Material 3) |
| Navigation | Navigation Compose |
| Media playback | AndroidX Media3 (ExoPlayer) |
| Image loading | Coil 3 |
| Office documents | Apache POI |
| PDF | iText 7 (`com.itextpdf:kernel`) |
| CAD | dwg-lib (`com.1spatial`) + custom DXF parser |
| Storage | EncryptedSharedPreferences (AndroidX Security Crypto) |

## Requirements

- Android 16 (API 36) or newer
- JDK 21+ and the Android SDK with platform 37

## Build

```bash
# Point Gradle at your SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## License notice

This project depends on **iText 7**, which is licensed under the **AGPL**. If you
redistribute modified versions of this app, the AGPL obligations apply. A commercial
use may require a separate iText license.
