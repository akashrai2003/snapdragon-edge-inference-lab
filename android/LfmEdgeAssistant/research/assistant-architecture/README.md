# Cue as an on-phone assistant

Research and implementation decision, 14 November 2026.

## Decision

Keep Cue's Android app, working wake/voice pipeline and phone-hosted LFM3 inference. Build a small Kotlin agent runner around **real tools**. Reuse libraries and selected implementations rather than transplanting a complete desktop assistant stack.

The phone model owns the task: choose a tool, observe its result, decide the next step, and answer. Browser state, personal memory, screen context and Android actions remain on the phone. An optional larger model receives an explicitly scoped subtask when useful; it does not inherit Cue's entire conversation or acquire direct Android permissions.

**No always-on computer is required.** Search networking, browser execution, native integrations and task state run on Android. OmniRoute is an optional phone-local proxy for remote model APIs; a remote LLM still runs at its provider. Internet access is needed for web search and cloud delegation.

This directory contains research and a concrete implementation specification. It does **not** add these capabilities to the installed app. Cue 3.3.2 was left running without an installation, microphone test, new permission, provider login, Docker operation, or model change during this research.

## What was done

- Cloned the twelve assistant projects and the directory linked in the attachment, resolving renamed repositories and the OpenVoiceOS organization to `ovos-core`.
- Added search, mobile control, browser and MCP references; followed relocated implementations for Letta, OpenHands and Playwright; included the user's OmniRoute link. Total: **25 shallow, sparse Git checkouts**, about **864 MB** when collected.
- Inspected targeted implementation paths for dispatch, tool schemas/results, cancellation, browser observations, Android control, memory and model routing. Recorded **67 evidence paths** and exact Git revisions in [references.json](references.json).
- Kept source checkouts at `Projects/cue-assistant-references`, alongside `Projects/snapdragon-edge-inference-lab`. No upstream source tree, dependency environment, binary, screenshot, model or credentials were added to this repository.

These are source-review findings, not upstream runtime tests, an exhaustive security audit, a new phone performance benchmark, or a measurement of local tool-call accuracy.

## Reuse choices

| Area | Choice for Cue | Most useful references |
| --- | --- | --- |
| Agent loop | Small Kotlin runner with typed tool calls/results, cancellation and bounded turns | OpenHands SDK, Leon, OVOS |
| Android actions | Native APIs/intents first; selectively adapt Android implementations with attribution | OpenClaw Android, Home Assistant's capability registry |
| Browser | An owned Android WebView with observable, typed actions and fresh element references | Playwright backend/ARIA snapshots, Browser Use |
| General search | Phone-side provider adapters, source-page reading and browser fallback | DDGS, SearXNG, Khoj |
| MCP | Official Kotlin client where compatible; native tools stay in process | Kotlin/Python SDKs, OpenClaw's tool filtering |
| Larger-model help | Separate public-subtask client; optional pinned OmniRoute in Termux | OmniRoute, OpenHands model routing |
| Memory | Small, explicit app-private memory with edit/delete and retrieval budgets | Letta Code |
| Voice/tool interaction | Extend the existing native pipeline with task events and cancellation | Pipecat, LiveKit Agents |

OpenClaw is the strongest candidate if we wanted a complete assistant fork: it already has a gateway, tools and Android capability handlers. That is a viable alternative to evaluate, including a phone-hosted gateway. My recommendation is selective reuse because Cue's purpose is to demonstrate a compact **on-phone model orchestrator**, retain the existing native experience, and measure what that model actually accomplishes. Full-stack adoption would introduce another runtime and its state/permission/session conventions before that experiment is established. This is an architectural judgment, not a claim that OpenClaw cannot use local models.

## Search without a paid search subscription

The realistic goal is **no mandatory paid search API and no artificial Cue query allowance**. A headless browser does not make upstream search unlimited: engines can change markup, rate-limit, require CAPTCHA or refuse automation. SearXNG itself implements engine suspension and denied-access handling; public instances often disable JSON. [SearXNG search API](https://docs.searxng.org/dev/search_api.html).

Start with a phone-side `web_search` interface, lightweight HTTPS providers, normalized title/URL/snippet results, deduplication, bounded caching, timeouts and polite backoff. DDGS is useful source material, but its native Python dependencies need Android qualification; copying its request code does not guarantee availability. Add an optional configured SearXNG endpoint without making a public instance or a home computer essential.

Use `web_read` to fetch the actual source pages and answer with links and retrieval time. When a page needs JavaScript or normal interactive access, use Cue's on-phone browser. A site challenge gets a visible user handoff. Failed search must produce an honest unavailable result, not an invented current answer. Search queries themselves leave the phone, so private memory is not automatically added to a public query.

## What “all apps” can realistically mean

Use Android-supported actions for opening apps, maps, timers/alarms, dial/message drafts, calendar flows and sharing. These are faster and more stable than tapping screenshots. Available actions vary by installed app, permissions and platform version. Opening a compose screen is reported as a draft, not a sent message. [Android common intents](https://developer.android.com/guide/components/intents-common).

For richer UI automation, study OpenClaw's snapshot-bound Android actions and Mobilerun's traversal/driver separation. This is an optional, explicitly enabled research capability, not a claim to have Siri/Google system privileges. Secure windows, missing accessibility nodes, lock screens and unsupported apps can require a user handoff.

There is a concrete distribution constraint: Google Play prohibits general autonomous planning/execution through AccessibilityService outside the qualifying accessibility-tool exemption. A broad consumer voice assistant does not qualify merely because voice control is useful. OpenClaw's inspected Play flavor returns unavailable for mobile UI control, while its third-party flavor implements it. Plan a research/sideload flavor for that experiment; keep supported native integrations and Cue-owned browser tools independent of it. [Google Play AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491).

## Recommended first delivery

Implement the tool-call protocol and a **phone-only web-answer flow** first: spoken request → local model chooses search → phone retrieves evidence → local model gives a sourced answer. Add open-app/maps/alarm actions alongside it. That makes the on-device orchestration claim observable before adding general UI control or cloud assistance.

The model publisher documents LFM3-VL's `qwen3_coder`-style tool calls and tool schemas. Cue's current `AssistantClient` sends chat/image messages and `StreamDecoder` reads answer content; neither is an agent executor. The installed GGUF template/parser and streaming `tool_calls` behavior must be qualified with a dry-run suite. Published model support is not a measured task-success result on this phone. [LFM3-VL model card](https://huggingface.co/LiquidAI/LFM3-VL-2B).

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for components, interfaces, milestones and acceptance gates; [SOURCE_REVIEW.md](SOURCE_REVIEW.md) for all repository findings; and [evaluation_cases.json](evaluation_cases.json) for the initial unexecuted task set.

## Keeping or removing the references

Keep the sparse checkouts for the next implementation phase. They are outside the project checkout and will not be pushed with Cue. To check them or recreate missing clones at the recorded revisions:

```sh
python3 acquire_references.py --check
python3 acquire_references.py
# Or choose a different local directory:
python3 acquire_references.py --root /path/to/cue-assistant-references
```

The script does not update existing clones, overwrite another revision, install dependencies or run upstream setup scripts. Deleting the external checkouts later does not remove the research decisions or revision manifest. Licenses differ across projects, including AGPL components and an SDK licensing transition; retain upstream notices and make a deliberate license choice before copying implementation code. No code was incorporated into Cue in this research change.
