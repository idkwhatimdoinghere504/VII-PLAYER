# MixPlayer

MixPlayer is a clean-room music player project inspired by features from BlackPlayer EX and MusicBee. It does not reuse their app code or branding. The supplied portrait is used as the app icon.

## Android app

The native Android project is in `android/`. It currently includes:

- A dark library interface with Tracks, Albums, Artists, and Favorites views plus search.
- Local music discovery through Android MediaStore, with an explicit audio permission prompt.
- Playback, seek, previous/next, shuffle, and repeat controls.
- Media3 playback in a service so audio can continue in the background and use Android media controls.
- Favorites saved on the device.

The original BlackPlayer APK was inventoried with REA. It contains Android Dalvik/ART components and native FLAC/Opus decoder libraries. REA's Java/resource extraction helpers could not be run in this environment, so the Android implementation is based on publicly described player features and Android APIs, not a reconstruction of the original internals.

## Build requirements

Install Android Studio with Android SDK Platform 36 and JDK 17 or newer. Open the `android/` directory in Android Studio and allow Gradle to sync. The project uses Android Gradle Plugin 9.4.0 and AndroidX Media3 1.11.1. This workspace did not have Java, Gradle, or the Android SDK on PATH when the project was created, so an APK has not been built here.

## Existing web prototype

The original PWA files remain in the project root (`index.html`, `app.js`, `styles.css`). The Android app lives separately under `android/`.
