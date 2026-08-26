package com.chelayel.geminirelay.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * The three ways Gemini Relay can reach a Gemini model. Mirrors the connection
 * styles seen across Google's surfaces — the public Generative Language API, a
 * standard Vertex AI project, and a Vertex deployment fronted by a custom
 * Apigee gateway (the enterprise pattern).
 */
enum class ConnectionMode(val label: String, val blurb: String) {
    GEMINI_API("Gemini API", "Public Generative Language API with an API key"),
    VERTEX("Vertex AI", "Standard Vertex AI project (OAuth access token)"),
    VERTEX_APIGEE("Vertex via Apigee", "Vertex behind a custom Apigee OAuth gateway");

    companion object {
        fun from(name: String?): ConnectionMode =
            entries.firstOrNull { it.name == name } ?: GEMINI_API
    }
}

/**
 * A reusable persona ("agent"): a named system-prompt preset the user can run a
 * turn as. The Gemini analogue of a Gem / a Claude sub-agent. Public mutable
 * fields with a no-arg constructor so the IDE's XML serializer can persist it.
 */
class Persona() {
    var name: String = ""
    var prompt: String = ""

    constructor(name: String, prompt: String) : this() {
        this.name = name
        this.prompt = prompt
    }
}

/** Launch config for one MCP (Model Context Protocol) tool server over stdio. */
class McpServerConfig() {
    var name: String = ""
    var command: String = ""
    var args: String = ""
    var env: String = ""
    var enabled: Boolean = true

    constructor(name: String, command: String, args: String, env: String, enabled: Boolean) : this() {
        this.name = name
        this.command = command
        this.args = args
        this.env = env
        this.enabled = enabled
    }
}

/**
 * Persisted, non-secret configuration for the active connection. Secrets
 * (the API key and the Apigee client secret) live in the IDE [PasswordSafe]
 * instead of this XML so they never land in plaintext settings files.
 *
 * Application-level (one configuration shared across projects), matching how a
 * developer typically points the plugin at a single backend.
 */
@State(
    name = "GeminiRelaySettings",
    storages = [Storage("geminiRelay.xml")],
)
class GeminiSettings : PersistentStateComponent<GeminiSettings.State> {

    class State {
        var connectionMode: String = ConnectionMode.GEMINI_API.name
        var model: String = DEFAULT_MODEL

        // Vertex / Apigee shared project coordinates.
        var vertexProjectId: String = ""
        var vertexLocation: String = "us-central1"

        // Custom host for the Apigee gateway, e.g. "my-gw.example.com".
        // Empty for standard Vertex (the regional aiplatform host is derived).
        var vertexApiEndpoint: String = ""

        // Apigee OAuth2 client-credentials token endpoint + client id.
        var apigeeTokenUrl: String = ""
        var apigeeClientId: String = ""

        // In Apigee mode the gateway exposes a fixed set of agents (= model
        // identifiers) the user may call. One per line; drives the model picker.
        var apigeeAgents: String = ""

        // For standard Vertex: how to obtain an access token. When blank we
        // shell out to `gcloud auth print-access-token`.
        var gcloudPath: String = ""

        // Agent behaviour.
        var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        var commandTimeoutSeconds: Int = 300

        // Auto-load a project memory file (GEMINI.md / AGENTS.md / CLAUDE.md).
        var loadProjectMemory: Boolean = true

        // Reusable personas and MCP tool servers.
        var personas: MutableList<Persona> = mutableListOf()
        var mcpServers: MutableList<McpServerConfig> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    // ---- typed accessors -----------------------------------------------------

    var connectionMode: ConnectionMode
        get() = ConnectionMode.from(state.connectionMode)
        set(value) { state.connectionMode = value.name }

    var model: String
        // Transparently migrate a saved model Google has since retired (requests to
        // it 404) to the current default, so users aren't stuck on a dead selection,
        // and translate ids that exist under a different name in the active mode.
        get() = canonicalModel(state.model.ifBlank { DEFAULT_MODEL }, connectionMode)
        set(value) { state.model = value.trim() }

    var vertexProjectId: String
        get() = state.vertexProjectId.trim()
        set(value) { state.vertexProjectId = value.trim() }

    var vertexLocation: String
        get() = state.vertexLocation.trim().ifBlank { "us-central1" }
        set(value) { state.vertexLocation = value.trim() }

    var vertexApiEndpoint: String
        get() = state.vertexApiEndpoint.trim()
        set(value) { state.vertexApiEndpoint = value.trim() }

    var apigeeTokenUrl: String
        get() = state.apigeeTokenUrl.trim()
        set(value) { state.apigeeTokenUrl = value.trim() }

    var apigeeClientId: String
        get() = state.apigeeClientId.trim()
        set(value) { state.apigeeClientId = value.trim() }

    var apigeeAgents: String
        get() = state.apigeeAgents
        set(value) { state.apigeeAgents = value }

    /** The accessible agents (model ids) parsed from [apigeeAgents]. */
    fun apigeeAgentList(): List<String> =
        state.apigeeAgents.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    var gcloudPath: String
        get() = state.gcloudPath.trim()
        set(value) { state.gcloudPath = value.trim() }

    var systemPrompt: String
        get() = state.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
        set(value) { state.systemPrompt = value }

    var commandTimeoutSeconds: Int
        get() = state.commandTimeoutSeconds.coerceIn(300, 3600)
        set(value) { state.commandTimeoutSeconds = value.coerceIn(300, 3600) }

    var loadProjectMemory: Boolean
        get() = state.loadProjectMemory
        set(value) { state.loadProjectMemory = value }

