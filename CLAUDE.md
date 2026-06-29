# Gemini Relay — project guide

A native chat GUI for Google Gemini inside JetBrains IDEs. The plugin adds a
"Gemini Relay" tool window that calls the Gemini / Vertex REST API directly
(no CLI) and renders a streaming transcript. Companion to Claude Relay
(`../claude-code-gui`), which it mirrors in look and feel.

## Build & run

- JDK 21, Gradle 9, IntelliJ Platform Gradle Plugin 2.x.
- `./gradlew compileKotlin` — fast compile check.
- `./gradlew runIde` — sandbox IDE with the plugin loaded.
- `./gradlew buildPlugin` — installable zip in `build/distributions/`.
- Bump `version` in `build.gradle.kts` before packaging.

## Architecture

- `settings/GeminiSettings` — app-level config (PersistentStateComponent);
  secrets (API key, Apigee client secret) live in PasswordSafe. `ConnectionMode`
  enum = Gemini API / Vertex AI / Vertex via Apigee.
- `settings/GeminiSettingsConfigurable` — Settings → Tools → Gemini Relay form;
  enables only the fields the selected mode needs.
- `api/AuthProvider` — resolves auth per mode: API key (query param), gcloud
  access token (standard Vertex), or Apigee OAuth client-credentials (cached).
- `api/GeminiClient` — one streaming `streamGenerateContent` call; parses the
  SSE stream, emits text deltas, returns the assembled `ModelTurn`.
- `api/GeminiTypes` — the slim `contents`/`tools` model shared by all backends.
- `agent/Tools` — function declarations + executor (read/write/list/search/run),
  confined to the project dir.
- `agent/AgentSession` — conversation history + the stream→tool→repeat loop.
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

## Status

Feature-complete v0.1: three connection modes, settings, streaming chat, agent
loop, and the composer context system (selection auto-attach, file/image
attach, paste). Mirrors the finalized Claude Relay; part of the "Relay" family.
Claude-CLI-specific features (slash commands, CLAUDE.md/agents bar) are
intentionally omitted as they don't apply to a Gemini backend.
