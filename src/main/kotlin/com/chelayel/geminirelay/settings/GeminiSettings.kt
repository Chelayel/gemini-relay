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

        // How long one turn may run: tool rounds before the loop gives up, and
        // how many history entries are kept in the prompt before trimming.
        var maxToolRounds: Int = 300
        var historyWindow: Int = 240

        // Web access for the agent. The provider is "" (none), "brave",
        // "tavily" or "google"; the key itself lives in PasswordSafe.
        var webEnabled: Boolean = true
        var searchProvider: String = ""
        var searchCx: String = ""

        // Thinking depth, both spellings, each sent only when set. Unset by
        // default: see [thinkingLevel].
        var thinkingLevel: String = ""
        // -1 = leave the field off the request entirely. 0 is meaningful (it
        // disables thinking on the 2.5 family), so it cannot double as "unset".
        var thinkingBudget: Int = -1

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

    /** The saved prompt, except that an untouched *older* default is upgraded to
     *  the current one. The default is persisted verbatim, so without this every
     *  existing install would keep the prompt it was first written with and never
     *  see an improvement — while a prompt the user actually edited is left alone. */
    var systemPrompt: String
        get() = state.systemPrompt.let { if (it.isBlank() || it.trim() in SUPERSEDED_PROMPTS) DEFAULT_SYSTEM_PROMPT else it }
        set(value) { state.systemPrompt = value }

    var commandTimeoutSeconds: Int
        get() = state.commandTimeoutSeconds.coerceIn(300, 3600)
        set(value) { state.commandTimeoutSeconds = value.coerceIn(300, 3600) }

    var loadProjectMemory: Boolean
        get() = state.loadProjectMemory
        set(value) { state.loadProjectMemory = value }

    /**
     * How many tool rounds one turn may take. The loop used to have no cap at
     * all, which is fine until a job stalls and silently burns the quota; a
     * migration-sized task legitimately needs hundreds, so the number is here
     * rather than hard-coded.
     */
    var maxToolRounds: Int
        get() = state.maxToolRounds.coerceIn(1, 5000)
        set(value) { state.maxToolRounds = value.coerceIn(1, 5000) }

    /** History entries kept in the prompt before trimming (see `AgentSession.trimmed`). */
    var historyWindow: Int
        get() = state.historyWindow.coerceIn(20, 5000)
        set(value) { state.historyWindow = value.coerceIn(20, 5000) }

    /** Whether the agent may reach the network beyond the model endpoint. */
    var webEnabled: Boolean
        get() = state.webEnabled
        set(value) { state.webEnabled = value }

    var searchProvider: String
        get() = state.searchProvider.trim()
        set(value) { state.searchProvider = value.trim() }

    /** Google Programmable Search engine id, for the `google` provider only. */
    var searchCx: String
        get() = state.searchCx.trim()
        set(value) { state.searchCx = value.trim() }

    /**
     * Gemini 3.x takes `thinkingLevel` ("low"/"medium"/"high"); the 2.5 family
     * takes a `thinkingBudget` in tokens. Both are blank/0 by default and then
     * absent from the request: an Apigee gateway validates the body against its
     * own schema and rejects a field it does not know, so an opt-in cannot break
     * a setup that already works.
     */
    var thinkingLevel: String
        get() = state.thinkingLevel.trim()
        set(value) { state.thinkingLevel = value.trim() }

    /** Thinking tokens for the 2.5 family; -1 means "don't send the field". */
    var thinkingBudget: Int
        get() = state.thinkingBudget.coerceIn(-1, 32_768)
        set(value) { state.thinkingBudget = value.coerceIn(-1, 32_768) }

    val personas: MutableList<Persona> get() = state.personas
    val mcpServers: MutableList<McpServerConfig> get() = state.mcpServers

    // ---- secrets (PasswordSafe) ----------------------------------------------

    var geminiApiKey: String
        get() = readSecret(KEY_API)
        set(value) = writeSecret(KEY_API, value)

    var apigeeClientSecret: String
        get() = readSecret(KEY_APIGEE_SECRET)
        set(value) = writeSecret(KEY_APIGEE_SECRET, value)

    /** The search provider's API key — a credential, so PasswordSafe, not XML. */
    var searchApiKey: String
        get() = readSecret(KEY_SEARCH)
        set(value) = writeSecret(KEY_SEARCH, value)

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
        private const val KEY_SEARCH = "search-api-key"

        /**
         * The two paragraphs after the basics are there for a specific failure:
         * asked to move a project onto a framework release newer than its
         * training data, the model answered from memory and invented plausible
         * artifact ids and property names, then hand-edited file after file
         * until the turn ended with the build broken. Both halves of that are
         * addressed here — check the world before asserting a version-specific
         * fact, and drive a large migration from a written plan and the
         * project's own tooling instead of from memory.
         */
        val DEFAULT_SYSTEM_PROMPT = """
            You are Gemini Relay, an agentic coding assistant working inside the user's project.
            You have tools to read, write, and search files, to run shell commands in the project directory,
            and to search and read the web.
            When given a task:
            1. Use searchFiles and readFile to understand the relevant code before changing anything.
            2. Make focused edits with writeFile; never claim a change you did not apply through a tool.
            3. Use runCommand to build, test, and verify your work, and fix failures before finishing.

            Your training data has a cutoff and the ecosystem has moved since. Before you state or rely on anything
            version-specific — an artifact or module id, a configuration property, a class or method that may have been
            renamed, moved or removed, the current release of a library, what a major version changed — check it with a
            tool first. Use webSearch and then fetchUrl to read the project's own release notes or migration guide, and
            mavenSearch to confirm every dependency coordinate before you write it into a build file. Do this before you
            answer, not after the user corrects you, and say which page you took a fact from. If webSearch is not
            available, fetchUrl still is: go straight to the documentation URL you know.

            For a large mechanical change across many files (a framework or language-version upgrade, a package rename,
            an API sweep), do not start editing file by file. First find out whether the ecosystem already automates it —
            OpenRewrite recipes, a vendor migration tool, a codemod, an IDE inspection — and prefer running that over
            hand-editing, then fix what it leaves behind. Write the plan to a file in the repo, keep it updated as you go,
            and work in batches that each end with a build or test run, so progress survives even if the turn is cut short.

            Be concise in your replies and autonomous in your work.
        """.trimIndent()

        /** Defaults shipped by earlier releases. A saved prompt matching one of
         *  these was never edited by the user, so it is replaced on read rather
         *  than pinning that install to a prompt that predates the web tools. */
        private val SUPERSEDED_PROMPTS = setOf(
            """
            You are Gemini Relay, an agentic coding assistant working inside the user's project.
            You have tools to read, write, and search files and to run shell commands in the project directory.
            When given a task:
            1. Use searchFiles and readFile to understand the relevant code before changing anything.
            2. Make focused edits with writeFile; never claim a change you did not apply through a tool.
            3. Use runCommand to build, test, and verify your work, and fix failures before finishing.
            Be concise in your replies and autonomous in your work.
            """.trimIndent(),
        )

        fun getInstance(): GeminiSettings =
            ApplicationManager.getApplication().getService(GeminiSettings::class.java)
    }
}
