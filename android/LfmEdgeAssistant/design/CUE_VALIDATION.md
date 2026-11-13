# Cue delivery and validation — 12 November 2026

Implemented and installed on the connected Realme GT 8 Pro, retaining package `ai.snapdragon.edge`, the existing widget provider, and migrated conversations.

## Confirmed

- Debug APK assembles; Android lint completes with zero errors (advisory compatibility/localization warnings remain).
- **14 JVM tests pass:** SSE null/control handling, reasoning separation, Unicode, multiline events, length limits, truncated/malformed/error events, cancellation, callback errors, WAV format, and Cue wake-phrase matching.
- **5 controller protocol tests pass**, with Ruff check/format passing for the added Python files.
- User confirmed **“Hey Cue” opens the panel** after the real overlay handoff was implemented.
- After replacing manual silence-based capture with an offline speech turn, the user confirmed **speech stops and submits automatically without Done/Send**.
- Background presentation probe opened FloatingAssistantActivity; a local sample measured 210ms from its synthetic UI trigger to first draw. Another recorded wake sample was 198ms from recognition match to draw. These exclude time before recognition, are individual diagnostic events, and are not p50/p95 or spoken wake benchmarks.
- A real text request completed through the app. The original conversation was retained; one benign test exchange was added during verification.
- New device controller rejects GET mutation (405), missing/incorrect control authorization (403), browser-origin requests (403), and route prefixes (404).
- Authorized idempotent Start preserved model readiness. A separate idle-runtime cycle verified Stop made the model unavailable and Start returned `/health` to ready.
- Display over other apps was enabled by the user and verified as allowed. Microphone/notification access supports the opt-in hands-free service. Default assistant/power-button assignments were not changed.

## Important limits

- Hands-free uses Android's explicitly on-device speech recognizer, **not** the failed small acoustic KWS model. It is experimental; there is no new battery, false-activation, noisy-room, cold-start, or throughput benchmark.
- The lightweight KWS experiment failed recognition of the proposed names and was removed from the APK. Its rejected results and pinned resources are documented under `research/keyword-spotting`.
- Extended physical checks for in-flight Stop, keyboard/landscape/large-text behavior, all image/Share providers, conversation management, and long archives remain worthwhile. No passing result is claimed for those scenarios here.
- Spoken replies and transcription review are available in Settings. Review defaults off so spoken questions submit automatically; spoken replies default off for discreet public use.
- Existing runtime ownership/reboot recovery, default LAN binding, SDK/release-signing modernization, exact token budgeting, and stronger local IPC remain follow-up items listed in README.

The app and runtime changes are installed on the phone. This document records the checks performed before the Cue baseline commit.

## Spoken-reply update (3.1.0)

- Added a synchronized speaker switch in full chat and the floating panel; Settings retains the same preference.
- Automatic speech applies to voice questions only. Typed questions remain silent unless Read aloud is explicitly selected.
- Switching to read-only immediately stops current playback and is checked again when a pending answer finishes, preventing a queued spoken reply after muting.
- Existing preference values were retained. Read-only remains the default until spoken replies are enabled.
- Build, 14 JVM tests, and lint passed. Updated APK installed; the quick control’s accessibility label was checked and the previously enabled foreground wake listener was restored.


## Streaming speech and conversation update (3.2.0)

- Speech queues completed sentences/clauses as answer tokens arrive, without waiting for generation to finish. TTS initialization starts during voice capture. Session IDs reject late tokens and callbacks after mute, Stop, replacement, or navigation.
- Added a visible conversation bar with Interrupt and End, automatic follow-up after generation **and all queued playback** finish, a short echo-tail gap, and bounded no-speech capture. Conversation ends on silence, cancellation, pause/dismiss, thread change, and stalled-turn timeout. Review before sending suspends the loop.
- During an active voice conversation, offline recognition can stop playback/generation on “Hey Cue”. Settings can opt into bare Stop/Wait/Pause or novel multiword input. It retains the recording for the new question. A text echo filter suppresses recognized playback; it is **not** acoustic echo cancellation, speaker verification, or a measured full-duplex implementation. Tap Interrupt/End remains available when recognition is unavailable or repeatedly errors.
- Fixed cancellation of a voice request waiting for engine startup; it cannot submit later after End. Switching threads also stops pending speech/startup requests.
- **26 JVM tests pass** (including sentence/token partitioning, formatting/code fences, long-phrase bounds, echo filtering, wake/control stripping, and genuine interruption questions). Debug app and instrumentation APK assemble; Android lint passes with zero errors.
- Physical Realme test probe passed: first sentence played while stream input remained open; a token gap did not end the speech session; mute rejected late tokens; stale completion did not cancel replacement; follow-up waited for playback completion and started recognition; End prevented restart; dismissal canceled recognition. The probe forced review for microphone checks and restored user preferences/draft afterward.
- Before the quick-voice optimization, a real model sample produced its first visible content at **5,241 ms** and TTS reported first audio at **5,533 ms** from submission, **while generation was still running**. Stop then canceled both inference and playback. The temporary test thread was deleted and original thread restored. These are single diagnostic events, not a benchmark or measured time from a spoken wake phrase.
- Before the quick-voice optimization, a separate warm-runtime HTTP sample with a different question (160-token cap) produced visible content at **3,508 ms**, finishing at **4,407 ms**. This isolates model startup from speech and shows further first-token profiling is needed; no general speedup or p50/p95 claim is made.
- Battery temperature during these checks was about 35.1–35.3°C; reported thermal status was 0 before testing. No governors, thermal protections, runtime bindings, model weights, or default-assistant assignment changed.
- Real spoken interruption, noisy-room echo, Bluetooth/headsets, and multiple back-to-back questions still need user/physical validation. The instrumented probe tests orchestration; it does not establish acoustic recognition quality.

