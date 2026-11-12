# LFM: design and implementation plan

Prepared 12 November 2026 from the checked-out Android source and the connected Realme GT 8 Pro. This is a proposal and audit, not a claim that the fixes below are implemented. No application source or device settings were changed during this planning pass.

Visual proposal: [design board](lfm-design-concept.png) · [editable SVG](lfm-design-concept.svg).

## Product direction

An everyday assistant that feels immediately available. Keep the current LFM name and ivory/lilac identity; make the floating panel belong to the same product. Lead with useful actions rather than implementation details.

- Main surfaces: warm ivory `#F8F6F1`, ink `#29242E`, lilac `#ECE5F4`, plum `#7554A4`. Use sage and peach sparingly for suggestions and supportive states.
- Typography: clear body text at 15–16sp; expressive headings at 28–36sp. No all-caps technical badges. Body contrast must reach 4.5:1; controls and focus indicators must remain identifiable without color alone.
- Geometry: 20–24dp page margins, 24–28dp major corners, 48dp minimum action targets. Use real vector icons for microphone, stop, copy, expand, and close instead of emoji or ambiguous glyphs.
- Animation: one 160–200ms sheet entrance, subtle audio motion, no idle animation loops. Respect the system animation scale and large text settings.
- Copy: “Ready”, “Resting”, “Getting ready”, “Listening”, “Thinking”, “Stop”, “Try again”. Keep hardware, model names, ports, and diagnostics in an optional advanced view.

## Proposed screens and behavior

### Home

Keep the greeting, artwork, clear “Ask anything” action, two useful starters, and access to conversations. Move engine management and hands-free configuration to Settings. The readiness badge opens a small status sheet with Start/Pause if needed. A user can ask first; if the model is resting, show the panel immediately, preserve the question, and warm the assistant in parallel.

Settings contains hands-free opt-in and its real status, a voice test, voice response preference, assistant availability/idle behavior, privacy and data deletion, and advanced diagnostics. “Enabled” is a preference; “Listening” is a runtime state, and the interface must distinguish them.

### Floating assistant

Open a compact surface over the current app. Start at roughly 300–360dp tall; cap the surface to 70% of usable screen height, with the composer always visible. On smaller screens or with the keyboard open, calculate the cap from the available height. Scroll the transcript inside the sheet. Full-screen chat is an explicit expansion.

The default shows a welcoming prompt and two honest starters. If returning to a conversation, show a short recent exchange with Continue/New options, not an entire wall of old messages. New starts a separate thread rather than silently clearing the existing one.

The header has the LFM mark, Expand, and Close. No hardware chip. The composer has exactly one trailing action: microphone for an empty draft, Send for text, Stop during generation. Remove the plus button that currently sends an arbitrary question. Add attachment controls only after attachment support actually exists.

- Tapping Ask opens text mode without silently recording.
- Tapping Speak or detecting the wake phrase opens listening mode with an explicit mic indicator, real waveform, Done, and Cancel.
- A voice transcription becomes an editable draft by default. A separate opt-in can enable immediate spoken-question submission.
- Dismissing saves the draft, releases the microphone, and follows a documented generation policy. Default: stop the active request and retain a clearly marked partial answer. Expanding transfers the active session without canceling or restarting it.
- If the user scrolls up, do not pull them back down on every token. Show Jump to latest. Copy uses the actual current answer; Read aloud appears only once speech output is implemented.
- Swipe down, outside tap, Back, and Close behave consistently. While typing, Back closes the keyboard first. Locking the phone or backgrounding the panel releases recording; resuming never starts recording without a fresh explicit action.

### Full-screen chat

Use the same conversation state and components as the floating surface. Include streaming, Stop, Retry, Copy, basic Markdown/code formatting, and accessible selection. Preserve drafts, partial answers, and scroll position through rotation and navigation. Opening Expand lands directly in the current conversation. A conversations list supports returning to earlier threads, renaming, and deletion.

### Widget

Match the app palette and typography. Header: LFM and a small readiness indicator. Body: “A little help, right here.” Actions: Ask anything and Speak, with distinct text/voice entry behavior. Keep quick Start/Pause accessible through the readiness control with clear confirmation of the resulting state; do not make the whole card a destructive toggle. Provide compact/wide layouts with real minimum sizes and preview layouts. Readiness is last checked, not a claim of continuous monitoring.

### States to design together

