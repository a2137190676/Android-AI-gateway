package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工具形状归一。
 *
 * 不做这些变换时，国际版会直接回 400「Invalid request parameters」（11133）。
 * 规则逐条来自官方渠道的实测记录，这里把每条都钉住。
 */
class CodeBuddyToolSchemaTest {

    private fun normalize(json: String) =
        normalizeToolParameters(JsonParser.parseString(json)).asJsonObject

    @Test
    fun `根级没有 type 时当对象`() {
        val out = normalize("""{"properties":{"a":{"type":"string"}}}""")
        assertEquals("object", out.get("type").asString)
        assertEquals("string", out.getAsJsonObject("properties").getAsJsonObject("a").get("type").asString)
    }

    @Test
    fun `根级是联合时合并各分支的字段`() {
        val out = normalize(
            """{"anyOf":[{"properties":{"a":{"type":"string"}}},{"properties":{"b":{"type":"number"}},"required":["b"]}]}""",
        )
        assertEquals("object", out.get("type").asString)
        assertFalse(out.has("anyOf"), "联合应已被合并成对象")
        val props = out.getAsJsonObject("properties")
        assertTrue(props.has("a") && props.has("b"), "两个分支的字段都要在：$props")
        assertEquals(listOf("b"), out.getAsJsonArray("required").map { it.asString })
    }

    @Test
    fun `已是合法对象时不改动结构`() {
        val out = normalize("""{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}""")
        assertEquals("object", out.get("type").asString)
        assertEquals(listOf("a"), out.getAsJsonArray("required").map { it.asString })
    }

    @Test
    fun `pattern 里的反斜杠零换成 u0000`() {
        val out = normalize("""{"type":"object","properties":{"a":{"type":"string","pattern":"x\\0y"}}}""")
        val pattern = out.getAsJsonObject("properties").getAsJsonObject("a").get("pattern").asString
        assertEquals("x\\u0000y", pattern)
    }

    @Test
    fun `元组式 items 合成任一满足的单个 schema`() {
        val out = normalize("""{"type":"array","items":[{"type":"string"},{"type":"number"}]}""")
        assertTrue(out.get("items").isJsonObject, "items 应是单个 schema：${out.get("items")}")
        assertEquals(2, out.getAsJsonObject("items").getAsJsonArray("anyOf").size())
    }

    @Test
    fun `enum 无 type 时补类型`() {
        val single = normalize("""{"properties":{"a":{"enum":["x","y"]}}}""")
        assertEquals("string", single.getAsJsonObject("properties").getAsJsonObject("a").get("type").asString)

        val withNull = normalize("""{"properties":{"a":{"enum":["x",null]}}}""")
        val types = withNull.getAsJsonObject("properties").getAsJsonObject("a").getAsJsonArray("type")
        assertEquals(listOf("string", "null"), types.map { it.asString })
    }

    @Test
    fun `enum 多类型时按类型分组为 anyOf`() {
        val out = normalize("""{"properties":{"a":{"enum":["x",1]}}}""")
        val schema = out.getAsJsonObject("properties").getAsJsonObject("a")
        assertFalse(schema.has("enum"), "原始 enum 应已被 anyOf 取代")
        val branches = schema.getAsJsonArray("anyOf")
        assertEquals(2, branches.size())
        assertEquals("string", branches[0].asJsonObject.get("type").asString)
        assertEquals("number", branches[1].asJsonObject.get("type").asString)
    }

    @Test
    fun `空 enum 直接去掉`() {
        val out = normalize("""{"properties":{"a":{"type":"string","enum":[]}}}""")
        assertFalse(out.getAsJsonObject("properties").getAsJsonObject("a").has("enum"))
    }

    @Test
    fun `schema 位置的布尔值换成空对象与 not`() {
        val out = normalize("""{"properties":{"a":true,"b":false}}""")
        val props = out.getAsJsonObject("properties")
        assertEquals(0, props.getAsJsonObject("a").entrySet().size)
        assertTrue(props.getAsJsonObject("b").has("not"))
    }

    @Test
    fun `additionalProperties 原样保留`() {
        val out = normalize("""{"type":"object","additionalProperties":true}""")
        assertTrue(out.get("additionalProperties").asBoolean, "additionalProperties 不该被改")
    }

    @Test
    fun `非数组的 required 丢掉、未知 type 丢掉、String 改成 string`() {
        val out = normalize(
            """{"type":"object","required":"a","properties":{"a":{"type":"String"},"b":{"type":"Foo"}}}""",
        )
        assertFalse(out.has("required"), "非数组 required 应去掉")
        val props = out.getAsJsonObject("properties")
        assertEquals("string", props.getAsJsonObject("a").get("type").asString)
        assertFalse(props.getAsJsonObject("b").has("type"), "未知 type 应去掉")
    }

    @Test
    fun `工具名非法字符换成下划线且合法名不动`() {
        assertEquals("mcp_server_tool", sanitizeToolName("mcp.server:tool"))
        assertEquals("a_b_c", sanitizeToolName("a b/c"))
        assertEquals("___", sanitizeToolName("中文名"))
        assertEquals("read_file-2", sanitizeToolName("read_file-2"))
    }

    @Test
    fun `请求侧改写工具名并可还原`() {
        val raw = """
            {"messages":[],"tools":[{"type":"function","function":{
              "name":"mcp.server.read","description":"d","parameters":{"properties":{"a":{"type":"string"}}}}}]}
        """.trimIndent()
        val request = prepareCodeBuddyRequest(raw)
        val tool = JsonParser.parseString(request.body).asJsonObject
            .getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function")
        assertEquals("mcp_server_read", tool.get("name").asString)
        assertEquals("object", tool.getAsJsonObject("parameters").get("type").asString)
        assertEquals(mapOf("mcp_server_read" to "mcp.server.read"), request.toolNameRestore)
    }

    @Test
    fun `响应侧按还原表改回原名`() {
        val chunk = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"mcp_server_read","arguments":"{}"}}]}}]}"""
        val restored = JsonParser.parseString(restoreToolNames(chunk, mapOf("mcp_server_read" to "mcp.server.read")))
            .asJsonObject
        val call = restored.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("delta").getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals("mcp.server.read", call.getAsJsonObject("function").get("name").asString)
    }

    @Test
    fun `流包装逐行还原且保持帧结构`() {
        val upstream = buildString {
            append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"mcp_server_read\"}}]}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val restored = ToolNameRestoringStream(
            upstream.byteInputStream(Charsets.UTF_8),
            mapOf("mcp_server_read" to "mcp.server.read"),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(restored.contains("\"mcp.server.read\""), "调用名应还原：$restored")
        assertTrue(restored.contains("data: [DONE]"), "结束帧应保留：$restored")
        assertEquals(
            upstream.count { it == '\n' },
            restored.count { it == '\n' },
            "行结构必须保持不变",
        )
    }

    @Test
    fun `畸形 schema 只丢弃不抛异常`() {
        // 上游可能给任何形状（required 写成字符串、properties 写成数组）：不能崩
        val out = normalize("""{"type":"object","required":"a","properties":[],"anyOf":"x"}""")
        assertFalse(out.has("required"))
        assertFalse(out.has("properties"))
    }

    @Test
    fun `还原表为空时响应原样透传`() {
        val chunk = """{"choices":[{"delta":{"content":"hi"}}]}"""
        assertEquals(chunk, restoreToolNames(chunk, emptyMap()))
    }
}
