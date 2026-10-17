package dev.aigw.core.gateway

import com.google.gson.JsonParser
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.util.objOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「命中前缀缓存的输入 token」解析。
 *
 * 三家上游口径都认：OpenAI 的 `prompt_tokens_details.cached_tokens`（Gemini 路径由
 * OpenAiGemini 映射过来）、DeepSeek/腾讯系平铺的 `prompt_cache_hit_tokens`、
 * Anthropic 风格的 `cache_read_input_tokens`。上游没报就是 0——绝不估算。
 */
class CachedTokensTest {

    private fun cached(json: String): Long {
        val obj = JsonParser.parseString(json).asJsonObject
        val server = GatewayHttpServer(GatewayEngine(InMemoryKeyValueStore()), "127.0.0.1", 0)
        return server.cachedTokensOf(obj.objOrNull("usage"))
    }

    @Test
    fun `OpenAI 口径从 prompt_tokens_details 里取`() {
        assertEquals(1024L, cached("""{"usage":{"prompt_tokens":2048,"prompt_tokens_details":{"cached_tokens":1024}}}"""))
    }

    @Test
    fun `平铺的 prompt_cache_hit_tokens 也认`() {
        assertEquals(300L, cached("""{"usage":{"prompt_tokens":900,"prompt_cache_hit_tokens":300,"prompt_cache_miss_tokens":600}}"""))
    }

    @Test
    fun `Anthropic 口径的 cache_read_input_tokens 也认`() {
        assertEquals(512L, cached("""{"usage":{"input_tokens":100,"cache_read_input_tokens":512}}"""))
    }

    @Test
    fun `没有缓存字段或 usage 缺失时返回 0 而不是估算`() {
        assertEquals(0L, cached("""{"usage":{"prompt_tokens":800,"completion_tokens":20}}"""))
        assertEquals(0L, cached("""{"usage":{"prompt_tokens_details":{"cached_tokens":0}}}"""))
        assertEquals(0L, cached("""{"choices":[]}"""))
        assertEquals(0L, cached("""{"usage":null}"""))
    }
}