Ready, resting, starting, listening, no speech, transcribing, thinking, streaming, stopped, complete, offline/connection failure, microphone denied, notification denied, another app using the mic, and wake temporarily unavailable. Every state needs one clear next action and must preserve the user's work.

## Findings from this audit

| Priority | Verified finding | Impact / proposed correction |
|---|---|---|
| P0 | `FloatingAssistantActivity` computes `maxScrollHeight` but never applies it. The current phone capture shows an almost full-screen transcript. | Enforce available-height bounds; scroll messages inside the surface. |
| P0 | `AssistantClient.streamReply` uses `optString` on nullable deltas; an existing answer on the phone visibly begins `nullYou're welcome`. | Accept actual string content only; ignore JSON null/control frames. Add parser regression tests. |
| P0 | Stream parsing catches every exception and can show `reasoning_content` as the answer when content is empty. | Keep reasoning separate, surface protocol/network failures, validate terminal stream events and truncation. |
| P0 | No cancellable request handle or Stop action; `shutdownNow()` does not guarantee an HTTP read disconnect. | Explicitly close the active connection and coordinate server-side generation cancellation. Retain partial output as stopped. |
| P0 | Wake uses the default Android recognizer with `EXTRA_PREFER_OFFLINE`, not a dedicated keyword model. The configured service is GoogleTTSRecognitionService. | An offline preference does not establish that this session is on-device. Prefer an explicitly available on-device recognizer during the interim; replace continuous ASR with a dedicated acoustic keyword detector after testing. |
| P0 | Wake restart requests come from end-of-speech, results, and errors with no single pending restart or active-session guard. | One recognizer state machine, one restart task, bounded backoff, explicit unavailable state. |
| P0 | Wake resumes after a fixed five seconds even if the panel is still recording. | One owner of microphone input; resume wake only after recording/transcription interaction releases ownership. |
| P1 | Floating Expand opens MainActivity without `destination=chat`; live Copy closes over the original empty message. | Transfer the same conversation and draft; copy the current answer value. |
| P1 | Floating recording requests microphone permission but has no grant-result continuation. | Resume the requested action after grant; useful denied/permanently denied states. |
| P1 | Recorder has no maximum recording/no-speech limit; cancellation does not join its worker. | Bound recording time, use monotonic timing and adaptive voice activity detection, stop/join before release, lower allocation rate. |
| P1 | Floating transcription converts all failures into an empty string. | Distinguish no speech, speech server unavailable, timeout, and cancellation with recoverable copy. |
| P1 | Main and floating activities keep separate mutable histories and overwrite the same preferences key. | Shared session/repository, stable message IDs, atomic conversation updates and lifecycle ownership. |
| P1 | Main serial executor mixes generation, status, and engine control. UI posts and forced scrolling happen per token. | Independent control and generation work; coalesce rendering once per frame or every 33–50ms; follow the bottom only when appropriate. |
| P1 | Main stop action ignores the control result and marks the assistant stopped. Widget does the same after a fixed sleep; start acknowledgment is treated as success by the client. | Verify service health after the command, distinguish accepted/starting/ready/failed, and display real failure/retry. |
| P1 | “Inspire me” currently opens the floating activity without passing a prompt. “Quick summary” asks about quantum computing. | Make action labels match what happens; never pretend there is screen context. |
| P1 | Notification permission is currently denied, while the hands-free service reports itself running. | Explain notification access at hands-free opt-in, check actual runtime recognition availability, provide a visible stop control and supported notification fallback. |
| P1 | Runtime scripts live only on the device; controller status checks process existence rather than readiness. | Version the scripts and installation procedure; probe both LLM and speech readiness independently. Test a cold recovery. |
| P1 | Device scripts bind LLM and Whisper to `0.0.0.0`; the loopback controller accepts unauthenticated GET mutations and sends wildcard CORS. | Default inference to loopback, with explicit opt-in for LAN access if still needed. Replace ambient controller access with authenticated exact-path commands and appropriate methods. Preserve the user's deliberate laptop workflow. |
| P2 | No text-to-speech, image attachment pipeline, screen context capture, speaker verification, multi-thread chat UI, or runtime latency metrics found. | Treat these as missing features, not shipped capabilities. Add in the order below. |
| P2 | App still targets SDK 33 on this newer phone; release builds use the debug signing key. | Stage SDK/toolchain migration, permission/background behavior tests, and a real release-signing workflow. |

