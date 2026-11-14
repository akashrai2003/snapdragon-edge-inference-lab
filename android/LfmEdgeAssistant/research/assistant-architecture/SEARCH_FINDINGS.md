# Web search findings and Cue implementation

Reviewed 14 November 2026. Repository findings refer to the pinned clones, not executed upstream installations or a guarantee about future provider terms.

## Can search be perfect, unlimited and free?

No implementation can guarantee all three. The useful target is **no mandatory paid search API, no Cue subscription/query allowance, and no always-on personal computer**. Internet access is still required. Coverage, freshness, relevance, service availability and correctness are separate concerns; a retrieved page or citation is not proof that every generated claim is true.

Fetching ordinary search pages avoids a compulsory API bill but remains dependent on the search service. A headless browser does not remove upstream limits, CAPTCHAs, markup changes or running costs. Public SearXNG instances also depend on operators and engines; [its API documentation](https://docs.searxng.org/dev/search_api.html) explicitly says many instances disable machine-readable formats. Cue does not rotate identities, bypass challenges or silently subscribe to paid services. [Brave offers monthly free API credits](https://brave.com/search/api/), which is another optional provider route; it still requires a key and has a finite allowance. It is not configured or billed by this implementation.

## Google and Apple

Google's own search-grounding documentation describes a model generating queries, obtaining Google Search results, synthesizing an answer and returning source annotations. This is infrastructure supplied by Google, not a promise of an unrestricted free search API for other apps. [Google Search grounding](https://aistudio.google.com/docs/google-search).

Apple says Applebot crawls data used by Siri, Spotlight and Safari, including grounding broad-world-knowledge answers with links. Apple also has partner integrations; the historical Google search agreement explicitly covered Siri and Spotlight. Those facts do not establish that every current Siri query uses Google, or reveal Apple's complete current ranking/routing stack. [Applebot](https://support.apple.com/en-au/119829), [historical agreement, trial exhibit JX0033](https://www.justice.gov/atr/media/1325602/dl?inline=).

Apple's June 2026 Siri AI announcement describes up-to-date web answers alongside on-device and Private Cloud Compute processing and personal-context integration. This is not an architecture we can reproduce merely by swapping an LLM. Being free at the point of use does not mean its indexing, serving or model inference has no operating cost. [Apple's announcement](https://www.apple.com/newsroom/2026/06/apple-introduces-siri-ai-a-profoundly-more-capable-and-personal-assistant/).

## How the cloned repositories search

| Project | Actual inspected code | Implication for Cue |
| --- | --- | --- |
| [Khoj](https://github.com/khoj-ai/khoj/blob/ae229ca894c0b80ad84664afcfdde523b5e87057/src/khoj/processor/tools/online_search.py) | Configured Serper → Exa → Firecrawl → Google Custom Search → SearXNG, stopping after a successful provider. Reads source pages separately. | API credentials or a configured SearXNG endpoint; no bundled global index. |
| [OpenClaw](https://github.com/openclaw/openclaw/blob/eae12351406e4cfee0b800e5f2006ae24e0e1578/src/agents/tools/web-search.ts) | A provider runtime resolves the configured search implementation and credentials, returning normalized external evidence. | Availability and charges depend on the selected provider. |
| [Browser Use](https://github.com/browser-use/browser-use/blob/48d9fe008bb98a8959279b495518652dfb934d6b/browser_use/search.py) | Its built-in search posts to the Browser Use gateway at `/api/v4/search` with a Bearer API key and handles budget exhaustion. Browser navigation is a separate capability. | Built-in search is a keyed service, not unlimited free browser scraping. |
| [DDGS](https://github.com/deedy5/ddgs/blob/8e74959141106702645020dcb853c6453b5e5195/ddgs/engines/duckduckgo.py) | Engine adapters fetch and parse upstream search pages, including DuckDuckGo HTML. | No mandatory paid key for some adapters; upstream throttles, challenges and markup changes remain. |
| [SearXNG](https://github.com/searxng/searxng/blob/219b63b207c98b85471de5abab773e76432f1961/searx/search/processors/online.py) | Metasearch queries upstream engines and handles timeout, access-denied and suspension conditions. | Hosting and upstream access are still required. Many public instances disable JSON. |

Pipecat, LiveKit Agents and MCP supply orchestration/transport mechanisms; they do not by themselves supply a free global web index. The useful reuse is the separation between tool schemas, execution, evidence and final answers. No upstream assistant source code is copied into Cue's new search implementation.

## First delivered slice in Cue 3.4.0

- A per-question **Search web** control, or an explicit voice/text phrase such as “search the web for…”. Ordinary chat does not silently become a public search.
- The existing on-phone model emits a native structured `web_search` call. Kotlin validates the query, retrieves results, reads **all four returned pages concurrently**, then gives the combined evidence to the same local model for its streamed answer. `web_read` similarly batches up to four URLs explicitly supplied by the user. No remote LLM, API key, always-on PC, Python search daemon or browser runtime is required.
- DuckDuckGo HTML is the first provider. Jsoup extracts up to four real results. The reader strips navigation/scripts and selects question-relevant passages, initially bounded to 1,800 characters per page. Each page retains a stable source ID and its actual success/failure status. Failed reads retain only the original search snippet and an explicit read error. Search/read adapters are replaceable.
- The normal task uses two model turns: request retrieval, then answer. Up to four page requests run together with a shared 20-second reading deadline; one slow/blocked page does not discard completed evidence. Stop cancels all child sockets. Exact rendered-template/tokenizer checks fit combined excerpts within the existing phone context, shortening excerpts across sources rather than silently dropping all but one page. There is no extra model call per page. Stable schemas preserve the reusable prefix, including the final answer (`tool_choice=none`); malformed/unknown calls and extra attempts remain bounded.
- Earlier chat, attached images, private memory, account cookies and credentials are excluded from public requests. The current public search query and requests for selected websites leave the phone. Network retrieval uses the actual connection's DNS resolver to reject non-public addresses and checks every redirect; arbitrary model-generated URLs are not fetched unless returned by search or provided by the user.
- Source IDs, titles, URLs, retrieval timestamps and page-read state are retained with the answer. If the model omits inline references, Cue appends the actual reviewed-source inventory rather than inventing claim-level citations. Unknown numeric citation IDs are removed from the final displayed answer. Retrieved page bodies/snippets stay ephemeral. Cards distinguish search evidence from pages actually read; sources open in the normal browser. Numbered citation markers are omitted from speech.
- Rate limits/challenges, unavailable pages and empty results produce visible failures and **Open search** browser handoff. There are no automatic challenge retries. No arbitrary JavaScript, app actions, browser clicking, MCP servers or cloud routing are included in this slice.

## Qualification and limits

A real native model dry run produced streaming structured tool calls from the existing installed GGUF/template/parser. Host-only checks returned parseable DuckDuckGo HTML/Lite results. Bing RSS returned HTTP 200 with unrelated results for the same benign technical query, so it was rejected rather than treated as a reliable fallback. Lite is not enabled as an automatic challenge workaround.

The first full physical-phone test searched for official Android documentation, read an official page, generated a cited answer with the local model and rendered source cards. Its 43.4-second completion was a single diagnostic event, not a benchmark or an acceptable instant-answer claim. Follow-up measurements and final build/device checks are recorded in [Cue validation](../../design/CUE_VALIDATION.md).

The current parser is best effort: JavaScript-only pages, PDFs, logged-in sites and some search result layouts are unsupported. Relevance/claim verification, automatic freshness filters, multilingual search, a broader quality suite, persistent bounded caching and provider backoff, optional configured SearXNG and an owned interactive WebView remain follow-up work. The 24-case architecture evaluation file remains **proposed**, not a completed task-success benchmark. A successful benign test does not establish general agent accuracy, long-session performance or battery use.
