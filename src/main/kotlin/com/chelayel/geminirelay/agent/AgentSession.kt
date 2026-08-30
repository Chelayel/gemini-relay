package com.chelayel.geminirelay.agent

import com.chelayel.geminirelay.api.Content
import com.chelayel.geminirelay.api.GeminiClient
import com.chelayel.geminirelay.api.Part
import com.chelayel.geminirelay.api.Usage
import com.chelayel.geminirelay.mcp.McpManager
import com.chelayel.geminirelay.settings.GeminiSettings
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

/** How aggressively Agent mode runs tools without asking (mirrors Claude Relay). */
enum class PermissionMode(val label: String) {
    ASK("Ask"),
    ACCEPT_EDITS("Accept edits"),
    BYPASS("Bypass all");

    override fun toString() = label
}

/** The user's answer to a permission prompt. */
enum class PermissionDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

/**
 * Holds one conversation's running history and drives the agentic loop:
 * stream a model turn, run any tool calls it requests (built-in or MCP), feed
 * the results back, and repeat until the model answers with plain text (or the
 * iteration cap is hit). All listener callbacks are delivered on the Swing EDT.
 */
class AgentSession(
    private val workingDir: String,
    private val settings: GeminiSettings,
    private val mcp: McpManager,
) {
    private val log = logger<AgentSession>()
    private val history = mutableListOf<Content>()
    // Tools the user approved "for this chat" (skip future prompts).
    private val approvedTools = mutableSetOf<String>()
    // MCP startup failures already shown, so each is reported once per chat.
    private val reportedMcpErrors = mutableSetOf<String>()

    @Volatile private var client: GeminiClient? = null
    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    interface Listener {
        fun onAssistantText(text: String) {}
        fun onToolUse(name: String, summary: String) {}
        fun onToolResult(text: String, isError: Boolean) {}
        fun onUsage(usage: Usage) {}
        fun onError(message: String) {}
        fun onComplete() {}
    }

    fun reset() {
        cancel()
        history.clear()
        approvedTools.clear()
        reportedMcpErrors.clear()
    }

    fun cancel() {
        cancelled = true
        client?.cancel()
    }

    /**
     * Send one user message (text and/or inline images) and run the loop.
     * [systemPrompt] is the fully-composed instruction for this turn (persona
     * and/or project memory already folded in by the caller).
     */
    fun send(
        userParts: List<Part>,
        askMode: Boolean,
        systemPrompt: String,
        permission: PermissionMode,
        confirm: (String, String) -> PermissionDecision,
        listener: Listener,
    ) {
        cancelled = false
        running = true
        history.add(Content("user", userParts.ifEmpty { listOf(Part.Text("Please continue.")) }))
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { loop(askMode, systemPrompt, permission, confirm, listener) }
                .onFailure { e ->
                    log.warn("Turn failed", e)
                    edt { listener.onError(describe(e)) }
                }
            running = false
            edt { listener.onComplete() }
        }
    }

    /** Always produce something useful, even for exceptions with no message. */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val msg = root.message?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() }
        return msg ?: "${root::class.simpleName ?: "Error"} (no message)"
    }

    private fun loop(
        askMode: Boolean,
        systemPrompt: String,
        permission: PermissionMode,
        confirm: (String, String) -> PermissionDecision,
        listener: Listener,
    ) {
        val tools = Tools(workingDir, settings.commandTimeoutSeconds, Web.from(settings))
        // Ask mode is strictly read-only: no tools at all.
        val declarations = if (askMode) emptyList() else tools.declarations() + mcp.declarations()
        // A server that failed to start used to contribute no tools and no
        // explanation, which looks from the transcript like the model ignoring
        // a tool it was given. Say so — but once per chat, not once per turn:
        // the manager caches its tool list, so the same failure is still on
        // record every time round and repeating it would bury the transcript.
        if (!askMode) {
            mcp.lastErrors().filter { reportedMcpErrors.add(it) }
                .forEach { e -> edt { listener.onError("MCP server unavailable — $e") } }
        }

        var rounds = 0
        while (!cancelled) {
            if (++rounds > settings.maxToolRounds) {
                edt { listener.onError(roundCapMessage()) }
                return
            }
            val c = GeminiClient(settings)
            client = c

            val turn = c.streamTurn(trimmed(), systemPrompt, declarations) { delta ->
                edt { listener.onAssistantText(delta) }
            }
            if (cancelled) break

            turn.usage?.let { u -> edt { listener.onUsage(u) } }
            val calls = turn.functionCalls
            if (turn.parts.isNotEmpty()) history.add(Content("model", turn.parts))
            if (calls.isEmpty()) {
                if (turn.text.isBlank()) edt { listener.onError(emptyTurnMessage(turn.finishReason)) }
                return
            }

            val responses = mutableListOf<Part>()
            for (call in calls) {
                if (cancelled) break
                val builtin = tools.handles(call.name)
                val summary = if (builtin) tools.summarize(call.name, call.args) else mcp.summarize(call.name)
                edt { listener.onToolUse(call.name, summary) }

                if (needsConfirm(permission, call.name, builtin) && call.name !in approvedTools) {
                    when (confirm(call.name, summary)) {
                        PermissionDecision.DENY -> {
                            edt { listener.onToolResult("Denied by user.", true) }
                            responses.add(Part.FunctionResponse(call.name, JsonObject().apply {
                                addProperty("error", "The user denied permission to run this tool.")
                            }))
                            continue
                        }
                        PermissionDecision.ALLOW_ALWAYS -> approvedTools.add(call.name)
                        PermissionDecision.ALLOW_ONCE -> {}
                    }
                }

                val response = if (builtin) tools.execute(call.name, call.args) else mcp.execute(call.name, call.args)
                val isError = response.has("error")
                val shown = (response.get("error") ?: response.get("result"))?.asString.orEmpty()
                edt { listener.onToolResult(shown, isError) }
                responses.add(Part.FunctionResponse(call.name, response))
            }
            if (cancelled) break
            // Gemini expects function results in a user-role turn.
            history.add(Content("user", responses))
        }
    }

    /**
     * Whether a tool call must be confirmed under [mode]. Read-only built-ins
     * (readFile/listFiles/searchFiles, and the web tools, which only read)
     * never prompt; writeFile prompts only in ASK; runCommand and any MCP tool
     * prompt unless BYPASS — an MCP tool is somebody else's code and is not
     * confined to the project directory.
     */
    private fun needsConfirm(mode: PermissionMode, name: String, builtin: Boolean): Boolean {
        if (mode == PermissionMode.BYPASS) return false
        return when {
            !builtin -> true
            name == "writeFile" -> mode == PermissionMode.ASK
            name == "runCommand" -> true
            else -> false
        }
    }

    /**
     * Bound the prompt size by keeping the most recent turns — but not with a
     * plain `takeLast`, which was wrong in two ways. It could start the window
     * on `functionResponse` parts whose `functionCall` had just been cut away,
     * and Gemini rejects that outright (HTTP 400); and it dropped the very
     * first message — the task itself — so a job that ran long enough to trim
     * forgot what it had been asked to do. So the window is advanced past any
     * orphaned results, and the opening request is always kept, with a note in
     * place of the middle.
     */
    private fun trimmed(): List<Content> {
        val window = settings.historyWindow
        if (history.size <= window) return history.toList()
        var start = history.size - window
        while (start < history.size && history[start].parts.any { it is Part.FunctionResponse }) start++
        if (start >= history.size) return history.takeLast(1)
        val tail = history.subList(start, history.size).toList()
        if (start == 0) return tail
        return listOf(history.first(), Content("user", listOf(Part.Text(TRIM_NOTICE)))) + tail
    }

    private fun roundCapMessage(): String =
        "Stopped after ${settings.maxToolRounds} tool rounds without a final answer. The work so far is applied; " +
            "send “continue” to carry on, or raise the limit in Settings → Tools → Gemini Relay."

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    /** Explain otherwise-silent empty model turns (commonly safety-blocked). */
    private fun emptyTurnMessage(finishReason: String?): String = when (finishReason?.uppercase()) {
        "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" ->
            "Gemini blocked this response due to safety filters. Please rephrase and try again."
        "MAX_TOKENS" ->
            "Gemini stopped before producing a visible answer (max output tokens reached). Try a shorter request."
        null, "" ->
            "Gemini returned an empty response. Please try again."
        else ->
            "Gemini returned an empty response (finish reason: $finishReason). Please try again."
    }

    companion object {
        private const val TRIM_NOTICE =
            "[Earlier steps of this conversation were dropped to stay within the context window. " +
                "The original request is above; continue from the most recent tool results below.]"
    }
}
