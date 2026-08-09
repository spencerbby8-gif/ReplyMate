# Provider capability map (P-intelligence-6) — researched 2026-08-09
> **P-background-8 amendment (1.5.8):** every dialect that attaches a paid
> thinking/reasoning control now also enlarges the output-token cap by the
> thinking budget + reply headroom — providers count thinking INSIDE the output
> cap, and shipping a chat-tuned 220 cap with a 512+ thinking budget starved the
> answer into empty/MAX_TOKENS replies (background drafts silently died on
> 1.5.6–1.5.7). Anthropic and OpenAI-Responses already did this; Gemini
> (2.5 budgets & 3 levels), OpenRouter effort, DeepSeek, Kimi and Mistral now
> follow the same rule. The encyclopedia fallback additionally carries its own
> tight transport (2.5s/4s per call, 9s total) — a slow external lookup can
> never park a background draft; it degrades to the honesty line instead.


Every claim below traces to the provider's own official documentation (URLs listed).
Nothing here invents APIs, tools, or pricing; where a capability does not exist in
the dialect we speak, the transport says FALLBACK or NONE — never "assumed".

Dialects ReplyMate speaks: `generateContent` (Gemini REST), `/v1/chat/completions`
(OpenAI-compatible), `/v1/messages` (Anthropic). NEW in this phase:
`POST {base}/responses` (OpenAI Responses dialect, also spoken by xAI and offered
by OpenRouter).

## Live web search

| Provider | Transport | Wire shape (exact) | Decision | Cost (official) |
|---|---|---|---|---|
| GEMINI | native, in-call | `tools:[{"google_search":{}}]` on generateContent. Response: `candidates[0].groundingMetadata.webSearchQueries[]` (billable count), `groundingChunks[].web.title` (audit-safe source titles) | model decides per prompt; our gate attaches the tool only when needed | Gemini 3.x: 5,000 free queries/mo shared, then $14/1,000 queries; 2.5/2.0: 1,500 grounded prompts/day free then $35/1,000 grounded prompts; 2.5-Flash free tier 500 RPD. Grounding context NOT billed as input tokens. (ai.google.dev/gemini-api/docs/pricing, .../generate-content/google-search) |
| OPENAI | native, in-call | `POST {base}/responses` `tools:[{"type":"web_search"}]`, `tool_choice:"auto"`. Chat Completions search is limited to dedicated always-search models — NOT used (we need search to be optional) | `tool_choice:"auto"` = model searches only when useful | per provider pricing page (search-tool usage billed per call; tool calls appear in `output[]` as `web_search_call`) |
| OPENROUTER | native, in-call | `tools:[{"type":"openrouter:web_search","parameters":{"max_results":3,"max_total_results":3}}]` — works on **Chat Completions** (and Responses); deprecated plugins[{id:"web"}] / `:online` NOT used | engine `auto`: provider-native if supported else Exa | Native: passthrough pricing; Exa $0.005–0.007/req (≤10 results); Parallel $0.001–0.005; capped by max_total_results (openrouter.ai/docs/guides/features/plugins/web-search, blog agentic-web-tools) |
| ANTHROPIC | native, in-call | `tools:[{"type":"web_search_20250305","name":"web_search","max_uses":3}]` server tool on /v1/messages. `usage.server_tool_use.web_search_requests` = billed count; citations in `web_search_result_location` (cited_text ≤150 chars) | Claude decides; max_uses caps cost | $10/1,000 searches + result text billed as input tokens; failed searches not billed (docs.anthropic.com web-search-tool, pricing) |
| GROK (xAI) | native, in-call | `POST https://api.x.ai/v1/responses` `tools:[{"type":"web_search"}]`. Counts: `usage.server_side_tool_usage`. LEGACY `search_parameters` Live Search is **retired (410 Gone since 2026-01-12)** — NOT used | model decides | per xAI pricing (docs.x.ai/developers/tools/web-search) |
| KIMI (Moonshot) | native, in-call | `tools:[{"type":"builtin_function","function":{"name":"$web_search"}}]` + official echo loop: on `finish_reason=="tool_calls"`, append the assistant message and a `role:"tool"` reply containing the arguments **verbatim** (the platform executes the search). On K2.5/K2.6 the search tool is incompatible with thinking → thinking is disabled whenever search is attached. `kimi-k3` always reasons | loop capped (≤3 rounds) | per-request tool-call billing at the "fibers" step (platform.moonshot.ai/docs/guide/use-web-search) |
| DEEPSEEK | FALLBACK | no official search tool in chat/completions | retrieval before generation | n/a (api-docs.deepseek.com) |
| MISTRAL | FALLBACK | `web_search` built-ins officially work ONLY on the Conversations/Agents APIs — **not supported in /v1/chat/completions** (docs.mistral.ai/agents/tools) | retrieval before generation | n/a |
| OLLAMA (local) | FALLBACK | local daemon has no search; hosted `POST ollama.com/api/web_search` needs a separate Ollama cloud key — NOT wired (a second credential + privacy boundary; documented, user may add later) | retrieval before generation | n/a |
| OPENAI_COMPAT | FALLBACK | capability unknown for arbitrary endpoints | retrieval before generation | n/a |

