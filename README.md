# Gemini Relay for JetBrains

A native chat GUI for **Google Gemini** inside JetBrains IDEs (IntelliJ IDEA,
PyCharm, WebStorm, GoLand, …) — a side-panel coding assistant, instead of just
the terminal or a browser tab.

> Independent project, not affiliated with Google. Part of the **"Relay"**
> family of IDE companions, alongside **Claude Relay** — same look and feel.

## Features

- **Chat tool window** ("Gemini Relay", docked right) with streaming responses
  and live tool activity
- **Agent mode** — Gemini reads, writes, and searches files and runs commands in
  your project via function calling
- **Ask mode** — read-only answers about your code (no tools)
- **Editor-aware** — auto-attaches the current editor selection as context, so
  "explain/refactor this" just works
- **Attach files & images** — pick a file, or paste a screenshot straight into
  the prompt (Gemini is multimodal; images go inline)
- **Three connection modes**, switchable in settings:
  - **Gemini API** — the public Generative Language API with an API key
  - **Vertex AI** — a standard Vertex AI project (OAuth token via `gcloud`)
  - **Vertex via Apigee** — Vertex behind a custom Apigee OAuth gateway
    (project, location, gateway host, token URL, client id/secret)
- **Configurable everything** — every connection parameter lives in
  `Settings → Tools → Gemini Relay`; the API key and Apigee client secret are
  stored in the IDE password safe, never in plaintext config
- Runs in the context of the **currently open project**

## Requirements

- A JetBrains IDE, build 242 (2024.2) or newer
- **JDK 21** (to build)
- Credentials for one of the connection modes:
  - *Gemini API*: an API key from Google AI Studio
  - *Vertex AI*: the `gcloud` CLI installed and authenticated (`gcloud auth login`)
  - *Vertex via Apigee*: your gateway host plus OAuth2 client-credentials

## Build & run

```bash
# Launch a sandbox IDE with the plugin loaded:
./gradlew runIde

# Or build an installable zip:
./gradlew buildPlugin
# -> build/distributions/gemini-relay-0.1.0.zip
```

## Install into your IDE

`Settings → Plugins → ⚙ → Install Plugin from Disk…` → pick the zip from
`build/distributions/`, then restart. Open the **Gemini Relay** tool window on
the right, then click ⚙ (or `Settings → Tools → Gemini Relay`) to configure the
connection.

## How it works

Each turn sends the conversation to `…:streamGenerateContent?alt=sse` for the
selected backend and renders the streamed text as it arrives. In Agent mode the
model's `functionCall`s are executed locally (read / write / list / search /
run, confined to the project directory) and the results are fed back until the
model produces a final answer.

## Notes / limitations (v0.1)

- Markdown rendering is intentionally lightweight (code blocks, inline code, bold).
- Agent tools run with your permissions — review what you ask it to do.
- Standard Vertex obtains its access token by shelling out to `gcloud`.
