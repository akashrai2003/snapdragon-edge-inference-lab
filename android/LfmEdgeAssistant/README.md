# Cue for Android

**Cue** — “Hey Cue.” A native personal assistant with a warm ivory/lilac app, compact floating conversation, and home-screen widget. The existing application ID (`ai.snapdragon.edge`) and widget provider are retained, so updates preserve the installed app and its original conversations.

## Implemented

- Home with everyday language, artwork, text/voice entry, and useful starters. Runtime controls live in Settings/status.
- Compact floating panel with bounded transcript height, automatic speech endpointing/submission (optional transcription review), real microphone-level waveform, Cancel/Done, image attachment, and text input.
- Quick voice answers uses the installed model’s non-thinking mode per voice request, avoiding a hidden reasoning delay. Turn it off in Settings to allow more thinking on difficult questions. Typed requests keep the default model behavior; server defaults and other clients are unchanged.
- Spoken questions request conversational prose. Speech cleanup removes Markdown/list markers and decorative symbols, turns list separators into pauses, and chooses the system’s offline voice or the best available local alternative.
- Sentence-by-sentence speech starts during generation, with TTS warmed during voice capture. Formatting is cleaned for speech and fenced code is skipped. Muting cancels the speech session, so later tokens cannot restart it. Typed questions stay silent.
- Voice conversation mode offers an automatic follow-up window of up to 8 seconds after the answer and queued speech finish. A visible End control, silence timeout, leaving the app, or “end conversation” finishes listening. Settings can disable continued conversation; review-before-send suspends it.
- During a voice conversation, say “Hey Cue” to interrupt or tap Interrupt. Settings can opt into interruption by novel multiword speech with a text echo filter; this is experimental, not acoustic echo cancellation. Default interruption requires an explicit “Hey Cue”, so ordinary background conversation cannot become a new request during playback. The same recognition turn keeps the beginning of a new question while stopping both generation and speech.
- One composer action at a time: mic, Send, or Stop. A speaker switch in both chat headers enables automatic voice replies or read-only mode; switching off stops current speech and also applies to pending answers. Streaming handles JSON null correctly, separates reasoning from answer text, detects incomplete streams, and preserves stopped/failed answers for Retry.
- Shared conversation/session state across full screen and floating views. Expand carries the same conversation and active generation. Drafts, partial-answer checkpoints, and older LFM/Muse conversation migration are supported. Serialization runs on a dedicated persistence worker.
- Conversations list with search, rename, delete, and new threads; selectable basic Markdown/code text; live Copy; Read aloud/Stop speaking using an installed offline voice.
- Image picker and Android text/image Share input. Images are resized and copied into private app storage; image requests use the existing vision endpoint. These paths still need broader provider/image testing.
- Widget with separate Ask anything and Speak actions, compact/wide layouts, and last-checked readiness.
- “Hey Cue” uses one continuous, app-owned AudioRecord with a bundled offline Vosk decoder. It does not repeatedly launch Android’s short speech-recognition sessions or their system tones. Capture pauses for foreground Cue interaction; the visible conversation owns its own quiet turn.
- A genuine `TYPE_APPLICATION_OVERLAY` assistant surface on wake, automatically opening the conversation after drawing. No extra confirmation tap is needed. This addresses the phone's background-activity restriction after the user enables Display over other apps. Notification/widget entry remains available. Locked phones do not start automatic question recording.
- An enabled wake service requests Android's sticky process recovery. Opening Cue also restores an enabled-but-missing listener after an update. Turn off stays off; capture failures stop quietly and retry only when Cue is reopened. Force-stop, reboot, and manufacturer battery restrictions can still require opening Cue again.
- Microphone/notification/overlay setup, bounded recording and no-speech behavior, local timing/error diagnostics without speech text, and truthful assistant start/stop verification.
- Versioned runtime scripts and loopback controller protocol: exact routes, POST-only mutation, per-run control token, rejected browser Origin headers, and no wildcard CORS. The token endpoint is protected against cross-origin reads by the browser; this is **not** authentication against malicious local Android applications.

## Connections and privacy

The app uses the existing phone services at `127.0.0.1:8080` (answers/vision), `8081` (Whisper fallback transcription), and `8082` (runtime control). Voice capture and wake recognition use the bundled offline Vosk model and app-owned AudioRecord, with bounded endpointing and cancellation. The Android microphone indicator stays visible while hands-free is enabled; Turn off releases capture. There is no app-level system-volume muting. Speech is submitted automatically by default; Settings can enable review before sending, optional spoken replies, and continued conversation. Continued conversation defaults on for voice requests and is visibly indicated; spoken replies still default off. Expanding the panel ends hands-free listening while retaining the thread and active answer; tap the microphone in the full view to resume conversation. The user confirmed the original wake → record → stop on silence → send flow and that the new quiet wake path opens Cue on the connected phone. No hosted chat API or analytics is configured. The app does not invoke a platform/cloud recognizer for wake or voice turns. Missing model/native/microphone support stops hands-free quietly; reopening Cue retries an enabled listener. Hands-free remains experimental and can consume battery.

