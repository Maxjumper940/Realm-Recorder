# Realm Recorder

Realm Recorder is a separate Android app for recording gameplay together with the microphone and eligible audio playing from other apps.

## Requirements and limits

- Android 10 (API 29) or newer.
- Android displays its screen-capture confirmation for each recording.
- Audio from another app is captured only when Android allows it. Apps can disable playback capture, and Android does not expose every audio type (including voice-communication audio) to third-party recorders. Realm VC call audio must be tested on the actual phone; this recorder cannot bypass Android or another app's capture policy.
- Tell everyone in a call before recording and get their consent.

## Download

[Download the Android APK](Realm-Recorder.apk) (debug-signed build).

## Use

1. Install and open Realm Recorder.
2. Choose **All compatible app audio** to include Minecraft and Realm VC audio, or choose one app to filter playback audio.
3. On Android 14+, choose **Choose one app** under **Screen** if you want Android to capture a single app; otherwise the whole screen is recorded.
4. Tap **Start recording** and approve the microphone and Android screen-capture prompts.
5. Switch to Minecraft Bedrock and join the voice call.
6. Stop from the app or its ongoing notification. The MP4 is saved under Movies/Realm Recorder.

The **App audio** menu filters audio only. It does not choose the video; the **Screen** setting controls full-screen versus Android's single-app picker.

The recording is stored locally; this app does not upload it.

## Build

Open this folder in Android Studio, or run `gradlew.bat assembleDebug`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
