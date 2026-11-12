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
