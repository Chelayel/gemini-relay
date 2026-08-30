# Gemini Relay — project guide

A native chat GUI for Google Gemini inside JetBrains IDEs. The plugin adds a
"Gemini Relay" tool window that calls the Gemini / Vertex REST API directly
(no CLI) and renders a streaming transcript. Companion to Claude Relay,
which it mirrors in look and feel.

## Build & run

- JDK 21, Gradle 9, IntelliJ Platform Gradle Plugin 2.x.
- `./gradlew compileKotlin` — fast compile check.
- `./gradlew runIde` — sandbox IDE with the plugin loaded.
- `./gradlew buildPlugin` — installable zip in `build/distributions/`.
- Bump `version` in `build.gradle.kts` before packaging.

## Architecture

- `settings/GeminiSettings` — app-level config (PersistentStateComponent);
  secrets (API key, Apigee client secret) live in PasswordSafe. `ConnectionMode`
  enum = Gemini API / Vertex AI / Vertex via Apigee. Also the model catalogue:
  the default (`gemini-3.7-flash`), the per-mode shortlist, and `canonicalModel`
  — Vertex and the Gemini API name the same model differently
  (`gemini-3.1-pro` / `gemini-3.1-pro-preview`), and Google retires ids outright,
  so a saved selection is translated or replaced on read rather than 404-ing.
  The default system prompt is persisted verbatim, so `systemPrompt` upgrades a
  saved prompt that matches a **superseded** default rather than pinning an
  existing install to the text it was first written with; an edited prompt is
  left alone. Thinking (`thinkingLevel` for 3.x, `thinkingBudget` for 2.5) is
  off unless set: an Apigee gateway validates the request body against its own
  schema and rejects a field it doesn't know, and the gateway's model ids give
  no way to tell which spelling that model takes.
- `settings/GeminiSettingsConfigurable` — Settings → Tools → Gemini Relay form;
  enables only the fields the selected mode needs, plus the Agent / Thinking /
  Web access sections. An unconfigured search provider *warns* rather than
  refusing to save (unlike the Apigee agent list): it degrades a feature, it
  doesn't break the connection.
- `api/AuthProvider` — resolves auth per mode: API key (query param), gcloud
  access token (standard Vertex), or Apigee OAuth client-credentials (cached).
- `api/GeminiClient` — one streaming `streamGenerateContent` call; parses the
  SSE stream, emits text deltas, returns the assembled `ModelTurn`.
- `api/GeminiTypes` — the slim `contents`/`tools` model shared by all backends.
- `agent/Tools` — built-in function declarations + executor (read/write/list/
  search/run), confined to the project dir. It also **composes `agent/Web`**, so
  the whole built-in tool surface is declared, routed and summarized in one place.
- `agent/Web` — `fetchUrl`, `webSearch` and `mavenSearch`. This exists because an
  agent that can only read the project answers every question about the world
  from training memory, which is how a Spring Boot 2→4 / Java 8→21 upgrade
  produced confident, invented artifact coordinates and property names. **There
  is no keyless search fallback on purpose**: DuckDuckGo's html and lite
  endpoints and Mojeek all answer an automated client with a challenge page, and
  a scraper that silently returns nothing is worse than no search — the model
  reads "no results" as "nothing exists" and goes back to guessing. So
  `webSearch` needs a provider (Brave / Tavily / Google CSE) and is **not
  advertised at all until one is configured**, while `fetchUrl` and `mavenSearch`
  need no key and always work. `mavenSearch` is there for the specific fact
  models get wrong most often on an upgrade — which group an artifact ships
  under, and which versions exist. Deliberately not Gemini's native
  `googleSearch` grounding: that is a wire feature, it can't always be combined
  with function declarations, and an Apigee gateway is free to strip it.
- `agent/AgentSession` — conversation history + the stream→tool→repeat loop;
  routes each call to built-in `Tools` or the `McpManager`. Gates mutating tools
  via `PermissionMode` (Ask/Accept edits/Bypass) with a blocking confirm callback
  ("Allow for this chat" remembered per tool). The loop is bounded by
  `maxToolRounds` (it previously had no cap at all) and the prompt by
  `historyWindow`. **`trimmed()` is not a `takeLast`**: that could start the
  window on `functionResponse` parts whose `functionCall` had just been cut
  away, which Gemini rejects outright, and it dropped the very first message —
  the task itself — so a job that ran long enough to trim forgot what it was
  doing. The window is advanced past orphaned results and the opening request is
  always kept, with a note in place of the middle.
- `agent/ProjectMemory` — finds & reads GEMINI.md / AGENTS.md / CLAUDE.md.
- `mcp/McpClient` — minimal JSON-RPC-over-stdio MCP client (handshake, tools/
  list, tools/call) with schema sanitization for Gemini. **Its stderr is drained
  on its own thread**: MCP servers log chattily, an undrained pipe fills and the
  server blocks writing to it, which looks from here like a server that
  handshook and then stopped answering. The tail is kept, because a server that
  dies on startup says why only on stderr.
- `mcp/McpManager` — owns configured servers, merges their tools, routes calls.
- `ui/GeminiChatPanel` — tool window: composer (mode/model chips, "+" context
  menu, send), attachable context (editor-selection auto-attach, files, and
  images via multimodal `inlineData`), footer (token usage), title actions
  (New Chat, Settings).
- `ui/ChatWebView` / `ui/TranscriptView` — transcript renderers behind the
  `ChatView` interface (JCEF when available, editor-pane fallback).

## Conventions

- All UI mutations on the Swing EDT; network/disk/tool work on pooled threads.
- Register disposables (JCEF browser) with a parent `Disposable`.
- Parse REST/SSE output defensively — never crash the tool window on bad input.
- Keep file/command tools confined to the project directory.
- Target build `242+`; avoid APIs newer than IntelliJ 2024.2 unless guarded.
- A new capability is off unless it can work. `webSearch` is not declared
  without a provider, and no thinking field is sent unless one was chosen —
  neither is an error, and neither can break a setup that already works.
- There is no test source set (no IntelliJ test fixture). `agent/Web` and the
  MCP client are line-for-line the same logic as AI Relay's, which does have
  tests for them — change them there first, then port.

## Status

v0.1: three connection modes, settings, streaming chat, agent loop, composer
context system (selection auto-attach, file/image attach, paste), **personas
(agents)**, **project memory** (GEMINI.md/AGENTS.md/CLAUDE.md), **MCP tool
servers**, and **web access** (search / fetch / Maven Central). Part of the
"Relay" family alongside Claude Relay. The `--agent` *flag* and `.claude/`-folder
scanning are not copied (CLI-specific); the equivalent capabilities are
implemented natively instead.
