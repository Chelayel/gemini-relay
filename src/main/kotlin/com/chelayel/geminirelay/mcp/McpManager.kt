package com.chelayel.geminirelay.mcp

import com.chelayel.geminirelay.api.FunctionDecl
import com.chelayel.geminirelay.settings.GeminiSettings
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger

/**
 * Owns the configured MCP servers for one tool window: connects lazily, merges
 * their tools into the function-calling loop, and routes calls back to the
 * right server. Gemini-facing tool names are sanitized and mapped back to the
 * original (server, tool) pair. A server that fails to start is logged and
 * simply contributes no tools.
 */
class McpManager(private val settings: GeminiSettings) : Disposable {

    private val log = logger<McpManager>()

    private class Entry(val client: McpClient, val originalName: String, val decl: FunctionDecl)

    private var entries: List<Entry>? = null
    private val clients = mutableListOf<McpClient>()
    private val errors = mutableListOf<String>()

    /** Connects (once) and returns the merged tool declarations. */
    @Synchronized
    fun declarations(): List<FunctionDecl> {
        entries?.let { return it.map { e -> e.decl } }
        val built = mutableListOf<Entry>()
        errors.clear()
        for (config in settings.mcpServers.filter { it.enabled && it.command.isNotBlank() }) {
            val client = McpClient(config)
            clients.add(client)
            runCatching {
                client.listTools().forEach { tool ->
                    val fnName = McpClient.functionName(config.name.ifBlank { "mcp" }, tool.name)
                    val decl = FunctionDecl(fnName, tool.description, McpClient.sanitizeSchema(tool.inputSchema))
                    built.add(Entry(client, tool.name, decl))
                }
            }.onFailure {
                errors.add("${config.name}: ${it.message}")
                log.warn("MCP server '${config.name}' failed to list tools", it)
            }
        }
        entries = built
        return built.map { it.decl }
    }

    fun handles(functionName: String): Boolean = entries?.any { it.decl.name == functionName } == true

    fun summarize(functionName: String): String =
        entries?.firstOrNull { it.decl.name == functionName }?.originalName ?: functionName

    fun execute(functionName: String, args: JsonObject): JsonObject {
        val entry = entries?.firstOrNull { it.decl.name == functionName }
            ?: return JsonObject().apply { addProperty("error", "Unknown MCP tool: $functionName") }
        return runCatching {
            JsonObject().apply { addProperty("result", entry.client.callTool(entry.originalName, args)) }
        }.getOrElse {
            JsonObject().apply { addProperty("error", it.message ?: "MCP tool '$functionName' failed.") }
        }
    }

    /** Any connection/list errors from the last [declarations] build. */
    fun lastErrors(): List<String> = errors.toList()

    override fun dispose() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        entries = null
    }
}