**Fallback retrieval (used where transport = FALLBACK):** official, free,
key-less Wikimedia REST only — Wikipedia summary
(`https://en.wikipedia.org/api/rest_v1/page/summary/{title}`) and Wiktionary
(`https://en.wiktionary.org/w/api.php?action=query&prop=extracts…`) with a proper
`User-Agent`. Declared honestly as **encyclopedic, not tick-by-tick live**:
good for slang/terms/people/places/stable facts, weak for live scores/prices —
when it can't verify, the reply must not invent (anti-hallucination rule block).

## Reasoning / thinking

| Provider | Transport | Wire shape (exact) | Levels we map (NONE/LOW/HIGH) |
|---|---|---|---|
| GEMINI | native | `generationConfig.thinkingConfig`: `thinkingLevel` (Gemini 3 models; per-model support table) or `thinkingBudget` (2.5 series). Thoughts are internal; `includeThoughts` is **never** requested (no CoT leaves the provider). Response text = parts **without** `thought:true` (skipped defensively) | LOW: `thinkingLevel:"low"`/`thinkingBudget:1024`; HIGH: `"high"`/`4096`; NONE: omit (dynamic default) (ai.google.dev/.../thinking) |
| OPENAI | native | Chat Completions: `reasoning_effort:"low|medium|high"`; Responses: `reasoning:{"effort":"…"}` | mapped 1:1; `reasoning_tokens` in usage = metadata only |
| OPENROUTER | native | `reasoning:{"effort":"minimal|low|medium|high"}` (official reasoning parameter, model-routed) | mapped 1:1 |
| ANTHROPIC | native, model-family split | Claude 4.6+/5.x: `thinking:{"type":"adaptive"}` + `output_config:{"effort":"low|medium|high|xhigh|max"}` (GA, no beta header; `budget_tokens` 400s on 4.7+). Claude ≤4.5 (incl. Sonnet/Opus 4.5, Haiku 4.5): `thinking:{"type":"enabled","budget_tokens":N}` and temperature MUST be 1/omitted. Effort is rejected by Haiku 4.5 → family must match before sending. Response `content[]`: only `type=="text"` blocks become replies; `thinking` blocks are ignored (never shown/stored) | pattern-classified per model id |
| GROK (xAI) | native, always-on | grok-4.x are reasoning models (`usage.reasoning_tokens`); no documented per-request effort knob on grok-4 → **we send nothing** (never invent) | tokens read as METADATA only |
| KIMI | native | `thinking:{"type":"enabled"|"disabled"}` root field; kimi-k3 always reasons; thinking is disabled when the search tool is attached (official incompatibility) | LOW→disabled, HIGH→enabled (below k3) |
| DEEPSEEK | native | `thinking:{"type":"enabled"|"disabled"}` + `reasoning_effort:"low|high|max"`; enabled by default on v4; `reasoning_content` is **never** logged, stored, or replayed (official rule) | LOW: enabled+low; HIGH: enabled+high; NONE: disabled for speed on simple messages |
| MISTRAL | native, family split | `mistral-small-*`: root `reasoning_effort:"low|medium|high"`; `magistral-*`: native always-on, **param 422s — never sent**; other models: no reasoning support (param 422s) | pattern-classified per model id |
| OLLAMA / OPENAI_COMPAT | NONE | arbitrary local/compatible endpoints — nothing sent | — |

## Provider → base URL → model → capability binding (provider page)

- Selecting a provider auto-suggests its **official default base URL** (from
  `ProviderType.defaultBaseUrl`, docs-verified above) while keeping the field
  fully editable; a value the user typed by hand is NEVER overwritten (switching
  providers replaces the field **only** when it still holds another provider's
  default).
- Capability detection is pattern-based (`ModelClassifier`) against the
  user-picked/discovered model id, shown as "capabilities: search native /
  search fallback / no search · reasoning yes/no" — with runtime graceful
  degradation: a 400/404 complaining about the search/reasoning fields strips
  the capability ONCE, retries plainly, and the audit trail says so honestly.
- Provider/model family mismatch (e.g. a `claude-*` id on a Gemini endpoint)
  is detected from id patterns and surfaced as a blocking-style warning on the
  editor screen. Active provider/model/endpoint never switch silently.

## Anti-hallucination contract (all providers)

- The model is told: if it is unsure about something current/unfamiliar and no
  live-facts block is present, answer naturally WITHOUT asserting specifics.
- A failed lookup is recorded in the audit why-line ("live lookup failed —
  answered from what's known") and is never converted into a personality excuse
  inside the reply text.
- No chain-of-thought, system prompts, keys, other-contact memory, or internal
  rules are ever exposed in chat, drafts, notifications, or Prompt Audit
  (thinking blocks / `reasoning_content` are dropped at the parser; only the
  safe metadata — level, tool usage, timing, counts — is kept).
