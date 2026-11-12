# LFM Edge Assistant for Android

An on-device AI assistant for Qualcomm Snapdragon 8 Elite with an ivory, ink, and lilac aesthetic. The application ID (`ai.snapdragon.edge`) integrates directly with on-device inference daemons running locally over localhost.

## Architecture & Experience

- **Engine Control & Status Card:** Direct on-device engine management (`⚡ Start Engine` / `⏸ Rest Engine`). Sends instantaneous commands to the localhost supervisor daemon (`127.0.0.1:8082`) to launch or terminate `llama-server` (Adreno 840 GPU) and `whisper-server` (Oryon CPU NEON), freeing ~2.5 GB RAM and eliminating idle battery drain.
- **Home Interface:** Time-aware greeting, vector flower artwork (`MuseArtView`), and quick conversation starter cards.
- **Native Chat:** Local conversational reasoning with LFM3-VL-2B (`127.0.0.1:8080/v1/chat/completions`), live thinking state, selectable text responses, and persistent chat history.
- **Interactive Home Screen Widget:**
  - One-tap Engine Toggle: `⚡ Start Engine` when resting, `● Active ⏸` when running.
  - Quick Launcher: `Ask LFM` opens the chat composer immediately.
  - Updates automatically whenever engine state transitions.
- **100% Private:** No cloud APIs, no analytics, no external tracking. All inference occurs on-device.

## Building & Installation

Requires Java 11 and Android SDK 33 (`platforms;android-33`, `build-tools;33.0.2`).

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Screenshots

Live device captures from Realme GT 8 Pro (Qualcomm Snapdragon 8 Elite) are archived in `design/`:
- `lfm-home.png`: Home dashboard showing active engine status and controls.
- `lfm-rest.png`: Engine in resting state (power-saving mode) with instant ⚡ Start toggle.
- `lfm-start.png`: Live transition waking the engine into GPU memory.