Read-only health requests succeeded for the model and controller in this session. This does not re-validate an unplugged start/stop cycle, reboot survival, speech accuracy, battery usage, or the previously reported 40 tokens/second. None of those are newly measured results here.

## Fixing the 5–10 second “Hey LFM” delay

The 5–10 seconds is the user's observed delay, not an instrumented benchmark. The current architecture plausibly waits for speech-recognition endpointing or provider scheduling. The code's fixed five-second delay is a cooldown **after** a trigger; it does not explain the initial wake delay by itself. The 250ms delay before starting panel recording is also separate from panel presentation. Do not replace those constants and claim the root cause is solved.

### Measure first

Use monotonic timestamps for audio phrase-end (from controlled playback/annotated recordings), keyword/partial match, wake dispatch, activity creation, first visible frame, microphone readiness, transcription completion, request sent, first answer token, and generation finish. Production diagnostics store timings and error codes, not raw audio or recognized speech. Report p50/p95 and failure counts, splitting screen-on, another-app foreground, locked, cold-start, and warm-start scenarios.

### Immediate correction

1. Render the panel immediately on detection; neither model health checks nor model startup may block presentation.
2. Use a single recognizer session with one deduplicated restart callback; do not restart from end-of-speech before the terminal callback.
3. Trigger on the earliest valid partial wake match. Remove broad aliases such as “hey assistant” and “hey liam”; use tested LFM pronunciation variants and boundaries.
4. Prefer Android's explicitly on-device recognizer where available, with a clear unsupported path. Partial-result and silence-hint support depend on the provider, so this remains an interim approach.
5. Release/pause wake capture before voice input begins. Keep wake paused until the panel returns the microphone, not for an arbitrary five seconds.
6. Remove double entrance animations and unnecessary startup work from the UI path. Model warmup and request readiness remain separate states.

### Durable solution

Run a dedicated offline acoustic keyword detector on short PCM frames. Evaluate sherpa-onnx's Android keyword-spotting example first, with a model/license/size review and custom “Hey LFM” pronunciation tests; it is a candidate, not a committed dependency or a promised latency result. Keep the main LLM and Whisper out of the continuous wake loop. One audio owner can retain a short in-memory pre-roll to avoid clipping the beginning of a question; discard it when unused and do not record continuously to disk.

A generic foreground service does not automatically grant unrestricted background activity launches. Keep the existing system assistant unchanged. Validate a compliant background launch route on the phone; if a true overlay is necessary, explain the optional Draw over other apps permission and request it only through an explicit user action. Where launch is restricted, use a direct notification PendingIntent with notification access. Do not use full-screen alarm/call privileges for a normal assistant. Define locked-screen behavior explicitly; no silent microphone activation behind a lock screen.

## Architecture and delivery order

Keep native Android for this pass. A full framework rewrite would add risk without resolving the current state and lifecycle bugs. Extract a small shared design layer and session infrastructure first; then replace the duplicated screen logic.

### 1. Reliability foundation and instrumentation

Deliver a shared `AssistantSession` / `ConversationRepository`, explicit generation and recording jobs, request cancellation, robust SSE parsing, a readiness model for LLM/STT/controller, and monotonic latency tracing. Separate controller work from long generation. Save user drafts as they change; use stable conversation/message IDs rather than whole-history overwrites. Maintain existing user data during migration.

Tests: null and empty deltas, fragmented/multiline events, malformed/error events, missing DONE/finish markers, length-limited output, cancellation before/after the first token, timeouts, stale callbacks, duplicate sends, and background/rotation during generation. Verify partial retention and no unintended restart when expanding.

### 2. Floating experience and wake hotfix

Implement bounded sheet sizing, the new compact states and single composer action, honest starters, live Copy, correct expansion, recoverable failure UI, permission continuation, microphone ownership, and the interim wake fixes above. Reuse the shared session. Keep animation and recording state independent of model readiness.

Tests: first-time permissions; denial and permanent denial; keyboard open/closed; large font; narrow/landscape screen; long answers; dismiss/reopen; repeated wake phrases; mic conflict with another app. Verify direct widget tap to first frame and preserve current conversation history.

### 3. Dedicated wake detector and runtime reliability

