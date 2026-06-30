package com.chelayel.geminirelay.api

import com.chelayel.geminirelay.settings.ConnectionMode
import com.chelayel.geminirelay.settings.GeminiSettings
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Resolves how a single outbound request authenticates to the active backend.
 * [QueryKey] is appended to the URL (Generative Language API); [Bearer] becomes
 * an `Authorization` header (both Vertex flavours).
 */
sealed interface Auth {
    data class QueryKey(val key: String) : Auth
    data class Bearer(val token: String) : Auth
}

/**
 * Produces the right credential for the current [ConnectionMode]:
 *  - Gemini API  → the user's API key as a query param.
 *  - Vertex      → a Google OAuth access token, obtained via `gcloud`.
 *  - Vertex+Apigee → an OAuth2 client-credentials token from the Apigee gateway.
 *
 * Bearer tokens are cached until shortly before they expire so we don't mint a
 * fresh one on every turn.
 */
object AuthProvider {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    // Cached bearer tokens keyed by a fingerprint of the credential source.
    private data class CachedToken(val token: String, val expiresAtMillis: Long)
    private val cache = HashMap<String, CachedToken>()

    @Synchronized
    fun resolve(settings: GeminiSettings): Auth = when (settings.connectionMode) {
        ConnectionMode.GEMINI_API -> {
            val key = settings.geminiApiKey
            require(key.isNotBlank()) { "No Gemini API key set — configure it in Settings → Tools → Gemini Relay." }
            Auth.QueryKey(key)
        }

        ConnectionMode.VERTEX -> Auth.Bearer(gcloudToken(settings))

        ConnectionMode.VERTEX_APIGEE -> Auth.Bearer(apigeeToken(settings))
    }

    /** Force the next [resolve] to mint a fresh token (used after a 401). */
    @Synchronized
    fun invalidate() = cache.clear()

    // ---- standard Vertex: gcloud access token --------------------------------

    private fun gcloudToken(settings: GeminiSettings): String {
        val cacheKey = "gcloud:${settings.gcloudPath}"
        cached(cacheKey)?.let { return it }

        val exe = settings.gcloudPath.ifBlank { "gcloud" }
        val cmd = GeneralCommandLine(exe, "auth", "print-access-token")
        val output = runCatching { ExecUtil.execAndGetOutput(cmd, 30_000) }.getOrNull()
            ?: throw IllegalStateException("Could not run `$exe auth print-access-token`. Is the gcloud CLI installed?")
        if (output.exitCode != 0) {
            throw IllegalStateException(
                "`gcloud auth print-access-token` failed: ${output.stderr.trim().ifBlank { "exit ${output.exitCode}" }}. " +
                    "Run `gcloud auth login` (or set an explicit gcloud path in settings).",
            )
        }
        val token = output.stdout.trim()
        check(token.isNotBlank()) { "gcloud returned an empty access token." }
        // Vertex tokens are ~60 min; refresh a little early.
        store(cacheKey, token, System.currentTimeMillis() + Duration.ofMinutes(50).toMillis())
        return token
    }

    // ---- Vertex via Apigee: OAuth2 client_credentials ------------------------

    private fun apigeeToken(settings: GeminiSettings): String {
        val tokenUrl = settings.apigeeTokenUrl
        val clientId = settings.apigeeClientId
        val clientSecret = settings.apigeeClientSecret
        require(tokenUrl.isNotBlank() && clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "Apigee token URL, client id, and client secret must all be set."
        }

        val cacheKey = "apigee:$tokenUrl:$clientId"
        cached(cacheKey)?.let { return it }

        val uri = URI.create(appendQuery(tokenUrl, "grant_type=client_credentials"))
        val basic = Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            throw IllegalStateException(
                "Apigee token request failed (HTTP ${response.statusCode()}). Check the token URL and client credentials.",
            )
        }

        val json = JsonParser.parseString(response.body()).asJsonObject
        val token = json.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw IllegalStateException("Apigee response had no access_token.")
        val expiresInSec = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 3600L
        store(cacheKey, token, System.currentTimeMillis() + (expiresInSec - 60).coerceAtLeast(30) * 1000)
        return token
    }

    // ---- token cache helpers -------------------------------------------------

    private fun cached(key: String): String? =
        cache[key]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }?.token

    private fun store(key: String, token: String, expiresAtMillis: Long) {
        cache[key] = CachedToken(token, expiresAtMillis)
    }

    private fun appendQuery(url: String, query: String): String =
        if (url.contains("?")) "$url&$query" else "$url?$query"
}
