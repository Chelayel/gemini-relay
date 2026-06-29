# Gemini Relay — marketing kit

Brand: **Gemini Relay**, by **Chelayel**. Part of the **Relay** family of JetBrains IDE companions:

| Product | Wraps | Status |
| --- | --- | --- |
| **Claude Relay** | Claude Code CLI (Anthropic) | shipped |
| **Gemini Relay** | Gemini API / Vertex AI / Vertex-via-Apigee (Google) | this repo |

The shared **"Relay"** suffix + single publisher (**Chelayel**) signals one developer; the leading
vendor keyword (Claude / Gemini) keeps each discoverable in Marketplace search.

> Unlike Claude Relay (which drives a local CLI), Gemini Relay talks **directly to the Gemini /
> Vertex REST API** — no CLI to install. One plugin, three switchable connection modes.

---

## Taglines

- **Primary:** *Google Gemini, native in your JetBrains IDE.*
- Gemini and Vertex AI, relayed into a polished side-panel chat.
- Stop juggling tabs. Chat with Gemini where you code.

## Short description (≤ 80 chars, for cards)

> Native Gemini chat panel for JetBrains — editor-aware, with Agent & Ask modes.

## Long description (Marketplace / landing)

Gemini Relay brings Google Gemini into IntelliJ IDEA, PyCharm, WebStorm, GoLand, Rider and the rest
of the JetBrains family — a first-class tool window that talks directly to the Gemini API, to Vertex
AI, or to a Vertex deployment behind a custom Apigee gateway, in the context of your open project.
Streaming responses, live tool activity, editor-aware context, file & image attachments, and
**Agent vs Ask** modes.

## Feature bullets (lead with these)

1. **Streaming chat** with live tool activity (edits, commands, file reads).
2. **Agent & Ask modes** — full agentic edits via function calling, or strictly read-only Q&A.
3. **Editor-aware** — auto-attaches your current selection so "refactor this" just works.
4. **Attach files & paste images** straight into the prompt — Gemini is natively multimodal.
5. **Three connection modes** — Gemini API key, standard Vertex AI, or Vertex via Apigee — all
   configurable in Settings, with secrets kept in the IDE password safe.

---

## "Why I built this" — launch post draft

> **I built a native Gemini panel for JetBrains, because the model shouldn't live in a browser tab.**
>
> Google's Gemini and Vertex AI are great for coding — but inside JetBrains I was copy-pasting code
> into a browser, losing context, and stitching answers back by hand. The polished side-panel
> experience didn't exist for Gemini the way it does for some other assistants.
>
> So I made **Gemini Relay**: a tool window that talks straight to the Gemini / Vertex REST API and
> renders a proper chat transcript — streaming text, tool calls, the works. It stays aware of the file
> and the exact lines I have selected, so "explain this" or "refactor this" just works. I can paste a
> screenshot right into the prompt. It has an **Ask** mode for answers that never touch my files, and
> an **Agent** mode for when I want it to read, edit, and run commands.
>
> Best part: one plugin, three connection modes — a personal **Gemini API key**, a corporate **Vertex
> AI** project, or **Vertex behind an Apigee gateway** — switchable in settings.
>
> It's unofficial and independent (not affiliated with Google) — just a tool I wanted for my own
> workflow, now cleaned up to share. It's part of the small **Relay** family, alongside *Claude Relay*.
>
> Install from the JetBrains Marketplace, point it at your backend in Settings, and you're going.

Post targets: r/JetBrains, r/Bard (Gemini), r/GoogleAI, LinkedIn, X/Twitter, dev.to.

---

## Screenshot shot-list (capture from a real, populated session)

Capture at ~1.5–2× scale on a clean theme (Dark by default; one Light variant for the banner).
Recommended: a wide, **detached** tool window so the composer breathes.

1. **Hero / transcript** — an in-progress chat with: a user message, streaming assistant text, a tool
   call (e.g. a writeFile), and a collapsed tool result. *Caption: "A real transcript, in your IDE."*
2. **Settings / connection modes** — `Settings → Tools → Gemini Relay` with the Mode dropdown showing
   Gemini API / Vertex AI / Vertex via Apigee. *Caption: "Personal key or enterprise Vertex — your call."*
3. **Editor selection auto-attached** — code selected in the editor + the `✦ File.kt:40–55` chip in the
   composer. *Caption: "It knows what you're looking at."*
4. **Agent vs Ask** — the Mode chip dropdown open. *Caption: "Let it edit, or keep it read-only."*
5. **Image paste** — a pasted screenshot chip above the input. *Caption: "Paste a screenshot, ask about it."*
6. **(Banner)** — a clean hero composite for the Marketplace banner / social card.

Marketplace assets needed: plugin icon (already `/icons/gemini.svg`), 1+ screenshots, optional banner.

---

## Positioning notes

- Always label **unofficial / not affiliated with Google** (trademark hygiene; the name uses
  "Gemini" descriptively for an integration, which is standard for community plugins).
- Lead with the *editor-aware* + *Agent/Ask* + *multimodal* differentiators.
- Emphasize the **three connection modes** — this is the standout vs single-backend plugins; it
  serves hobbyists (API key) and enterprises (Vertex / Apigee) from one install.
- Cross-link the Relay family (Claude Relay) in the description.
