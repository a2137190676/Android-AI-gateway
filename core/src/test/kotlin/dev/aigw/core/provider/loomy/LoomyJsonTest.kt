package dev.aigw.core.provider.loomy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 流式请求体改写。
 *
 * 上游只有收到 `stream_options.include_usage = true` 时，SSE 末帧才回 `usage`，
 * 否则网关侧用量统计会缺。客户端已传 `stream_options` 时必须合并而不是覆盖。
 */
class LoomyJsonTest {

    private fun parse(body: String) = JsonParser.parseString(body).asJsonObject

    @Test
    fun `无 stream_options 时补上 include_usage`() {
        val out = parse(withIncludeUsage("""{"model":"m","stream":true,"messages":[]}"""))
        assertTrue(out.getAsJsonObject("stream_options").get("include_usage").asBoolean)
    }

    @Test
    fun `已有 stream_options 时合并不覆盖其它字段`() {
        val out = parse(withIncludeUsage("""{"stream":true,"stream_options":{"foo":1}}"""))
        val options = out.getAsJsonObject("stream_options")
        assertEquals(1, options.get("foo").asInt)
        assertTrue(options.get("include_usage").asBoolean)
    }

    @Test
    fun `已有 include_usage 时原样返回`() {
        val body = """{"stream":true,"stream_options":{"include_usage":false}}"""
        // 保留客户端显式设置，不强行改成 true
        assertFalse(parse(withIncludeUsage(body)).getAsJsonObject("stream_options").get("include_usage").asBoolean)
    }

    @Test
    fun `非法 JSON 原样返回`() {
        assertEquals("not-json", withIncludeUsage("not-json"))
    }
}
