# Cue quiet-listener reliability review — 13 November 2026

## Reported issue and cause

The user heard repeated beeps, saw the microphone indicator restart, and could not open Cue by saying the wake phrase. They clarified that notification volume had previously been muted, so this may have been present before the latest update.

The previous implementation repeatedly called Android SpeechRecognizer for wake listening and foreground interruption. That is a short-session recognition API, not a continuous hotword engine; its provider can play recognition tones when starting/ending sessions. The coordinator also enforced a 20-second restart and rearmed after no-match/timeout. Cue’s existing notification channel was already low-importance with sound null and vibration disabled on the phone. This narrowed the likely audible source to provider/session behavior rather than ordinary notification updates. We did not change global volume or system privacy controls.

Reference: [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), which cautions against continuous use.

## Implemented

- Replaced the wake and VoiceTurn platform recognizers with one app-owned AudioRecord per active owner and offline Vosk 0.3.75. There is no timed wake-capture restart. A single microphone lock prevents overlapping recorders during handoff.
- Wake uses a constrained English grammar with confusable alternatives and an unknown path. The partial wake must remain stable before dispatch; final results can dispatch immediately. A grammar containing only the wake phrase was rejected because it forced “Hey you” into a wake match; adding explicit alternatives fixed the controlled cases tested.
- Foreground interruption keeps capture open through the answer and switches the same session into a question turn. An independent keyword decoder supports explicit “Hey Cue” while normal transcription retains the question’s leading words.
- Models are unpacked/loaded on a worker and cached in no-backup storage. Recording, native decoding, and teardown also run on that worker. Cancellation invalidates callbacks and releases the mic without joining a worker on the main thread. UI updates run on the main Handler. Listening UI waits for actual microphone readiness.
- Errors stop hands-free once, quietly, instead of retrying indefinitely. A silent notification channel has sound null, vibration disabled, zero defaults, and alert-once updates. Turn off cancels capture. The normal Android microphone indicator remains visible while hands-free is on.
- The existing real-overlay-to-floating-activity handoff was retained and verified from the background. Permission and keyguard checks remain in place.

## Evidence

- Debug build, lint, and 30 JVM tests pass, including wake correction/stability, long silence, confusable text, and the existing conversation/streaming tests.
- Physical native decoder: three synthetic “Hey Cue” voices accepted; synthetic “Hey you”, “thank you”, and “Hey Google” rejected. These were generated test fixtures, not user recordings or a human-accuracy benchmark.
- Physical 45-second idle check: the AudioRecord session ID and capture-start counter stayed unchanged throughout. This verifies removal of the timed microphone restart. The notification channel was silent and global volume was untouched.
- A background foreground-service probe opened the real overlay and FloatingAssistantActivity. This verifies Android presentation policy, separately from live acoustic recognition.
- Turn off released recording. No raw microphone audio or recognized speech is written to logs or files.
- Foreground playback, queue cancellation, automatic follow-up, End/cancel, and microphone teardown also passed the existing physical integration probe with the new recognizer.
- The quiet build was installed and hands-free restored for a live user check. The user subsequently confirmed “opens” and supplied a screenshot of the native packaging warning, which led to the alignment fix described below. An initial quiet/no-opening reply was confounded by a regression test stopping the listener; a fresh check was requested only after capture was verified active. The final listener was confirmed actively recording and not silenced before the successful user check; synthetic and presentation probes do not substitute for it.

## Targeted ANR / component review

ANR risk: none found in the new capture/model files. Call chain: QuietSpeechSession.start → dedicated CueQuietSpeech worker → QuietSpeechModel.get / model copying / native model initialization / decoder / blocking AudioRecord.read. Main-thread access is guarded against in model initialization. The microphone lock and native close calls are worker-only. Posted UI callbacks never mutate attached views off-main. No main-thread join was introduced.

Internal service intents are explicit, the service remains non-exported, and PendingIntents are immutable. Microphone permission is checked at capture time and runtime errors fail closed. The microphone dot is not hidden or suppressed. No assistant-role, rooting, thermal/governor, Docker, or system-volume changes were made.

## Build and asset review

The English model’s official 40 MB archive is SHA-256 pinned by `scripts/prepare_speech.py`; archive paths and required files are checked. Weights are ignored by Git and packaged for the device. The app/debug APK is now about 53 MB, rather than the earlier text-only shell’s 2.5 MB. This is a deliberate reliability/footprint tradeoff, not a low-power DSP wake implementation.

Vosk 0.3.75 and JNA 5.18.1 are pinned Maven dependencies. The first quiet build used JNA 5.13.0 to work around old D8; a user screenshot then exposed a 16 KB compatibility warning. The old APK failed our artifact check for JNA's 8 KB ZIP offset and a writable tail sharing a rounded 16 KB RELRO page. Checking only LOAD p_align would have missed the latter.

Updated the build to JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.2.21 and Build Tools 35.0.0. SDK/target 33 remain unchanged. The updated JNA and both packaged native libraries pass `scripts/check_apk_alignment.py` and the official `zipalign -c -P 16 4` check. The old APK is rejected by the same guard. No page-size compatibility flag or “Don't show again” suppression was used. A full 16 KB-kernel emulator run is separate from these structural checks and the physical 4 KB-page phone run.

The Gradle wrapper was regenerated twice and its JAR hash matched Gradle's published checksum. Its distribution checksum is pinned, build caching is enabled, and model-validation file resolution occurs during configuration rather than capturing Project during task execution. A distribution download timed out; all four wrapper files were restored and the baseline rechecked, then the official archive was downloaded and checksum-verified before regeneration completed. The build and configuration checks passed afterward. Wrapper backup was kept until validation completed. A staged target-SDK/release-signing and broader ABI modernization remain release work.

## Skills reviewed / installed

Installed with the skill installer and pinned to upstream revisions:

- [Google Android skills](https://github.com/android/skills/tree/5815d27fda564aa9f07ebdcb5e57ed3e8d904e0d): ANR audit, intent security, permissions security, testing setup, and Android profiler.
- [Gradle best practices / wrapper upgrade](https://github.com/gradle/gradle-skills/tree/6d30a9c0dcf863a421f4a0895e34c6a40c844fb1).

Applied relevant threading/lifecycle, permission/component, test-strategy, dependency, and build checks. Existing Views/JUnit/native instrumentation were retained. Kotlin’s official skills were also inspected: their currently listed workflows focus on toolchain/migrations rather than hotword capture; no unrelated framework or AGP 9 migration was applied. AGP 8.9.2, Kotlin 2.2.21 and JDK 17 were selected from their published compatibility tables for the native packaging fix. Profiling and full test-infrastructure migration skills are available for later work; this fix does not claim a Perfetto or battery benchmark.

## Limits and follow-up

A constrained decoder can still confuse acoustically similar phrases (additional synthetic “Hey cute” trials showed collisions). This is not speaker verification, a trained Cue-specific acoustic keyword model, or a validated false-activation rate. Continued conversation can hear nearby voices during its bounded follow-up window; the default explicit-address interruption and End/Turn off controls remain available. Longer quiet/noisy-room, music, calls, Bluetooth, locked-screen, background/process-death, battery, and human-speech trials are still needed. The LLM/vision server and runtime ownership were not changed by this fix.
