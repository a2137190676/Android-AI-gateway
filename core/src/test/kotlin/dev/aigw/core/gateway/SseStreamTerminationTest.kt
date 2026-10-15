package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 流式收尾的回归测试。
 *
 * 上游「正常结束但没发 `data: [DONE]`」时（原样透传类供应商常见），网关既不能把流原样截断，
 * 也不能重复补 [DONE]；另外零内容流仍要报 `upstream_empty`。这里用真起 `GatewayHttpServer`
 * 的桩 Provider 把这些行为锁死。
 */
class SseStreamTerminationTest {

    /** 有实际内容、但上游没给 [DONE]，正常 EOF 关闭。 */
    private val upstreamWithoutDone = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}],\"usage\":null}\n\n")
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}\n\n")
    }

    /** 上游自带 [DONE]。 */
    private val upstreamWithDone = upstreamWithoutDone + "data: [DONE]\n\n"

    /** 零内容：只有元数据行（role/finish），没有任何 payload。 */
    private val upstreamMetadataOnly = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}],\"usage\":null}\n\n")
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}\n\n")
    }

    /** 只有图片、没有文本（图像型号可能如此）：不能判成空回复。 */
    private val upstreamImagesOnly = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{\"images\":[{\"index\":0,\"type\":\"image_url\",")
        append("\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]},\"finish_reason\":null}],\"usage\":null}\n\n")
        append("data: [DONE]\n\n")
    }

    private inner class FramingProvider(private val body: String) : Provider {
        override val id = "framing"
        override val displayName = "收尾桩上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            StreamingChatCall(200, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
    }

    private fun withServer(body: String, block: (port: Int) -> Unit) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(FramingProvider(body))
        engine.pool.upsert(ProviderAccount("framing", "u1", "桩账号", "{}"))
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    /** 发一个流式请求，返回客户端解分块后拿到的完整 SSE 文本。 */
    private fun streamBody(port: Int): String {
        val body = """{"model":"framing/m","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
        val conn = java.net.URL("http://127.0.0.1:$port/v1/chat/completions")
            .openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer test")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        assertEquals(200, conn.responseCode)
        return conn.inputStream.bufferedReader().readText()
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { it == needle }

    @Test
    fun `上游正常 EOF 但缺 DONE 时网关补一个且在末尾`() {
        withServer(upstreamWithoutDone) { port ->
            val raw = streamBody(port)
            assertEquals(1, raw.countOccurrences("[DONE]"), "必须恰好补一个 [DONE]，实际流：$raw")
            assertTrue(
                raw.trimEnd().endsWith("[DONE]"),
                "[DONE] 之后不能再有任何内容，实际流：$raw",
            )
            val afterDone = raw.substringAfterLast("[DONE]")
            assertTrue(afterDone.isBlank(), "[DONE] 之后仍有字节：$afterDone")
            assertTrue(raw.contains("你好"), "正文必须完整透传：$raw")
        }
    }

    @Test
    fun `上游自带 DONE 时不会重复补`() {
        withServer(upstreamWithDone) { port ->
            val raw = streamBody(port)
            assertEquals(1, raw.countOccurrences("[DONE]"), "上游已给 [DONE] 不得重复补，实际流：$raw")
            assertTrue(raw.trimEnd().endsWith("[DONE]"), "实际流：$raw")
        }
    }

    @Test
    fun `零内容流仍报 upstream_empty 并带 DONE`() {
        withServer(upstreamMetadataOnly) { port ->
            val raw = streamBody(port)
            assertTrue(raw.contains("upstream_empty"), "零内容必须报 upstream_empty：$raw")
            assertEquals(1, raw.countOccurrences("[DONE]"), "实际流：$raw")
            assertTrue(raw.trimEnd().endsWith("[DONE]"), "实际流：$raw")
        }
    }

    @Test
    fun `只有图片的流不算空回复`() {
        withServer(upstreamImagesOnly) { port ->
            val raw = streamBody(port)
            assertFalse(raw.contains("upstream_empty"), "纯图响应不得被判为空：$raw")
            assertTrue(raw.contains("image_url"), "图片负载必须透传：$raw")
            assertEquals(1, raw.countOccurrences("[DONE]"), "实际流：$raw")
        }
    }

    @Test
    fun `ChunkWriter 仅在静默后判定为空闲`() {
        val out = ByteArrayOutputStream()
        var now = 0L
        val writer = ChunkWriter(out) { now }

        assertEquals(0L, writer.idleMillis(), "刚创建、未写出时不算静默")

        now = 16_000L
        writer.write("你好")
        assertEquals(0L, writer.idleMillis(), "刚写完数据不算静默")

        now = 16_000L + 1_000L
        assertEquals(1_000L, writer.idleMillis())
        assertTrue(writer.idleMillis() < 15_000L, "静默不足 15s 不该触发心跳")

        now = 16_000L + 15_000L
        assertTrue(writer.idleMillis() >= 15_000L, "静默满 15s 应允许发心跳")
    }

    @Test
    fun `ChunkWriter 被注入的时钟推进后才算静默`() {
        val out = ByteArrayOutputStream()
        var now = 1_000_000L
        val writer = ChunkWriter(out) { now }
        writer.write("a")
        now += 14_999L
        assertTrue(writer.idleMillis() < 15_000L)
        now += 1L
        assertTrue(writer.idleMillis() >= 15_000L)
    }
}
