package dev.aigw.core.provider.loomy

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.protocol.FALLBACK_CONVERSATION_ID
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 对话请求头的会话标识回归。
 *
 * 上游按会话给提示词缓存分桶：`chatid` 在同一会话内必须稳定（每请求换新 = 每请求换
 * 一个缓存桶 = 前缀永不命中），而 `msgid` / `traceparent` 是消息级、链路级标识，
 * 每请求换新才对。
 */
class LoomyChatHeadersTest {

    /** 打一轮真实 HTTP 到本地假上游，回收每请求的 (chatid, msgid)。 */
    private fun capture(bodies: List<String>): List<Pair<String, String>> {
        val captured = ConcurrentLinkedQueue<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(LoomyConstants.PATH_CHAT_COMPLETIONS) { exchange ->
            captured.add(
                exchange.requestHeaders.getFirst(LoomyConstants.CHAT_ID_HEADER).orEmpty() to
                    exchange.requestHeaders.getFirst(LoomyConstants.MESSAGE_ID_HEADER).orEmpty(),
            )
            val bytes = "data: [DONE]\n\n".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = LoomyChatClient(apiBase = "http://127.0.0.1:${server.address.port}")
            val account = LoomyAccount(uid = "u1", phone = "13800000000", session = "s")
            for (body in bodies) client.openStream(account, body).close()
        } finally {
            server.stop(0)
        }
        return captured.toList()
    }

    @Test
    fun `同一会话两轮请求 chatid 相同而 msgid 每轮不同`() {
        val first = """{"model":"m","messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"第一条"}]}"""
        val second = """{"model":"m","messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"第一条"},""" +
            """{"role":"assistant","content":"回复"},{"role":"user","content":"第二条"}]}"""
        val hops = capture(listOf(first, second))
        assertEquals(2, hops.size)
        assertTrue(hops[0].first.isNotEmpty())
        assertEquals(hops[0].first, hops[1].first, "同会话的 chatid 必须稳定，否则每请求换一个缓存桶")
        assertNotEquals(hops[0].second, hops[1].second, "msgid 是消息级标识，应每请求换新")
    }

    @Test
    fun `不同会话 chatid 不同且派生不出时用固定兜底`() {
        val a = """{"messages":[{"role":"user","content":"A"}]}"""
        val b = """{"messages":[{"role":"user","content":"B"}]}"""
        val noMessages = """{"messages":[]}"""
        val hops = capture(listOf(a, b, noMessages))
        assertNotEquals(hops[0].first, hops[1].first, "不同会话不能共用缓存桶")
        assertEquals(
            "chat-$FALLBACK_CONVERSATION_ID",
            hops[2].first,
            "派生不出会话指纹时必须用固定值，随机兜底等于每请求换桶",
        )
    }
}