Implementation uses Android's documented [queued TTS](https://developer.android.com/reference/android/speech/tts/TextToSpeech) and [utterance progress callbacks](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener). Follow-up waits for `onDone`, when playback has completed, rather than for synthesis to begin.

- Inspecting the running model’s chat template confirmed `enable_thinking` defaults to true. Added **Quick voice answers**, enabled by default for spoken requests only, sending `chat_template_kwargs.enable_thinking=false`. Settings can restore deeper thinking for voice; typed requests and server defaults are unchanged. This is supported by the installed template and documented by [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md) and [LiquidAI](https://huggingface.co/LiquidAI/LFM3-VL-2B).
- A warm HTTP sample with thinking disabled returned visible content at **321 ms** and completed at **2,649 ms** (same Saturn question, 160-token cap). Cache conditions differed from the earlier enabled-thinking sample; this is a diagnostic observation, not a controlled speedup estimate.


### Voice refinements and llama UI verification — 13 November

- User confirmed that continuous conversation was picking up voices between turns and flagged sensitivity to noise. Default interruption now requires **Hey Cue** during an answer. Open interruption is an explicit opt-in; nearby voices are still possible in the bounded follow-up window, and continued conversation can be disabled in Settings.
- Strengthened speech cleanup for star/bullet variants, escaped Markdown, decorative emoji, table separators, and dash separators. Added regression coverage using the user’s Atomic Habits / The Power of Habit example. Voice requests ask for natural prose instead of Markdown lists. TTS prefers the configured offline system voice, then a matching high-quality offline voice.
- Latest build/lint and **26 unit tests pass**. The final physical probe again passed speech streaming, stale-session cancellation, follow-up handoff, microphone teardown, and Stop. With quick voice mode and the new conversational style, first audio was reported at **753 ms** while generation was still running. An earlier quick-mode app sample was **524 ms**. These are individual app-submission-to-playback callbacks, not spoken-wake latency or a controlled before/after benchmark.
- The separately updated llama UI **v4** was verified in an isolated mobile browser context against the phone’s static UI: one enabled Send button with text, one enabled Send with image + text, submission included the image, and a controlled SSE fixture rendered without browser errors. No browser history or personal photo was used. Its existing v4 fix was retained; this work did not modify its bundle.
- The model server was temporarily started for these checks because it was initially stopped. It is restored to the stopped state afterward. The instrumentation test app and temporary ADB forward are removed. User app preferences, current thread, and draft are restored by the probe.

- The persistent runtime controller was also found stopped after the idle interval. Its existing loopback daemon was restarted, and `/status` plus authenticated-session issuance were verified. Model inference remains stopped until requested. The previously enabled foreground wake listener was restored after instrumentation.


## Quiet capture and native compatibility update (3.3.1)

- Removed Android SpeechRecognizer from automatic wake and foreground voice turns. Cue owns a continuous offline AudioRecord/decoder session and fails quietly on errors; the required microphone indicator remains visible while hands-free is enabled. No global volume or privacy-indicator settings were changed.
- User confirmed the quiet wake now opens Cue, then provided an Android 16 KB compatibility warning. The warning exposed both APK ZIP placement and JNA RELRO layout problems in the first quiet build. These were fixed in the build/native dependency rather than hidden with “Don't show again”.
- Updated JDK/Gradle/AGP/Kotlin/JNA together (JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.2.21, JNA 5.18.1). The wrapper JAR matches Gradle’s published checksum. SDK/target 33 remain unchanged.
- New APK passes the official Build Tools 35 `zipalign -c -P 16 4` and custom ZIP / ELF LOAD / RELRO checks for both native libraries. The old APK fails this guard. After installation, the physical-device UI check found **no Android app compatibility pane**. This is not a 16 KB-kernel emulator benchmark; the connected phone reports a 4 KB kernel page size.
- **30 JVM tests and lint pass**. Final-device probes pass native synthetic wake/confusable decoding, **45 seconds with the same AudioRecord session** and no timed restart, silent notification configuration, background overlay-to-activity opening, and microphone release on Turn off.
- Existing device speech tests also pass with the new backend: early queued playback, mute/stale-callback protection, follow-up after playback drain, cancel without restart, and microphone cancellation on dismissal. Extra echo guards normalize Cue/queue/q and reject playback echo as a follow-up prompt.
- Test orchestration now restores previously enabled hands-free after instrumentation, which otherwise stops the normal app process. The initial quiet/no-opening user check was confounded by such a test; the subsequent live check was requested after capture was verified active and unsilenced. All automated device checks finish before a live user check is requested.
- Instrumentation APK removed, temporary ADB forwards absent, and hands-free restored. During final checks battery was about 32°C and thermal status 0. Model/runtime server settings and user conversations were unchanged.

See [ANDROID_RELIABILITY_REVIEW.md](ANDROID_RELIABILITY_REVIEW.md) for the skill sources, implementation review, build decisions, and remaining noise/battery/phonetic-confusable validation.
