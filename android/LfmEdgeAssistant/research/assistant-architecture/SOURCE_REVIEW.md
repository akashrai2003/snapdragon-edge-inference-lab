# Source review — 14 November 2026

25 shallow, sparse reference repositories are kept outside Cue's Git checkout. All twelve assistant projects and the directory linked in the attachment were cloned; additional repositories cover actual moved implementations, search, Android control, browser tools, MCP and OmniRoute. We did not recursively clone every entry in the awesome directory.

This is a targeted inspection of the paths below, not a claim to have audited every file, run each stack, tested phone compatibility, or benchmarked any new assistant capability. Upstream code has not been copied into Cue. Exact revisions, license filenames and sparse checkout paths are in [references.json](references.json).

## Findings by repository

### OpenVoiceOS/ovos-core

Revision `ff2012175ba5` · Apache-2.0 · **reference**

Intent dispatch owns one start and one terminal event, with timeout and session-scoped state. Reuse that lifecycle pattern in Cue; the Python/message-bus stack is not an Android replacement.

Inspected: [ovos_core/intent_services/dispatcher.py](https://github.com/OpenVoiceOS/ovos-core/blob/ff2012175ba5be072daa4eb9acaf38b4f7ecbd03/ovos_core/intent_services/dispatcher.py), [ovos_core/intent_services/working_session.py](https://github.com/OpenVoiceOS/ovos-core/blob/ff2012175ba5be072daa4eb9acaf38b4f7ecbd03/ovos_core/intent_services/working_session.py).

### home-assistant/core

Revision `08920ecb3ae1` · Apache-2.0 · **optional integration**

Tool schemas, explicit results, user context, and filtered MCP exposure are separated from the language model. Integrate a user-owned Home Assistant later; do not require its server for ordinary phone actions.

Inspected: [homeassistant/helpers/llm.py](https://github.com/home-assistant/core/blob/08920ecb3ae1d96049f3ca03b7e14601c33aadb4/homeassistant/helpers/llm.py), [homeassistant/components/mcp_server/server.py](https://github.com/home-assistant/core/blob/08920ecb3ae1d96049f3ca03b7e14601c33aadb4/homeassistant/components/mcp_server/server.py).

### leon-ai/leon

Revision `3b56d83126cc` · MIT · **reference**

Execution handles retain progress, cancellation and results without reissuing a tool, and check profile/session ownership. Skill manifests describe functions. Adapt those contracts to Kotlin rather than bundling its server.

Inspected: [server/src/core/tool-manager/tool-execution-manager.ts](https://github.com/leon-ai/leon/blob/3b56d83126ccf38a72f70282e91515cc3a18a3de/server/src/core/tool-manager/tool-execution-manager.ts), [bridges/runtime/contracts/skill-tool-protocol.ts](https://github.com/leon-ai/leon/blob/3b56d83126ccf38a72f70282e91515cc3a18a3de/bridges/runtime/contracts/skill-tool-protocol.ts), [skills/native/timer_skill/skill.json](https://github.com/leon-ai/leon/blob/3b56d83126ccf38a72f70282e91515cc3a18a3de/skills/native/timer_skill/skill.json).

### rhasspy/rhasspy

Revision `083721a92a9a` · MIT · **historical reference** · **archived**

The archived service launcher dispatches independent speech, dialogue and intent modules; README shows deterministic slot grammars. Useful for fast local commands, not the basis for a new general browser agent. Submodule implementations were not recursively cloned.

Inspected: [rhasspy/__main__.py](https://github.com/rhasspy/rhasspy/blob/083721a92a9a12396c794003a7f9ebcdd8dffa60/rhasspy/__main__.py), [README.md](https://github.com/rhasspy/rhasspy/blob/083721a92a9a12396c794003a7f9ebcdd8dffa60/README.md).

### openclaw/openclaw

Revision `eae12351406e` · MIT · **selective reuse candidate**

Strongest Android action reference: one advertised/dispatch table, live permission gates, foreground restrictions and snapshot-bound UI actions. Play and third-party mobile-control implementations differ. Keep Cue as the on-phone orchestrator instead of making its brain depend on the gateway.

Inspected: [apps/android/app/src/main/java/ai/openclaw/app/node/InvokeDispatcher.kt](https://github.com/openclaw/openclaw/blob/eae12351406e4cfee0b800e5f2006ae24e0e1578/apps/android/app/src/main/java/ai/openclaw/app/node/InvokeDispatcher.kt), [apps/android/app/src/thirdParty/java/ai/openclaw/app/node/MobileUiHandler.kt](https://github.com/openclaw/openclaw/blob/eae12351406e4cfee0b800e5f2006ae24e0e1578/apps/android/app/src/thirdParty/java/ai/openclaw/app/node/MobileUiHandler.kt), [apps/android/app/src/play/java/ai/openclaw/app/node/MobileUiHandler.kt](https://github.com/openclaw/openclaw/blob/eae12351406e4cfee0b800e5f2006ae24e0e1578/apps/android/app/src/play/java/ai/openclaw/app/node/MobileUiHandler.kt), [docs/tools/mcp.md](https://github.com/openclaw/openclaw/blob/eae12351406e4cfee0b800e5f2006ae24e0e1578/docs/tools/mcp.md), [docs/tools/web-fetch.md](https://github.com/openclaw/openclaw/blob/eae12351406e4cfee0b800e5f2006ae24e0e1578/docs/tools/web-fetch.md).

### khoj-ai/khoj

Revision `ae229ca894c0` · AGPL-3.0 · **reference / optional service**

Separates search, page reading, MCP and browser observation/actions. Its Playwright environment is a Python/browser runtime, not a mobile WebView component. Useful evidence/citation and research patterns; no server dependency for Cue.

Inspected: [src/khoj/processor/tools/online_search.py](https://github.com/khoj-ai/khoj/blob/ae229ca894c0b80ad84664afcfdde523b5e87057/src/khoj/processor/tools/online_search.py), [src/khoj/processor/tools/mcp.py](https://github.com/khoj-ai/khoj/blob/ae229ca894c0b80ad84664afcfdde523b5e87057/src/khoj/processor/tools/mcp.py), [src/khoj/processor/operator/operator_environment_browser.py](https://github.com/khoj-ai/khoj/blob/ae229ca894c0b80ad84664afcfdde523b5e87057/src/khoj/processor/operator/operator_environment_browser.py).

### letta-ai/letta

Revision `8816d3a85168` · Apache-2.0 · **redirect reference**

Current default branch points to letta-code; retired V1 API source is on archive. Followed the active implementation rather than assuming the older Python memory server is still the main product.

Inspected: [README.md](https://github.com/letta-ai/letta/blob/8816d3a8516893bfdc0fa14bfb1235226012e295/README.md), [LICENSE](https://github.com/letta-ai/letta/blob/8816d3a8516893bfdc0fa14bfb1235226012e295/LICENSE).

### OpenHands/OpenHands

Revision `c11557132fcf` · MIT · **reference**

Current repository is Agent Canvas, a control center and adapter to agent servers. Followed software-agent-sdk for execution internals; the desktop/control-center product is not an Android assistant base.

Inspected: [README.md](https://github.com/OpenHands/OpenHands/blob/c11557132fcff450f942cd1560315555c8c4c022/README.md), [src/api/agent-server-adapter.ts](https://github.com/OpenHands/OpenHands/blob/c11557132fcff450f942cd1560315555c8c4c022/src/api/agent-server-adapter.ts).

### openinterpreter/openinterpreter

Revision `80d8f5538545` · Apache-2.0 · **reference**

The old repository URL redirects here. Current implementation is a Rust coding-agent fork with tool registration, cancellation and execution policy. Learn those boundaries; do not assume the old Python computer-control API or expose an unrestricted terminal on the phone.

Inspected: [README.md](https://github.com/openinterpreter/openinterpreter/blob/80d8f55385452457a625dbabc032f3003b9545b4/README.md), [codex-rs/core/src/tools/router.rs](https://github.com/openinterpreter/openinterpreter/blob/80d8f55385452457a625dbabc032f3003b9545b4/codex-rs/core/src/tools/router.rs), [codex-rs/core/src/tools/orchestrator.rs](https://github.com/openinterpreter/openinterpreter/blob/80d8f55385452457a625dbabc032f3003b9545b4/codex-rs/core/src/tools/orchestrator.rs).

### the-open-agent/openagent

Revision `f2338321f65a` · Apache-2.0 · **reference**

Go MCP clients implement discovery/calls, tokens and bounded requests. Its broader backend has many integrations and storage dependencies. Useful transport examples rather than a Kotlin mobile runtime.

Inspected: [mcp/client.go](https://github.com/the-open-agent/openagent/blob/f2338321f65ad7dd65d0749ad0bd95efa7c2c881/mcp/client.go), [mcp/tools.go](https://github.com/the-open-agent/openagent/blob/f2338321f65ad7dd65d0749ad0bd95efa7c2c881/mcp/tools.go), [go.mod](https://github.com/the-open-agent/openagent/blob/f2338321f65ad7dd65d0749ad0bd95efa7c2c881/go.mod).

### pipecat-ai/pipecat

Revision `379f35df81ab` · BSD-2-Clause · **voice reference**

Typed stream frames and explicit cleanup/cancellation connect voice and tool work. Adapt event and cancellation semantics to the existing quiet Android pipeline; replacing it with a Python media server is not needed for tools.

Inspected: [src/pipecat/services/llm_service.py](https://github.com/pipecat-ai/pipecat/blob/379f35df81ab809312ff4f57ff2f1296e2eee333/src/pipecat/services/llm_service.py), [src/pipecat/frames/frames.py](https://github.com/pipecat-ai/pipecat/blob/379f35df81ab809312ff4f57ff2f1296e2eee333/src/pipecat/frames/frames.py).

### livekit/agents

Revision `3e5156882414` · Apache-2.0 · **voice reference**

Tool results distinguish output from whether speech should follow; session and interruption state are explicit. Useful for spoken tool progress and interruption. WebRTC/room infrastructure is optional, not required for a single-phone assistant.

Inspected: [livekit-agents/livekit/agents/voice/agent_session.py](https://github.com/livekit/agents/blob/3e5156882414f4f5919437c1834a4261bdd9ce2a/livekit-agents/livekit/agents/voice/agent_session.py), [livekit-agents/livekit/agents/llm/tool_context.py](https://github.com/livekit/agents/blob/3e5156882414f4f5919437c1834a4261bdd9ce2a/livekit-agents/livekit/agents/llm/tool_context.py).

### elyase/awesome-personal-ai-assistants

Revision `e4e8ac390deb` · CC0-1.0 · **directory**

A discovery list rather than an implementation. Cloned this linked directory; did not recursively clone every project mentioned in it. Feature and memory claims in the list are not phone benchmarks.

Inspected: [readme.md](https://github.com/elyase/awesome-personal-ai-assistants/blob/e4e8ac390debfae0f28ba7be6083cdce42f0a073/readme.md), [LICENSE](https://github.com/elyase/awesome-personal-ai-assistants/blob/e4e8ac390debfae0f28ba7be6083cdce42f0a073/LICENSE).

### searxng/searxng

Revision `219b63b207c9` · AGPL-3.0-or-later · **optional search backend**

Search adapters have timeouts, denied-access and suspension handling. JSON output requires instance configuration. Good optional endpoint or adapter inspiration; public instances and upstream engines cannot promise unlimited availability.

Inspected: [searx/search/processors/online.py](https://github.com/searxng/searxng/blob/219b63b207c98b85471de5abab773e76432f1961/searx/search/processors/online.py), [searx/exceptions.py](https://github.com/searxng/searxng/blob/219b63b207c98b85471de5abab773e76432f1961/searx/exceptions.py), [docs/dev/search_api.rst](https://github.com/searxng/searxng/blob/219b63b207c98b85471de5abab773e76432f1961/docs/dev/search_api.rst).

### browser-use/browser-use

Revision `48d9fe008bb9` · MIT · **browser reference**

Current agent bounds runs by steps/time, owns browser sessions and distinguishes local/cloud runtimes. Adapt observation/action/result budgeting to a phone browser. Do not run its desktop Python/browser/REPL stack as a required phone service.

Inspected: [browser_use/agent/service.py](https://github.com/browser-use/browser-use/blob/48d9fe008bb98a8959279b495518652dfb934d6b/browser_use/agent/service.py), [browser_use/browser/browser.py](https://github.com/browser-use/browser-use/blob/48d9fe008bb98a8959279b495518652dfb934d6b/browser_use/browser/browser.py).

### microsoft/playwright-mcp

Revision `78eca85361ef` · Apache-2.0 · **tool-contract reference**

Package documents structured snapshots and tools; implementation lives in the Playwright monorepo. Followed that source. A mobile-emulation option is not an Android-native runtime and does not remove the need for a browser host.

Inspected: [README.md](https://github.com/microsoft/playwright-mcp/blob/78eca85361efb545a4c0f59b6140aef0721c5412/README.md), [src/README.md](https://github.com/microsoft/playwright-mcp/blob/78eca85361efb545a4c0f59b6140aef0721c5412/src/README.md), [package.json](https://github.com/microsoft/playwright-mcp/blob/78eca85361efb545a4c0f59b6140aef0721c5412/package.json).

### droidrun/mobilerun

Revision `e34004270a9d` · MIT · **mobile-agent reference**

Renamed from DroidRun. Manager/executor and UI-state providers are separate; Android drivers support both ADB and authenticated Portal HTTP. Useful control-loop patterns; current Python harness is not a drop-in Cue brain.

Inspected: [mobilerun/agent/droid/droid_agent.py](https://github.com/droidrun/mobilerun/blob/e34004270a9d7b68824b94a3a5ba6698cefea69d/mobilerun/agent/droid/droid_agent.py), [mobilerun/agent/utils/android_device.py](https://github.com/droidrun/mobilerun/blob/e34004270a9d7b68824b94a3a5ba6698cefea69d/mobilerun/agent/utils/android_device.py).

### droidrun/mobilerun-portal

Revision `49d92110ed9f` · AGPL-3.0-or-later · **Android reference**

Renamed Android Portal supplies accessibility traversal/action and screenshot machinery. Its license differs from the MIT Python harness. Study bounded traversal and dispatch; permission-auto-accept helpers are not selected for Cue.

Inspected: [app/src/main/java/com/mobilerun/portal/core/AccessibilityTraversalGuard.kt](https://github.com/droidrun/mobilerun-portal/blob/49d92110ed9faeaad4c8f65b681c4a3012b1de25/app/src/main/java/com/mobilerun/portal/core/AccessibilityTraversalGuard.kt), [app/src/main/java/com/mobilerun/portal/service/ActionDispatcher.kt](https://github.com/droidrun/mobilerun-portal/blob/49d92110ed9faeaad4c8f65b681c4a3012b1de25/app/src/main/java/com/mobilerun/portal/service/ActionDispatcher.kt).

### modelcontextprotocol/kotlin-sdk

Revision `af5d784d11bb` · Apache-2.0 / MIT transition (see LICENSE) · **library candidate**

Official client exposes listTools/callTool with typed results and request options. Candidate for selected remote or phone-local MCP tools. Pin SDK/transport versions and verify Kotlin/Android engine compatibility and APK cost before adoption.

Inspected: [kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt](https://github.com/modelcontextprotocol/kotlin-sdk/blob/af5d784d11bb8eaa09a825429f95ee0f44e630f4/kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt), [kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/StreamableHttpClientTransport.kt](https://github.com/modelcontextprotocol/kotlin-sdk/blob/af5d784d11bb8eaa09a825429f95ee0f44e630f4/kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/StreamableHttpClientTransport.kt), [LICENSE](https://github.com/modelcontextprotocol/kotlin-sdk/blob/af5d784d11bb8eaa09a825429f95ee0f44e630f4/LICENSE).

### modelcontextprotocol/python-sdk

Revision `77001c96f950` · MIT · **specialized-server candidate**

Current 2.x source renamed FastMCP to MCPServer; the old module raises an import error. Suitable for optional specialized workers, with version-pinned examples. No assumption that all Python/server dependencies already run on Android.

Inspected: [src/mcp/client/session.py](https://github.com/modelcontextprotocol/python-sdk/blob/77001c96f9507be0f4d009b83210a384a5e08bda/src/mcp/client/session.py), [src/mcp/server/fastmcp.py](https://github.com/modelcontextprotocol/python-sdk/blob/77001c96f9507be0f4d009b83210a384a5e08bda/src/mcp/server/fastmcp.py), [examples/stories/tools/server.py](https://github.com/modelcontextprotocol/python-sdk/blob/77001c96f9507be0f4d009b83210a384a5e08bda/examples/stories/tools/server.py).

### deedy5/ddgs

Revision `8e7495914110` · MIT · **search-adapter reference**

Small provider adapters show request/extraction contracts and explicit rate-limit errors. Dependencies include native primp and lxml: inspect/port useful patterns to Android HTTPS clients instead of assuming pip installation on Termux will work.

Inspected: [ddgs/engines/duckduckgo.py](https://github.com/deedy5/ddgs/blob/8e74959141106702645020dcb853c6453b5e5195/ddgs/engines/duckduckgo.py), [ddgs/exceptions.py](https://github.com/deedy5/ddgs/blob/8e74959141106702645020dcb853c6453b5e5195/ddgs/exceptions.py), [pyproject.toml](https://github.com/deedy5/ddgs/blob/8e74959141106702645020dcb853c6453b5e5195/pyproject.toml).

### cheaper-inference/OmniRoute

Revision `82f60e5ef66c` · MIT · **optional on-phone delegation gateway candidate**

Documents an Android/Termux server and implements quota/error/fallback routing. Separate it from the private local-inference route. Node/native dependency footprint and actual API quotas remain unmeasured. Request logging supports prompt/response/stream capture, including stream chunks when logging is disabled unless separately disabled; privacy needs explicit configuration and verification.

Inspected: [docs/guides/TERMUX_GUIDE.md](https://github.com/cheaper-inference/OmniRoute/blob/82f60e5ef66cb8f4fd92929a44c3679ef844f84a/docs/guides/TERMUX_GUIDE.md), [open-sse/services/combo/targetExhaustion.ts](https://github.com/cheaper-inference/OmniRoute/blob/82f60e5ef66cb8f4fd92929a44c3679ef844f84a/open-sse/services/combo/targetExhaustion.ts), [open-sse/utils/requestLogger.ts](https://github.com/cheaper-inference/OmniRoute/blob/82f60e5ef66cb8f4fd92929a44c3679ef844f84a/open-sse/utils/requestLogger.ts), [package.json](https://github.com/cheaper-inference/OmniRoute/blob/82f60e5ef66cb8f4fd92929a44c3679ef844f84a/package.json).

### letta-ai/letta-code

Revision `5c04f79eba45` · Apache-2.0 · **memory reference**

Active implementation offers explicit memory budgets, protected memory files and per-agent ownership. Adapt those ideas to app-private, user-visible memory. Do not enable unbounded background reflection, syncing personal memory or a second agent server by default.

Inspected: [src/memory-constraints.ts](https://github.com/letta-ai/letta-code/blob/5c04f79eba452fd80a85c08f673dbdca9d3c7e62/src/memory-constraints.ts), [src/permissions/cross-agent-guard.ts](https://github.com/letta-ai/letta-code/blob/5c04f79eba452fd80a85c08f673dbdca9d3c7e62/src/permissions/cross-agent-guard.ts), [README.md](https://github.com/letta-ai/letta-code/blob/5c04f79eba452fd80a85c08f673dbdca9d3c7e62/README.md).

### microsoft/playwright

Revision `0ad21f1d34e4` · Apache-2.0 · **browser implementation reference**

Actual MCP/backend code binds actions to observed element references and captures a fresh snapshot after input. ARIA snapshot implementation filters nodes and maps elements to refs. Adapt the design to an owned WebView; it does not provide unrestricted access to the user's Chrome tabs.

Inspected: [packages/playwright-core/src/tools/backend/snapshot.ts](https://github.com/microsoft/playwright/blob/0ad21f1d34e40a51649a97e14da787172d68d073/packages/playwright-core/src/tools/backend/snapshot.ts), [packages/playwright-core/src/tools/backend/mouse.ts](https://github.com/microsoft/playwright/blob/0ad21f1d34e40a51649a97e14da787172d68d073/packages/playwright-core/src/tools/backend/mouse.ts), [packages/injected/src/ariaSnapshot.ts](https://github.com/microsoft/playwright/blob/0ad21f1d34e40a51649a97e14da787172d68d073/packages/injected/src/ariaSnapshot.ts).

### OpenHands/software-agent-sdk

Revision `4c2cae33c3d5` · MIT · **agent-loop reference**

Actual agent step, schema validation and model-routing code are here. Adapt structured actions/results and execution state. Cue should retain the phone model as owner and delegate a bounded subtask, rather than switching the entire private conversation to a remote model.

Inspected: [openhands-sdk/openhands/sdk/agent/agent.py](https://github.com/OpenHands/software-agent-sdk/blob/4c2cae33c3d5cfdb3ef5d028ce736cc0843bf72a/openhands-sdk/openhands/sdk/agent/agent.py), [openhands-sdk/openhands/sdk/tool/tool.py](https://github.com/OpenHands/software-agent-sdk/blob/4c2cae33c3d5cfdb3ef5d028ce736cc0843bf72a/openhands-sdk/openhands/sdk/tool/tool.py), [openhands-sdk/openhands/sdk/tool/builtins/classify_and_switch_llm.py](https://github.com/OpenHands/software-agent-sdk/blob/4c2cae33c3d5cfdb3ef5d028ce736cc0843bf72a/openhands-sdk/openhands/sdk/tool/builtins/classify_and_switch_llm.py).
