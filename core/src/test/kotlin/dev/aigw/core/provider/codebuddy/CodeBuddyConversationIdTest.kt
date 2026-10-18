package dev.aigw.core.provider.codebuddy

import dev.aigw.core.protocol.stableConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 会话 ID 派生。
 *
 * 上游按 `X-Conversation-ID` 给提示词缓存分桶：同一个会话的多轮请求必须是**同一个值**
 * （否则每次请求落到不同桶，长上下文下实测 0% 前缀命中、积分多花约 3 倍），
 * 不同会话必须不同（否则互相污染缓存桶）。
 * 参照实现：`simplast/workbuddy-api` 的 `deriveConversationId`
 * （MIT，https://github.com/simplast/workbuddy-api）。
 */
class CodeBuddyConversationIdTest {

    private fun id(body: String) = stableConversationId(body)

    @Test
    fun `同一会话多轮请求得到同一个会话 ID`() {
        val first = """{"messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"第一条"}]}"""
        // 第二轮：在尾部追加 assistant + user，会话前缀不变
        val second = """{"messages":[{"role":"system","content":"你是助手"},{"role":"user","content":"第一条"},
            {"role":"assistant","content":"回复"},{"role":"user","content":"第二条"}]}"""
        assertEquals(id(first), id(second), "同会话的多轮请求必须派生出同一个会话 ID")
    }

    @Test
    fun `不同会话的会话 ID 不同`() {
        val a = """{"messages":[{"role":"system","content":"S"},{"role":"user","content":"A"}]}"""
        val b = """{"messages":[{"role":"system","content":"S"},{"role":"user","content":"B"}]}"""
        assertNotEquals(id(a), id(b))

        // 只有 system 不同也算不同会话
        val c = """{"messages":[{"role":"system","content":"S2"},{"role":"user","content":"A"}]}"""
        assertNotEquals(id(a), id(c))
    }

    @Test
    fun `多模态 content 也能参与指纹`() {
        val one = """{"messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}]}"""
        val two = """{"messages":[{"role":"user","content":[{"type":"text","text":"world"}]}]}"""
        assertNotEquals(id(one), id(two))
        assertEquals(id(one), id(one))
    }

    @Test
    fun `无可用消息时返回空串交由调用方兜底`() {
        // 不能返回随机值——那会让每次请求落到不同缓存桶
        assertEquals("", id("""{"messages":[]}"""))
        assertEquals("", id("not-json"))
        assertEquals("", id("""{"model":"m"}"""))
    }

    @Test
    fun `派生结果是标准 UUID 形状`() {
        val value = id("""{"messages":[{"role":"user","content":"x"}]}""")
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(value),
            "实际：$value",
        )
    }
}
