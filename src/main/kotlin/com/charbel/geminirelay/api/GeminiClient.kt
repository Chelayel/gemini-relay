package com.charbel.geminirelay.api

import com.charbel.geminirelay.settings.ConnectionMode
import com.charbel.geminirelay.settings.GeminiSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Issues a single streaming `streamGenerateContent` call against whichever
 * backend the [settings] select, parses the Server-Sent-Events stream, emits
 * text deltas as they arrive, and returns the fully assembled [ModelTurn].
 *
 * Cancellation: [cancel] disconnects the in-flight request so a long turn can
 * be stopped from the UI.
 */
class GeminiClient(private val settings: GeminiSettings) {

    private val log = logger<GeminiClient>()

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { connection?.disconnect() }
    }

    /**
     * Runs one model turn. [onText] receives incremental assistant text;
     * the returned [ModelTurn] carries the full text plus any function calls.
     * A 401 transparently refreshes the token once and retries.
     */
    fun streamTurn(
        contents: List<Content>,
        systemPrompt: String?,
        tools: List<FunctionDecl>,
        onText: (String) -> Unit,
    ): ModelTurn {
        return try {
            request(contents, systemPrompt, tools, onText)
        } catch (e: UnauthorizedException) {
            log.info("Auth rejected; refreshing token and retrying once.")
            AuthProvider.invalidate()
            try {
                request(contents, systemPrompt, tools, onText)
            } catch (e2: UnauthorizedException) {
                throw GeminiException(e2.detail)
            }
        }
    }

    private class UnauthorizedException(val detail: String) : RuntimeException(detail)

    private fun request(
        contents: List<Content>,
        systemPrompt: String?,
        tools: List<FunctionDecl>,
        onText: (String) -> Unit,
    ): ModelTurn {
        val auth = AuthProvider.resolve(settings)
        val url = URI(endpoint(auth)).toURL()
        val body = buildRequest(contents, systemPrompt, tools).toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 5 * 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (auth is Auth.Bearer) setRequestProperty("Authorization", "Bearer ${auth.token}")
        }
        connection = conn

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val status = conn.responseCode
        if (status == 401 || status == 403) {
            val err = conn.errorStream?.let { readAll(it) }.orEmpty()
            throw UnauthorizedException(humanizeError(status, err))
        }
        if (status / 100 != 2) {
            val err = conn.errorStream?.let { readAll(it) }.orEmpty()
            throw GeminiException(humanizeError(status, err))
        }

        return BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
            parseSse(reader, onText)
        }
    }

    // ---- SSE parsing ---------------------------------------------------------

    private fun parseSse(reader: BufferedReader, onText: (String) -> Unit): ModelTurn {
        val texts = StringBuilder()
        val calls = mutableListOf<Part.FunctionCall>()
        var finishReason: String? = null
        var usage: Usage? = null

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (cancelled) break
            val raw = line!!.trim()
            if (!raw.startsWith("data:")) continue
            val payload = raw.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue

            val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue

            chunk.getAsJsonObject("usageMetadata")?.let { usage = parseUsage(it) }

            val candidate = chunk.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject ?: continue
            candidate.get("finishReason")?.takeIf { it.isJsonPrimitive }?.let { finishReason = it.asString }

            val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts") ?: continue
            for (el in parts) {
                val part = el.asJsonObject
                part.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { t ->
                    if (t.isNotEmpty()) { texts.append(t); onText(t) }
                }
                part.getAsJsonObject("functionCall")?.let { fc ->
                    val name = fc.get("name")?.asString ?: return@let
                    val args = fc.getAsJsonObject("args") ?: JsonObject()
                    calls.add(Part.FunctionCall(name, args))
                }
            }
        }

        val assembled = buildList<Part> {
            if (texts.isNotEmpty()) add(Part.Text(texts.toString()))
            addAll(calls)
        }
        return ModelTurn(assembled, finishReason, usage)
    }

    private fun parseUsage(obj: JsonObject): Usage = Usage(
        promptTokens = obj.longOr("promptTokenCount"),
        candidateTokens = obj.longOr("candidatesTokenCount"),
        totalTokens = obj.longOr("totalTokenCount"),
    )

    // ---- request body --------------------------------------------------------

    private fun buildRequest(
        contents: List<Content>,
        systemPrompt: String?,
        tools: List<FunctionDecl>,
    ): JsonObject {
        val root = JsonObject()

        val contentArr = JsonArray()
        contents.forEach { contentArr.add(contentJson(it)) }
        root.add("contents", contentArr)

        if (!systemPrompt.isNullOrBlank()) {
            val sys = JsonObject()
            val partArr = JsonArray()
            partArr.add(JsonObject().apply { addProperty("text", systemPrompt) })
            sys.add("parts", partArr)
            root.add("systemInstruction", sys)
        }

        if (tools.isNotEmpty()) {
            val decls = JsonArray()
            tools.forEach { decls.add(functionDeclJson(it)) }
            val toolObj = JsonObject().apply { add("functionDeclarations", decls) }
            root.add("tools", JsonArray().apply { add(toolObj) })
        }

        return root
    }

    private fun contentJson(content: Content): JsonObject {
        val obj = JsonObject().apply { addProperty("role", content.role) }
        val parts = JsonArray()
        for (part in content.parts) {
            parts.add(
                when (part) {
                    is Part.Text -> JsonObject().apply { addProperty("text", part.text) }
                    is Part.InlineData -> JsonObject().apply {
                        add("inlineData", JsonObject().apply {
                            addProperty("mimeType", part.mimeType)
                            addProperty("data", part.dataBase64)
                        })
                    }
                    is Part.FunctionCall -> JsonObject().apply {
                        add("functionCall", JsonObject().apply {
                            addProperty("name", part.name)
                            add("args", part.args)
                        })
                    }
                    is Part.FunctionResponse -> JsonObject().apply {
                        add("functionResponse", JsonObject().apply {
                            addProperty("name", part.name)
                            add("response", part.response)
                        })
                    }
                },
            )
        }
        obj.add("parts", parts)
        return obj
    }

    private fun functionDeclJson(decl: FunctionDecl): JsonObject = JsonObject().apply {
        addProperty("name", decl.name)
        addProperty("description", decl.description)
        add("parameters", decl.parameters)
    }

    // ---- endpoint resolution -------------------------------------------------

    /** Builds the full streaming URL for the active mode (SSE enabled). */
    private fun endpoint(auth: Auth): String {
        val model = settings.model
        return when (settings.connectionMode) {
            ConnectionMode.GEMINI_API -> {
                val key = (auth as Auth.QueryKey).key
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "${enc(model)}:streamGenerateContent?alt=sse&key=${enc(key)}"
            }

            ConnectionMode.VERTEX -> {
                val host = "${settings.vertexLocation}-aiplatform.googleapis.com"
                vertexUrl(host, model)
            }

            ConnectionMode.VERTEX_APIGEE -> {
                val host = settings.vertexApiEndpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')
                vertexUrl(host, model)
            }
        }
    }

    private fun vertexUrl(host: String, model: String): String {
        val project = settings.vertexProjectId
        val location = settings.vertexLocation
        return "https://$host/v1/projects/${enc(project)}/locations/${enc(location)}" +
            "/publishers/google/models/${enc(model)}:streamGenerateContent?alt=sse"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    private fun humanizeError(status: Int, body: String): String {
        val detail = runCatching {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        return "Gemini request failed (HTTP $status)" + (detail?.let { ": $it" } ?: ". ${body.take(300)}")
    }

    private fun readAll(stream: java.io.InputStream): String =
        stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun JsonObject.longOr(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun JsonArray.firstOrNull() = if (size() > 0) get(0) else null

    class GeminiException(message: String) : RuntimeException(message)
}
