package com.chelayel.geminirelay.agent

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent's web access. Everything here runs against a throwaway local server
 * rather than the open internet, so the tests say something about this code and
 * not about whoever is up today.
 *
 * Plain JUnit, no IntelliJ fixture: [Web] depends on its own [Web.Settings]
 * rather than on the `GeminiSettings` application service precisely so this can
 * run in a second without booting a test IDE.
 */
class WebTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/page") { ex ->
            val body = """
                <html><head><title>T</title><style>.a{color:red}</style></head>
                <body><script>var x = 1;</script>
                <h1>Spring Boot 4</h1>
                <p>Use <code>spring-boot-starter-web</code> &amp; friends.</p>
                <p>Second&nbsp;paragraph.</p>
                </body></html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/plain") { ex ->
            val body = "line one\nline two\n".toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "/plain")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        createContext("/big") { ex ->
            val body = ("<p>" + "spring ".repeat(4_000) + "</p>").toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        createContext("/gone") { ex ->
            val body = "nothing here".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"

    private val web = Web(settings())

    @AfterTest fun stop() = server.stop(0)

    private fun call(tool: String, vararg pairs: Pair<String, Any>): JsonObject =
        web.execute(
            tool,
            JsonObject().apply {
                pairs.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> addProperty(k, v)
                        is Int -> addProperty(k, v)
                        else -> addProperty(k, v.toString())
                    }
                }
            },
        )

    private fun JsonObject.result(): String = get("result")?.asString.orEmpty()
    private fun JsonObject.error(): String = get("error")?.asString.orEmpty()

    // ---- fetchUrl -------------------------------------------------------------

    @Test
    fun `fetches a page as readable text with markup and scripts gone`() {
        val out = call("fetchUrl", "url" to "$base/page")
        assertTrue(out.error().isEmpty(), out.error())
        val text = out.result()
        assertTrue(text.contains("Spring Boot 4"), text)
        assertTrue(text.contains("spring-boot-starter-web"), text)
        assertFalse(text.contains("var x = 1"), "script bodies must not reach the model")
        assertFalse(text.contains("color:red"), "stylesheet bodies must not reach the model")
        assertFalse(text.contains("<p>"), "tags must be stripped")
    }

    @Test
    fun `decodes entities rather than showing them raw`() {
        val text = call("fetchUrl", "url" to "$base/page").result()
        assertTrue(text.contains("&"), text)
        assertFalse(text.contains("&amp;"), text)
        assertFalse(text.contains("&nbsp;"), text)
    }

    @Test
    fun `keeps block structure so a document does not collapse into one line`() {
        val text = call("fetchUrl", "url" to "$base/page").result()
        assertTrue(text.lines().size > 1, "expected several lines, got: $text")
    }

    @Test
    fun `non-html is returned as-is`() {
        assertEquals("line one\nline two", call("fetchUrl", "url" to "$base/plain").result().trim())
    }

    /** `HttpURLConnection` stops at a cross-protocol redirect, so the hops are walked by hand. */
    @Test
    fun `follows a redirect`() {
        assertTrue(call("fetchUrl", "url" to "$base/moved").result().contains("line one"))
    }

    /** A documentation page can be megabytes; the context window cannot. */
    @Test
    fun `truncates at maxChars instead of flooding the context`() {
        val whole = call("fetchUrl", "url" to "$base/big").result()
        assertTrue(whole.length > 20_000, "fixture should be large, got ${whole.length}")
        val clipped = call("fetchUrl", "url" to "$base/big", "maxChars" to 1_000).result()
        assertTrue(clipped.length < 1_100, "expected a truncated body, got ${clipped.length} chars")
        assertTrue(clipped.contains("truncated"), "truncation must be visible to the model: $clipped")
    }

    @Test
    fun `an http error is reported, not returned as page content`() {
        val out = call("fetchUrl", "url" to "$base/gone")
        assertTrue(out.error().contains("404"), out.error())
    }

    @Test
    fun `a relative url is refused with a usable message`() {
        assertTrue(call("fetchUrl", "url" to "/page").error().contains("absolute"))
    }

    // ---- configuration --------------------------------------------------------

    /**
     * Every keyless search endpoint tried (DuckDuckGo html and lite, Mojeek)
     * answers an automated client with a challenge page, so there is no default
     * provider. The tool is then not advertised at all: a tool that always
     * answers "not configured" teaches the model to stop trying and go back to
     * guessing, which is the failure web access exists to prevent.
     */
    @Test
    fun `webSearch is not advertised until a provider is configured`() {
        val names = Web(settings()).declarations().map { it.name }
        assertFalse("webSearch" in names, "unconfigured search must not be offered: $names")
        assertTrue("fetchUrl" in names, "fetchUrl needs no provider: $names")
        assertTrue("mavenSearch" in names, "mavenSearch needs no provider: $names")
    }

    @Test
    fun `an unconfigured search says where to set it up`() {
        val out = Web(settings()).execute("webSearch", JsonObject().apply { addProperty("query", "x") })
        val message = out.get("error")?.asString.orEmpty()
        assertTrue(message.contains("Settings"), message)
        assertTrue(message.contains("fetchUrl"), message)
    }

    @Test
    fun `a configured provider advertises search`() {
        val configured = Web(settings(provider = "brave", key = "k"))
        assertTrue("webSearch" in configured.declarations().map { it.name })
    }

    @Test
    fun `a key alone is enough, defaulting the provider`() {
        assertEquals(Web.Provider.BRAVE, Web(settings(key = "k")).provider)
    }

    @Test
    fun `google needs an engine id as well as a key`() {
        val partial = Web(settings(provider = "google", key = "k"))
        assertTrue(partial.searchUnavailable()?.contains("engine id") == true, partial.searchUnavailable().orEmpty())
    }

    @Test
    fun `web can be switched off entirely`() {
        val off = Web(settings(enabled = false))
        assertTrue(off.declarations().isEmpty())
        assertFalse(off.handles("fetchUrl"))
    }

    /** A fake of exactly what Web reads, so no IDE application is needed. */
    private fun settings(
        enabled: Boolean = true,
        provider: String = "",
        key: String = "",
        cx: String = "",
    ): Web.Settings = object : Web.Settings {
        override val webEnabled = enabled
        override val searchProvider = provider
        override val searchApiKey = key
        override val searchCx = cx
    }
}