The existing model/speech daemons still bind to all network interfaces as configured before this update, preserving the user's existing laptop workflow. The app itself permits cleartext only to loopback. Restricting those daemons to loopback, adding deliberate LAN sharing, stronger application-to-daemon authentication, and a persistent app-owned runtime are follow-up work.

Saved chats and imported images stay in private app storage and are excluded from Android backups. Delete all conversations also removes private attachments and the old migration preferences. No raw microphone audio or recognition transcripts are written to diagnostic logs or counters.

## Build and check

JDK 17, Android SDK platform 33, Build Tools 35.0.0, and the checked-in Gradle 8.11.1 wrapper (AGP 8.9.2 / Kotlin 2.2.21). Set JAVA_HOME to your JDK 17 installation:

```sh
python3 scripts/prepare_speech.py
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
python3 scripts/check_apk_alignment.py app/build/outputs/apk/debug/app-debug.apk
python3 -m unittest discover -s runtime -p 'test_*.py' -v
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Physical-device voice smoke test (briefly plays a test phrase and opens the microphone):

```sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/verify_voice_device.py
# Quiet native decoder + 45-second recording soak + background opening:
python3 scripts/verify_voice_device.py --quiet
# Also verify recovery after killing only Cue's idle process (hands-free must be enabled):
python3 scripts/verify_voice_device.py --quiet --recovery
# Optional real-model timing/Stop sample; removes its temporary thread afterward:
python3 scripts/verify_voice_device.py --model
adb uninstall ai.snapdragon.edge.test
```

The native instrumentation probe restores the original review/speech preferences, selected thread, and draft. It never submits microphone input: review is forced on for its recognition checks. Instrumentation stops the normal app process; the helper restores previously enabled wake capture from the foreground after a successful probe and waits for capture readiness. The quiet helper then verifies floating opening again **outside instrumentation**, whose background-start privileges could otherwise hide a real launch failure. Leave the phone on Home untouched during these checks. Finish all automated tests before asking for a live voice check. The probe APK is separate from the shipped app.

Cue 3.3 targets the arm64 phone and packages the pinned offline English model and native runtime; the debug APK is approximately 53 MB. Model weights/native libraries are not committed to Git. The preparation script validates the official archive’s SHA-256; Gradle verifies that prepared assets exist. Vosk 0.3.75 and JNA 5.18.1 are packaged with the updated Android plugin for 16 KB compatibility. Both native ZIP offsets and ELF LOAD/RELRO layout are checked before installation. The rejected sherpa keyword-spotting experiment and checksum-pinned asset preparation are documented under `research/keyword-spotting/`; those binaries/models are ignored by Git and are not packaged.

## Runtime controller

`runtime/controller.sh` and `controller_handler.sh` replace only the existing controller. Other scripts are versioned copies of the existing device setup. Preserve the owning execution context: the current runtime is shell-owned, so a Termux fallback cannot necessarily stop an existing shell-owned process. The app verifies readiness rather than treating dispatch as success.

Before the device update, controller scripts were backed up under `/sdcard/Download/cue-controller-backup/`. Controller detachment survives USB disconnect in the current setup; it does not promise reboot survival or immunity to Android process termination. Do not deploy scripts or change model configuration without keeping a rollback copy and verifying health.

## Remaining work

- Measure real spoken wake latency, missed/false activations, long idle/background/locked behavior, and paired unplugged battery use. A successful UI-opening probe is not a wake accuracy benchmark.
- Benchmark long idle battery/CPU use, phonetic confusables, and background reliability of the continuous Vosk path. A 45-second stable-capture check is not a battery or wake-accuracy benchmark.
- Develop/train/select a dedicated acoustic wake model that actually recognizes Cue reliably. The tested small KWS model failed both a user trial and synthetic Cue samples and was removed from the shipped path.
- Measure real barge-in latency, speaker echo/false interruptions, headset/Bluetooth routing, recognition gaps, follow-up timeout and long-session battery use. A text echo filter cannot provide full acoustic echo cancellation or speaker identification.
- Profile time to first visible model token separately from the TTS handoff; current samples are dominated by model response startup.
- Test voice input/noise, Read aloud, image/Share providers, accessibility at large font sizes, process recreation, and long chat archives more broadly.
- Token-accurate prompt budgeting, larger-history database storage, structured profiling of prefill/decode/transcription, and controlled context/cache tuning. No new throughput claim is made here.
- Runtime ownership/reboot recovery, cooperative server termination, default loopback inference with explicit LAN sharing, stronger IPC/authentication, and staged SDK/toolchain/release-signing migration.
- Explicit user-initiated screen context, speaker verification/enrollment, custom wake phrases, and multilingual speech models. These are not shipped capabilities.

See [Cue validation](design/CUE_VALIDATION.md) for delivered behavior and test results, and the [Android reliability review](design/ANDROID_RELIABILITY_REVIEW.md) for the implementation and build review.

See `design/VOICE_NEXT_STEPS.md` for the audio/omni evaluation plan and current limits.