    val personas: MutableList<Persona> get() = state.personas
    val mcpServers: MutableList<McpServerConfig> get() = state.mcpServers

    // ---- secrets (PasswordSafe) ----------------------------------------------

    var geminiApiKey: String
        get() = readSecret(KEY_API)
        set(value) = writeSecret(KEY_API, value)

    var apigeeClientSecret: String
        get() = readSecret(KEY_APIGEE_SECRET)
        set(value) = writeSecret(KEY_APIGEE_SECRET, value)

    /** True when the active mode has the minimum credentials to attempt a call. */
    fun isConfigured(): Boolean = when (connectionMode) {
        ConnectionMode.GEMINI_API -> geminiApiKey.isNotBlank()
        ConnectionMode.VERTEX -> vertexProjectId.isNotBlank()
        ConnectionMode.VERTEX_APIGEE ->
            vertexProjectId.isNotBlank() && vertexApiEndpoint.isNotBlank() &&
                apigeeTokenUrl.isNotBlank() && apigeeClientId.isNotBlank() && apigeeClientSecret.isNotBlank() &&
                apigeeAgentList().isNotEmpty()
    }

    private fun readSecret(key: String): String =
        PasswordSafe.instance.getPassword(credentialAttributes(key)).orEmpty()

    private fun writeSecret(key: String, value: String) =
        PasswordSafe.instance.setPassword(credentialAttributes(key), value.takeIf { it.isNotBlank() })

    /** Same service name and null user name as every previous release, so secrets
     *  already in PasswordSafe keep resolving. Built in Java — see
     *  [GeminiCredentialAttributes] for why Kotlin cannot reach that constructor. */
    private fun credentialAttributes(key: String): CredentialAttributes =
        GeminiCredentialAttributes.forService(generateServiceName("Gemini Relay", key))

    companion object {
        /** Google's current workhorse for coding and agentic work, and the one id
         *  that is GA under the same name in every mode. The 2.5 family it replaces
         *  is two generations behind and a poor default for an agent loop. */
        const val DEFAULT_MODEL = "gemini-3.7-flash"

        /** Gemini API id → Vertex id, for the models the two name differently.
         *  A preview on one surface is often GA on the other, and sending the
         *  wrong spelling 404s, so the id is translated for the active mode
         *  rather than left to fail after the user switches modes. */
        private val VERTEX_IDS = mapOf(
            "gemini-3.1-pro-preview" to "gemini-3.1-pro",
            "gemini-3-flash-preview" to "gemini-3-flash",
        )

        private val GEMINI_API_IDS = VERTEX_IDS.entries.associate { (api, vertex) -> vertex to api }

        /** Suggested models for the picker, best first; the model field is free-form
         *  too, and in Gemini API mode the live ListModels response supersedes this
         *  list. Kept to current, non-retired models — Google shuts old ones down
         *  (e.g. gemini-2.0-flash, gemini-3-pro-preview) and requests then 404. */
        private val GEMINI_API_MODELS = listOf(
            "gemini-3.7-flash",
            "gemini-3.1-pro-preview",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
        )

        /** The same shortlist as Vertex names it: models the Gemini API still
         *  publishes as previews are GA there, under an id without the suffix. */
        private val VERTEX_MODELS = GEMINI_API_MODELS.map { VERTEX_IDS[it] ?: it }

        /** Suggested models for [mode]. Apigee exposes only what the gateway lists,
         *  so callers use the configured agent list there and fall back to these. */
        fun modelChoices(mode: ConnectionMode): List<String> =
            if (mode == ConnectionMode.GEMINI_API) GEMINI_API_MODELS else VERTEX_MODELS

        /** Models Google has retired — a saved selection of one of these 404s, so
         *  it's migrated to [DEFAULT_MODEL] on read. Extend as Google shuts more down. */
        private val RETIRED_MODELS = setOf(
            "gemini-3-pro-preview",
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash-lite-001",
            "gemini-1.5-pro",
            "gemini-1.5-flash",
            "gemini-1.0-pro",
            "gemini-pro",
        )

        /** The id [model] goes by in [mode], with retired models replaced outright. */
        fun canonicalModel(model: String, mode: ConnectionMode): String {
            val trimmed = model.trim()
            if (trimmed.isEmpty() || trimmed in RETIRED_MODELS) return canonicalModel(DEFAULT_MODEL, mode)
            // Only the two Google-hosted surfaces are known to rename models; an
            // Apigee gateway publishes its own ids, so leave those untouched.
            return when (mode) {
                ConnectionMode.GEMINI_API -> GEMINI_API_IDS[trimmed] ?: trimmed
                ConnectionMode.VERTEX -> VERTEX_IDS[trimmed] ?: trimmed
                ConnectionMode.VERTEX_APIGEE -> trimmed
            }
        }

        private const val KEY_API = "gemini-api-key"
        private const val KEY_APIGEE_SECRET = "apigee-client-secret"

        val DEFAULT_SYSTEM_PROMPT = """
            You are Gemini Relay, an agentic coding assistant working inside the user's project.
            You have tools to read, write, and search files and to run shell commands in the project directory.
            When given a task:
            1. Use searchFiles and readFile to understand the relevant code before changing anything.
            2. Make focused edits with writeFile; never claim a change you did not apply through a tool.
            3. Use runCommand to build, test, and verify your work, and fix failures before finishing.
            Be concise in your replies and autonomous in your work.
        """.trimIndent()

        fun getInstance(): GeminiSettings =
            ApplicationManager.getApplication().getService(GeminiSettings::class.java)
    }
}