Run the KWS spike, decide model/runtime from measured latency/false triggers/power, then integrate only if it passes. Include opt-in, sensitivity testing, clear active/paused/error states, notification controls, locked-screen behavior, and device background-launch policy testing. Version and harden the existing scripts/controller, make Start idempotent, and test stop/start, USB detach, process kill, reboot, and Termux-unavailable recovery independently. Process detachment alone is not reboot persistence or OS-kill immunity.

### 4. Home, chat, widget, and settings consistency

Apply the visual system across all entry points. Add the settings and conversation-list surfaces, basic Markdown/code rendering, Stop/Retry/Copy parity, accessibility, stateful compact widget layouts, and honest readiness updates. Preserve LFM branding and remove consumer-facing technical copy. Request each capability's permission when that feature is enabled, not on every first launch.

### 5. Complete useful features, then tune

First: optional Read aloud and Stop speaking; text-share input; chat search/rename/delete and data controls. Then: image attach and a genuine image-to-model request using the existing vision capability; explicit screen-context capture only after a user-initiated capture/sharing flow. Speaker verification, custom wake phrases, and broader multilingual support are separate later milestones with enrollment/accuracy work. The current English speech model does not establish multilingual readiness.

Profile before changing model configuration. Compare realistic 4k/8k/32k context settings, prompt budgets, cache reuse, and cold/warm load costs on the phone. Track prompt evaluation separately from decode speed and transcription. Use bounded context by token budget rather than just last-N messages. Avoid eager loading of speech/vision components that are not needed. Keep idle UI work near zero, bound health polling to active surfaces, and measure wake memory/power independently from LLM residency.

## Acceptance targets and release gates

All numbers below are proposed targets, not measured achievements. Adjust model-dependent targets after the baseline.

| Area | Target / gate |
|---|---|
| Widget/text entry | Warm tap to first usable panel frame p95 ≤300ms; no dependency on model availability. |
| Voice wake | Phrase-end to visible panel p50 ≤500ms, p95 ≤1s on an unlocked, warm device. Report cold/background/locked cases separately. |
| Wake accuracy | At least 95% detections in the defined quiet 1m test set; ≤1 false activation per 8 hours of mixed background playback. Include accents and multiple users, then report noisy/distance results separately. |
| Streaming | No null/reasoning leakage, no duplicated text, Stop available throughout. Client request disconnect target ≤250ms; verify server slot/work also releases. |
| Rendering | Coalesced updates; p95 UI frame duration ≤16.7ms at a 60Hz test reference; user scrolling is not overridden. Evaluate the phone's higher refresh mode separately. |
| Audio | No-speech timeout around 8s, configurable bounded recording around 60s, cancellation releases audio promptly; wake never competes with the panel or playback. |
| Lifecycle | Expand, Back, rotate, dismiss/reopen, process recreation, and permission denial preserve work and never leave a hidden recording job. |
| Engine | Truthful starting/ready/stopping/failed states, idempotent repeated Start, actionable timeout, independent speech readiness, and verified cold/unplugged/reboot recovery paths. |
| Power | Proposed extra screen-off wake budget ≤1 battery percentage point/hour over matched baseline; verify with longer unplugged paired trials/coulomb counters where available. No “zero drain” claim. |
| Safety | Respect Android thermal limits; stop intensive trials around 40–42°C battery or thermal status ≥3, per repository instructions. |
| Accessibility | 48dp controls, TalkBack labels/order, 200% text scaling, adequate contrast, reduced motion, and keyboard/switch access. |

Run unit tests for protocol/session behavior, instrumentation tests for permissions/lifecycle and navigation, visual checks for every state, and physical-device audio/power/thermal trials. Preserve the pre-change behavior and user history before migration. A build passing and an APK installing are necessary checks, not proof of voice speed or reliability.

## Sources informing platform decisions

- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer): general recognition may use remote processing and is not intended for continuous recognition; explicit on-device recognition APIs are available on supported devices.
- [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent): offline preference can be ignored by an implementation.
- [Android background activity launch restrictions](https://developer.android.com/guide/components/activities/secure-bal): foreground services do not grant unrestricted UI launch rights.
- [Android foreground service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): background starts and microphone permissions require supported lifecycle/permission flows.
- [sherpa-onnx keyword spotting](https://k2-fsa.github.io/sherpa/onnx/kws/index.html) and [Android examples](https://github.com/k2-fsa/sherpa-onnx/tree/master/android): candidate implementation for a dedicated detector; model suitability still needs evaluation on this phone.
