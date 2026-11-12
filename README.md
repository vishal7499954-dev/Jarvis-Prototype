# Jarvis Prototype

A minimal Android prototype with one job: listen for **“Jarvis”** in the background and speak **“Hello sir.”**

## Offline recognition
Speech recognition uses a Vosk English model bundled into the APK during the build. The app does not use Google Speech Recognition or a cloud speech API. Gradle downloads the model at build time; the installed app recognizes speech locally without internet access.

The spoken greeting uses Android's installed text-to-speech engine. For fully offline speech output, the phone must have an offline English voice installed.

## Build
Open this repository in Android Studio with JDK 17 and Android SDK 35, then build the debug APK. Gradle downloads the Vosk model automatically. GitHub Actions also builds the APK on pushes to main or when manually started.

## Test
1. Install the APK.
2. Open Jarvis Prototype and grant microphone permission.
3. Tap **Start background listening**.
4. Switch to another app while the ongoing notification remains visible.
5. Say **“Jarvis”**. JARVIS should answer **“Hello sir.”**
6. Return to the app and tap **Stop listening** to stop the microphone.

## Scope
This prototype does not send messages, click controls, unlock the phone, or perform other commands. Listening starts only after the user taps the button and is shown by a persistent notification. Android or Samsung battery settings may affect long-running background operation.
