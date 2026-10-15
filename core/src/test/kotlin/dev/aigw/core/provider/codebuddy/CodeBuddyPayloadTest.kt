package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CodeBuddy 请求体改写。
 *
 * 两条硬约束来自上游实测：非流式请求会被拒（必须强制 `stream:true`）；
 * `tool_choice` 在上游 Go struct 里是 string，传对象会 400（code=11101）。
 */
class CodeBuddyPayloadTest {

    private val provider = CodeBuddyProvider()

    private fun prepare(body: String) =
        JsonParser.parseString(prepareCodeBuddyBody(body)).asJsonObject

    @Test
    fun `强制开启流式`() {
        assertTrue(prepare("""{"model":"m","messages":[]}""").get("stream").asBoolean)
        assertTrue(prepare("""{"model":"m","messages":[],"stream":false}""").get("stream").asBoolean)
    }

    @Test
    fun `tool_choice 对象形式归一化成字符串`() {
        val auto = prepare("""{"tool_choice":{"type":"auto"},"tools":[{"type":"function"}]}""")
        assertEquals("auto", auto.get("tool_choice").asString)

        val required = prepare("""{"tool_choice":{"type":"required"}}""")
        assertEquals("required", required.get("tool_choice").asString)

        val named = prepare("""{"tool_choice":{"type":"function","function":{"name":"get_weather"}}}""")
        assertEquals("get_weather", named.get("tool_choice").asString)
    }

    @Test
    fun `none 会连同 tools 一起删掉`() {
        val none = prepare("""{"tool_choice":"none","tools":[{"type":"function"}],"functions":[{}]}""")
        assertFalse(none.has("tool_choice"))
        assertFalse(none.has("tools"))
        assertFalse(none.has("functions"))

        val noneObject = prepare("""{"tool_choice":{"type":"none"},"tools":[{"type":"function"}]}""")
        assertFalse(noneObject.has("tool_choice"))
        assertFalse(noneObject.has("tools"))
    }

    @Test
    fun `字符串形式的 auto 原样保留`() {
        val obj = prepare("""{"tool_choice":"auto","tools":[{"type":"function"}]}""")
        assertEquals("auto", obj.get("tool_choice").asString)
        assertTrue(obj.has("tools"))
    }

    @Test
    fun `无法识别的 tool_choice 直接删掉`() {
        assertFalse(prepare("""{"tool_choice":{"type":"weird"}}""").has("tool_choice"))
        assertFalse(prepare("""{"tool_choice":[1,2]}""").has("tool_choice"))
    }

    @Test
    fun `401 与 403 判为凭证失效`() {
        // 落到 CLIENT 的话网关会当作客户端错误早返回：不禁用死账号、也不换供应商
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, "").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(403, "").kind)
        // 其余分类不受影响
        assertEquals(ErrorKind.QUOTA, provider.classify(402, "").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, "").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(500, "").kind)
        assertEquals(ErrorKind.CLIENT, provider.classify(400, "").kind)
    }

    @Test
    fun `429 带业务码 6004 判为模型级限额`() {
        // 6004 是「该模型」超限：换账号无用（其它模型立刻可用），且不该计供应商熔断
        assertEquals(
            ErrorKind.MODEL_LIMIT,
            provider.classify(429, """{"code":6004,"message":"当前模型已达使用上限"}""").kind,
        )
        // 6004 也可能随非 429 状态码回来
        assertEquals(
            ErrorKind.MODEL_LIMIT,
            provider.classify(400, """{"code":6004}""").kind,
        )
        // 其它业务码 / 无业务码的 429 仍是可退避限流
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, """{"code":1234}""").kind)
    }

    @Test
    fun `非法 JSON 原样返回不抛异常`() {
        assertEquals("not-json", prepareCodeBuddyBody("not-json"))
    }

    @Test
    fun `首条不是 system 时补一条`() {
        // 上游以 400 code=11128“first message is not system prompt”拒绝；
        // 模型页的「测试」探测发的是纯 user 消息，靠这里补上
        val messages = prepare("""{"messages":[{"role":"user","content":"ping"}]}""")
            .getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `首条已是 system 时不重复插入`() {
        val messages = prepare("""{"messages":[{"role":"system","content":"s"},{"role":"user","content":"u"}]}""")
            .getAsJsonArray("messages")
        assertEquals(2, messages.size())
    }

    @Test
    fun `developer 角色改叫 system`() {
        val messages = prepare("""{"messages":[{"role":"developer","content":"d"},{"role":"user","content":"u"}]}""")
            .getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
    }

    @Test
    fun `max_completion_tokens 改名 max_tokens`() {
        val renamed = prepare("""{"messages":[{"role":"system","content":"s"}],"max_completion_tokens":128}""")
        assertFalse(renamed.has("max_completion_tokens"))
        assertEquals(128, renamed.get("max_tokens").asInt)

        // 两者都在时以 max_tokens 为准，不覆盖
        val both = prepare("""{"messages":[],"max_completion_tokens":1,"max_tokens":2}""")
        assertEquals(2, both.get("max_tokens").asInt)
    }

    @Test
    fun `第三方 CLI 的特征文本会被替换`() {
        val messages = prepare(
            """{"messages":[
              {"role":"system","content":"You are Claude Code, Anthropic's official CLI for Claude"},
              {"role":"user","content":[{"type":"text","text":"x\nx-anthropic-billing-header: abc\nMain branch (you will usually use this for PRs)"}]}
            ]}""".trimIndent(),
        ).getAsJsonArray("messages")

        assertEquals(
            "You are Claude Code, Anthropic's CLI for Claude",
            messages[0].asJsonObject.get("content").asString,
        )
        val text = messages[1].asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        assertFalse(text.contains("x-anthropic-billing-header"))
        assertTrue(text.contains("Main branch (usually the base for PRs)"))
    }

    @Test
    fun `工具结果后紧跟用户消息时插入空 assistant`() {
        // 上游回 400 11153「the conversation must end with a user message」
        val messages = prepare(
            """{"messages":[
              {"role":"system","content":"s"},
              {"role":"assistant","content":"","tool_calls":[{"id":"1","type":"function","function":{"name":"f","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"1","content":"ok"},
              {"role":"user","content":"继续"}]}""",
        ).getAsJsonArray("messages")
        assertEquals(5, messages.size())
        assertEquals("assistant", messages[3].asJsonObject.get("role").asString)
        assertEquals("", messages[3].asJsonObject.get("content").asString)
        assertEquals("user", messages[4].asJsonObject.get("role").asString)
    }

    @Test
    fun `没有 tool 后接 user 时不插入`() {
        val messages = prepare(
            """{"messages":[{"role":"system","content":"s"},{"role":"tool","tool_call_id":"1","content":"ok"}]}""",
        ).getAsJsonArray("messages")
        assertEquals(2, messages.size())
    }
}
