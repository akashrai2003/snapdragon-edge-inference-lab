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
- Experimental “Hey Cue” using Android's **explicit on-device SpeechRecognizer**, early partial matching, one restart loop, stale-callback protection, and microphone coordination. The background wake listener pauses during foreground interaction and speech playback; the visible conversation owns a separate on-device recognition turn while active.
- A genuine `TYPE_APPLICATION_OVERLAY` Cue card on wake before opening the conversation. This addresses the phone's background-activity restriction after the user enables Display over other apps. Notification/widget entry remains available. Locked phones receive a notification rather than automatic question recording.
- Microphone/notification/overlay setup, bounded recording and no-speech behavior, local timing/error diagnostics without speech text, and truthful assistant start/stop verification.
- Versioned runtime scripts and loopback controller protocol: exact routes, POST-only mutation, per-run control token, rejected browser Origin headers, and no wildcard CORS. The token endpoint is protected against cross-origin reads by the browser; this is **not** authentication against malicious local Android applications.

## Connections and privacy

The app uses the existing phone services at `127.0.0.1:8080` (answers/vision), `8081` (Whisper fallback transcription), and `8082` (runtime control). On supported phones, one-shot voice input uses Android’s explicit on-device recognition and end-of-speech detection. Speech is submitted automatically by default; Settings can enable review before sending, optional spoken replies, and continued conversation. Continued conversation defaults on for voice requests and is visibly indicated; spoken replies still default off. Expanding the panel ends hands-free listening while retaining the thread and active answer; tap the microphone in the full view to resume conversation. The user confirmed the wake → record → stop on silence → send flow on the connected phone. No hosted chat API or analytics is configured. Wake recognition explicitly requires Android on-device recognition support; the app does not fall back to a cloud speech recognizer. Hands-free remains experimental and can consume battery.

The existing model/speech daemons still bind to all network interfaces as configured before this update, preserving the user's existing laptop workflow. The app itself permits cleartext only to loopback. Restricting those daemons to loopback, adding deliberate LAN sharing, stronger application-to-daemon authentication, and a persistent app-owned runtime are follow-up work.

Saved chats and imported images stay in private app storage and are excluded from Android backups. Delete all conversations also removes private attachments and the old migration preferences. No raw microphone audio or recognition transcripts are written to diagnostic logs or counters.

## Build and check

Java 11, Android SDK platform 33, and the existing Gradle wrapper:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 -m unittest discover -s runtime -p 'test_*.py' -v
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Physical-device voice smoke test (briefly plays a test phrase and opens the microphone):

```sh
./gradlew assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w ai.snapdragon.edge.test/ai.snapdragon.edge.VoiceIntegrationProbe
# Optional real-model timing/Stop sample; removes its temporary thread afterward:
adb shell am instrument -w -e model true ai.snapdragon.edge.test/ai.snapdragon.edge.VoiceIntegrationProbe
adb uninstall ai.snapdragon.edge.test
```

The native instrumentation probe restores the original review/speech preferences, selected thread, and draft. It never submits microphone input: review is forced on for its recognition checks. Instrumentation stops the normal app process; restore an enabled wake service from the foreground afterward. The probe APK is separate from the shipped app.

The normal app has no native KWS dependency and is approximately 2.5 MB. The rejected keyword-spotting experiment and checksum-pinned asset preparation are documented under `research/keyword-spotting/`; those binaries/models are ignored by Git and are not packaged.

## Runtime controller

`runtime/controller.sh` and `controller_handler.sh` replace only the existing controller. Other scripts are versioned copies of the existing device setup. Preserve the owning execution context: the current runtime is shell-owned, so a Termux fallback cannot necessarily stop an existing shell-owned process. The app verifies readiness rather than treating dispatch as success.

Before the device update, controller scripts were backed up under `/sdcard/Download/cue-controller-backup/`. Controller detachment survives USB disconnect in the current setup; it does not promise reboot survival or immunity to Android process termination. Do not deploy scripts or change model configuration without keeping a rollback copy and verifying health.

## Remaining work

- Measure real spoken wake latency, missed/false activations, long idle/background/locked behavior, and paired unplugged battery use. A successful UI-opening probe is not a wake accuracy benchmark.
- Develop/train/select a dedicated acoustic wake model that actually recognizes Cue reliably. The tested small KWS model failed both a user trial and synthetic Cue samples and was removed from the shipped path.
- Measure real barge-in latency, speaker echo/false interruptions, headset/Bluetooth routing, recognition gaps, follow-up timeout and long-session battery use. A text echo filter cannot provide full acoustic echo cancellation or speaker identification.
- Profile time to first visible model token separately from the TTS handoff; current samples are dominated by model response startup.
- Test voice input/noise, Read aloud, image/Share providers, accessibility at large font sizes, process recreation, and long chat archives more broadly.
- Token-accurate prompt budgeting, larger-history database storage, structured profiling of prefill/decode/transcription, and controlled context/cache tuning. No new throughput claim is made here.
- Runtime ownership/reboot recovery, cooperative server termination, default loopback inference with explicit LAN sharing, stronger IPC/authentication, and staged SDK/toolchain/release-signing migration.
- Explicit user-initiated screen context, speaker verification/enrollment, custom wake phrases, and multilingual speech models. These are not shipped capabilities.

See `design/next/DESIGN_AND_IMPLEMENTATION_PLAN.md` for the original audit and targets. It predates implementation and branding; this README and `design/CUE_VALIDATION.md` describe what was actually delivered and tested.

See `design/VOICE_NEXT_STEPS.md` for the audio/omni evaluation plan and current limits.
