package com.chelayel.geminirelay.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two pure pieces of the MCP client: the schema filter and the name
 * mangler. Both sit between somebody else's server and Gemini's validator, and
 * both fail the same way when they're wrong — the whole server's tools are
 * rejected at request time with an opaque 400, long after the handshake said
 * everything was fine.
 *
 * Only the companion is exercised, so no child process and no IDE are started.
 */
class McpClientTest {

    private fun schema(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `drops keys Gemini's validator rejects`() {
        val out = McpClient.sanitizeSchema(
            schema(
                """
                {"type":"object","${'$'}schema":"http://json-schema.org/draft-07/schema#",
                 "additionalProperties":false,"title":"Args","default":{},
                 "properties":{"path":{"type":"string","description":"a path","format":"uri"}},
                 "required":["path"]}
                """,
            ),
        )
        assertFalse(out.has("\$schema"), out.toString())
        assertFalse(out.has("additionalProperties"), out.toString())
        assertFalse(out.has("title"), out.toString())
        assertFalse(out.has("default"), out.toString())
        assertFalse(out.getAsJsonObject("properties").getAsJsonObject("path").has("format"), out.toString())
    }

    @Test
    fun `keeps the parts a function declaration needs`() {
        val out = McpClient.sanitizeSchema(
            schema(
                """
                {"type":"object",
                 "properties":{"mode":{"type":"string","description":"how","enum":["a","b"]}},
                 "required":["mode"]}
                """,
            ),
        )
        val mode = out.getAsJsonObject("properties").getAsJsonObject("mode")
        assertEquals("string", mode.get("type").asString)
        assertEquals("how", mode.get("description").asString)
        assertEquals(2, mode.getAsJsonArray("enum").size())
        assertEquals("mode", out.getAsJsonArray("required").first().asString)
    }

    /** A nested object or array schema is filtered too, not passed through. */
    @Test
    fun `sanitizes nested properties and array items`() {
        val out = McpClient.sanitizeSchema(
            schema(
                """
                {"type":"object","properties":{
                   "filter":{"type":"object","additionalProperties":true,
                             "properties":{"name":{"type":"string","pattern":"^x"}}},
                   "tags":{"type":"array","items":{"type":"string","minLength":1}}}}
                """,
            ),
        )
        val props = out.getAsJsonObject("properties")
        val filter = props.getAsJsonObject("filter")
        assertFalse(filter.has("additionalProperties"), filter.toString())
        assertFalse(filter.getAsJsonObject("properties").getAsJsonObject("name").has("pattern"), filter.toString())
        assertFalse(props.getAsJsonObject("tags").getAsJsonObject("items").has("minLength"), props.toString())
    }

    /** Gemini requires a type; a server that omits one must not sink its siblings. */
    @Test
    fun `supplies a type when the server left it out`() {
        assertEquals("object", McpClient.sanitizeSchema(schema("""{"description":"no type here"}""")).get("type").asString)
    }

    @Test
    fun `builds a function name Gemini will accept`() {
        val name = McpClient.functionName("my-server.v2", "list files")
        assertEquals("my_server_v2_list_files", name)
        assertTrue(name.matches(Regex("[a-zA-Z0-9_]+")), name)
    }

    @Test
    fun `caps the function name length`() {
        assertEquals(60, McpClient.functionName("s".repeat(40), "t".repeat(40)).length)
    }
}
