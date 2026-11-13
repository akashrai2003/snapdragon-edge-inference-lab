# Cue Android project

Follow the repository root AGENTS.md and use this build root for Android commands.

- Build with JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.2.21, SDK platform 33 and Build Tools 35.0.0. Set JAVA_HOME to an installed JDK 17; do not write a host-specific path into tracked Gradle files.
- Prepare ignored model assets with `python3 scripts/prepare_speech.py`. Never commit model weights, private audio, device screenshots, APKs, or native library binaries.
- Required Android checks: `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`, then `python3 scripts/check_apk_alignment.py app/build/outputs/apk/debug/app-debug.apk` and Build Tools 35+ `zipalign -c -P 16 4` on the APK. Check both ZIP offsets and ELF LOAD/RELRO safety before deploying native dependency changes. Do not dismiss or suppress a compatibility warning as a substitute for fixing it.
- Quiet voice capture uses app-owned AudioRecord and the pinned offline decoder. Do not restore repeated Android SpeechRecognizer sessions for automatic wake/interruption: provider tones and repeated mic initialization were a device regression. Never mute global phone audio or hide Android microphone/privacy indicators to mask it.
- Model loading, recognition, blocking capture, locks, and native teardown stay off the UI thread. Deliver UI callbacks on the main Handler and invalidate callbacks after cancellation.
- Instrumentation stops the normal app process. Use `scripts/verify_voice_device.py` to restore previously enabled hands-free after successful probes. Run all automated phone tests before requesting a live user voice check; do not restart/install/test the app while that check is pending. Verify the service and microphone are actually active before requesting the check.
- Keep captured speech in memory only. Diagnostic files/logs contain bounded counters/timings, never recognized words, audio, prompts, or answers. The committed WAV fixtures are synthetic test audio only.
