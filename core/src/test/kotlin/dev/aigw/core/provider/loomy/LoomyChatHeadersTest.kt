package dev.aigw.core.provider.loomy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 对话请求头的标识回归。
 *
 * `chatid`/`msgid` 是上游**可选的计费归因头**（官方逆向文档：Optional attribution headers,
 * used for points bookkeeping server-side），不是前缀缓存的分桶键。官方客户端每个请求
 * 都发新的 chatid，参照实现（loomy2api）则完全不发这两个头。
 *
 * 曾经把 chatid 改成「同会话稳定」，随后出现偶发 HTTP 409——稳定的 chatid 配每请求换新的
 * msgid 会被上游当成同一对话内的重放/并发。这里锁住「两个 id 每请求都唯一」。
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
    fun `同一会话的两轮请求 chatid 也必须各不相同`() {
        val first = """{"model":"m","messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"第一条"}]}"""
        val second = """{"model":"m","messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"第一条"},""" +
            """{"role":"assistant","content":"回复"},{"role":"user","content":"第二条"}]}"""
        val hops = capture(listOf(first, second))
        assertEquals(2, hops.size)
        assertNotEquals(
            hops[0].first,
            hops[1].first,
            "chatid 复用同值会被上游当成同一对话的重复请求（实测偶发 HTTP 409）",
        )
        assertNotEquals(hops[0].second, hops[1].second, "msgid 每请求唯一")
    }

    @Test
    fun `chatid 与 msgid 形状与官方客户端一致`() {
        val hops = capture(listOf("""{"messages":[{"role":"user","content":"A"}]}"""))
        val (chatId, msgId) = hops.single()
        val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        assertEquals(true, Regex("^chat-$uuid$").matches(chatId), "实际：$chatId")
        assertEquals(true, Regex("^msg-$uuid$").matches(msgId), "实际：$msgId")
    }
}
