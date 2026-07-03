# Wazak Android

Android-first prototype for Wazak: a floating Malangi companion that can use a chosen image and a sound recorded directly in the app.

## Run

Open this `android/` directory in Android Studio, then run the `app` configuration.

The app asks for:

- Microphone permission: record a Malangi sound.
- Photos permission: choose a Malangi image.
- Display over other apps: show the floating Malangi.

## MVP Flow

1. Pick a Malangi image.
2. Record a short sound or use the current recording.
3. Preview the sound.
4. Grant overlay permission.
5. Start Malangi.

The floating Malangi can be dragged around. Tapping it plays the recording and applies a soft press animation.
