package com.charbel.geminirelay.agent

import com.charbel.geminirelay.api.Content
import com.charbel.geminirelay.api.GeminiClient
import com.charbel.geminirelay.api.Part
import com.charbel.geminirelay.api.Usage
import com.charbel.geminirelay.settings.GeminiSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

/**
 * Holds one conversation's running history and drives the agentic loop:
 * stream a model turn, run any tool calls it requests, feed the results back,
 * and repeat until the model answers with plain text (or the iteration cap is
 * hit). All listener callbacks are delivered on the Swing EDT.
 *
 * One [AgentSession] lives for the life of a chat; [reset] starts a new one.
 */
class AgentSession(
    private val workingDir: String,
    private val settings: GeminiSettings,
) {
    private val log = logger<AgentSession>()
    private val history = mutableListOf<Content>()

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
    }

    fun cancel() {
        cancelled = true
        client?.cancel()
    }

    /** Send one user message (text and/or inline images) and run the loop. */
    fun send(userParts: List<Part>, askMode: Boolean, listener: Listener) {
        cancelled = false
        running = true
        history.add(Content("user", userParts.ifEmpty { listOf(Part.Text("")) }))
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { loop(askMode, listener) }
                .onFailure { edt { listener.onError(it.message ?: "Unexpected error.") } }
            running = false
            edt { listener.onComplete() }
        }
    }

    private fun loop(askMode: Boolean, listener: Listener) {
        val tools = Tools(workingDir, settings.commandTimeoutSeconds)
        val declarations = if (askMode) emptyList() else tools.declarations()
        val systemPrompt = if (askMode) ASK_SYSTEM_PROMPT else settings.systemPrompt

        var iteration = 0
        while (!cancelled && iteration < settings.maxIterations) {
            iteration++
            val c = GeminiClient(settings)
            client = c

            val turn = c.streamTurn(trimmed(), systemPrompt, declarations) { delta ->
                edt { listener.onAssistantText(delta) }
            }
            if (cancelled) break

            turn.usage?.let { u -> edt { listener.onUsage(u) } }
            history.add(Content("model", turn.parts))

            val calls = turn.functionCalls
            if (calls.isEmpty()) return

            val responses = mutableListOf<Part>()
            for (call in calls) {
                if (cancelled) break
                edt { listener.onToolUse(call.name, tools.summarize(call.name, call.args)) }
                val response = tools.execute(call.name, call.args)
                val isError = response.has("error")
                val shown = (response.get("error") ?: response.get("result"))?.asString.orEmpty()
                edt { listener.onToolResult(shown, isError) }
                responses.add(Part.FunctionResponse(call.name, response))
            }
            if (cancelled) break
            // Gemini expects function results in a user-role turn.
            history.add(Content("user", responses))
        }

        if (!cancelled && iteration >= settings.maxIterations) {
            edt { listener.onError("Reached the ${settings.maxIterations}-step limit without finishing. Send another message to continue.") }
        }
    }

    /** Bound the prompt size by keeping only the most recent turns. */
    private fun trimmed(): List<Content> =
        if (history.size <= MEMORY_WINDOW) history.toList() else history.takeLast(MEMORY_WINDOW)

    private fun edt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)

    companion object {
        private const val MEMORY_WINDOW = 100

        private val ASK_SYSTEM_PROMPT = """
            You are Gemini Relay in read-only "ask" mode: answer questions about the user's code.
            You cannot modify files or run commands — explain, review, and suggest code in your reply instead.
            If the user's message includes attached context, use it to ground your answer.
        """.trimIndent()
    }
}
