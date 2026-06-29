package com.charbel.geminirelay.settings

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

        // For standard Vertex: how to obtain an access token. When blank we
        // shell out to `gcloud auth print-access-token`.
        var gcloudPath: String = ""

        // Agent behaviour.
        var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        var maxIterations: Int = 15
        var commandTimeoutSeconds: Int = 60
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    // ---- typed accessors -----------------------------------------------------

    var connectionMode: ConnectionMode
        get() = ConnectionMode.from(state.connectionMode)
        set(value) { state.connectionMode = value.name }

    var model: String
        get() = state.model.ifBlank { DEFAULT_MODEL }
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

    var gcloudPath: String
        get() = state.gcloudPath.trim()
        set(value) { state.gcloudPath = value.trim() }

    var systemPrompt: String
        get() = state.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
        set(value) { state.systemPrompt = value }

    var maxIterations: Int
        get() = state.maxIterations.coerceIn(1, 100)
        set(value) { state.maxIterations = value.coerceIn(1, 100) }

    var commandTimeoutSeconds: Int
        get() = state.commandTimeoutSeconds.coerceIn(5, 600)
        set(value) { state.commandTimeoutSeconds = value.coerceIn(5, 600) }

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
                apigeeTokenUrl.isNotBlank() && apigeeClientId.isNotBlank() && apigeeClientSecret.isNotBlank()
    }

    private fun readSecret(key: String): String =
        PasswordSafe.instance.getPassword(credentialAttributes(key)).orEmpty()

    private fun writeSecret(key: String, value: String) =
        PasswordSafe.instance.setPassword(credentialAttributes(key), value.takeIf { it.isNotBlank() })

    private fun credentialAttributes(key: String) =
        CredentialAttributes(generateServiceName("Gemini Relay", key))

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-pro"

        /** Suggested models for the picker; the model field is free-form too. */
        val MODEL_CHOICES = listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
        )

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
