# Gemini Relay

**Google Gemini, native in your JetBrains IDE.**

Gemini Relay is a plugin for IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider and
the rest of the JetBrains family. It adds a **Gemini Relay** tool window — a
side-panel chat that talks directly to the Gemini API, to Vertex AI, or to a
Vertex deployment behind an Apigee gateway. No CLI, no browser tab: the model
lives next to your code and stays aware of the file and lines you have open.

> Independent and unofficial — not affiliated with Google.

## Features

- **Streaming chat** with live tool activity — edits, commands, and file reads as they happen.
- **Agent & Ask modes** — let Gemini read, edit, and run commands, or keep it strictly read-only.
- **Editor-aware** — auto-attaches your current selection, so "explain this" / "refactor this" just works.
- **Attach files & images** — pick a file, or paste a screenshot straight into the prompt (Gemini is natively multimodal).
- **Three connection modes** — a personal Gemini API key, a standard Vertex AI project, or Vertex via Apigee — switchable in Settings.
- **MCP tool servers** — connect Model Context Protocol servers and expose their tools to the agent.
- **Project memory** — reads `GEMINI.md` / `AGENTS.md` / `CLAUDE.md` from your project for context.
- **Secrets stay safe** — API keys and client secrets are stored in the IDE's password safe, never in plain config.

## Requirements

- A JetBrains IDE on build `242` (2024.2) or newer.
- One of:
  - a **Gemini API key** (from Google AI Studio), or
  - a **Google Cloud project** with Vertex AI enabled and `gcloud` authenticated, or
  - access to a **Vertex-via-Apigee** gateway (token URL + client credentials).

## Install

**From source:**

```sh
./gradlew buildPlugin
```

This produces an installable zip in `build/distributions/`. In your IDE, go to
**Settings → Plugins → ⚙ → Install Plugin from Disk…** and select the zip.

To try it in a throwaway sandbox IDE without installing:

```sh
./gradlew runIde
```

## Configure

Open **Settings → Tools → Gemini Relay** and pick a connection mode. Only the
fields the selected mode needs are enabled.

- **Gemini API** — paste your API key and choose a model.
- **Vertex AI** — set your GCP project and location; auth uses your local `gcloud` access token.
- **Vertex via Apigee** — set the token URL, client ID/secret, and the list of accessible models; auth uses OAuth client-credentials (cached).

## Build & develop

- JDK 21, Gradle 9, IntelliJ Platform Gradle Plugin 2.x.
- `./gradlew compileKotlin` — fast compile check.
- `./gradlew runIde` — sandbox IDE with the plugin loaded.
- `./gradlew buildPlugin` — installable zip in `build/distributions/`.
- Bump `version` in `build.gradle.kts` before packaging.

See [`CLAUDE.md`](CLAUDE.md) for an architecture overview and contributor conventions.

## License

[MIT](LICENSE) © Charbel Helayel
