package dev.aigw.core.provider.raccoon

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 小浣熊（商汤）协议转换回归。
 *
 * 上游把真正的 OpenAI 结构包在 `data` 里（外层 `{"status":..,"data":..}`），请求体
 * 上游 v2 是 LiteLLM 兼容层，`max_tokens` 等标准字段直接认（改名反而被 500 拒绝）。
 * 这里把关键转换锁住。
 */
class RaccoonProviderTest {

    private companion object {
        const val GRANT_PATH = "/api/web/desktop/v1/login/points/grant"
        const val BILLS_PATH = "/api/web/points/v1/bills"
        const val BALANCE_PATH = "/api/web/points/v1/balance"
    }

    private fun account() = ProviderAccount(
        providerId = RaccoonProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = """{"accessToken":"a.b.c","refreshToken":"r","expiresAt":0,"userId":"u1","nickname":"测试"}""",
    )

    @Test
    fun `登录 URL 带 redirect 且不带 login_source=desktop`() {
        val provider = RaccoonProvider(host = "https://xiaohuanxiong.com")
        val ticket = provider.beginWebLogin("http://127.0.0.1:51122/callback")
        assertTrue(ticket.loginUrl.startsWith("https://xiaohuanxiong.com/code/authorize?"), ticket.loginUrl)
        assertTrue(ticket.loginUrl.contains("redirect=http%3A%2F%2F127.0.0.1%3A51122%2Fcallback"), ticket.loginUrl)
        assertTrue(!ticket.loginUrl.contains("login_source=desktop"), "带 desktop 会走 office-raccoon:// 分支跳不回来：${ticket.loginUrl}")
    }

    @Test
    fun `没有账号时不报错也不算失败`() {
        // 登录前本就不该有模型，这是正常状态：空列表 + 无 error，模型页不该顶红字
        val view = RaccoonProvider().listModels(null)
        assertTrue(view.models.isEmpty(), "无账号不该列出模型")
        assertFalse(view.fromFallback, "无账号是正常状态，不是回退")
        assertEquals("", view.error, "无账号不该报错")
    }

    @Test
    fun `空或 auto 解析为默认模型`() {
        val provider = RaccoonProvider()
        assertEquals(RaccoonProvider.DEFAULT_MODEL, provider.resolveModel(""))
        assertEquals(RaccoonProvider.DEFAULT_MODEL, provider.resolveModel("auto"))
        assertEquals("raccoon-8c4485", provider.resolveModel("raccoon-8c4485"))
    }

    @Test
    fun `模型目录能从未知嵌套结构里找到列表`() {
        // 模拟上游把模型数组藏在 data.profiles.models 这种非标准键下
        val body = """{"status":{"code":0},"data":{"profiles":{"models":[
            {"id":"raccoon-8c4485","name":"Raccoon-Work","max_input_tokens":1000000},
            {"id":"raccoon-chat-ml-5-5","name":"Raccoon Chat"}
        ]}}}"""
        withUpstream(path = "/api/web/llm/v2/model_catalog", body = body) { base ->
            val view = RaccoonProvider(host = base).listModels(account())
            assertEquals(2, view.models.size, view.models.toString())
            assertEquals("raccoon-8c4485", view.models[0].id)
            assertEquals(1_000_000L, view.models[0].contextWindow)
        }
    }

    // ------------------------------------------------------------------ classify

    @Test
    fun `classify 状态码映射`() {
        val provider = RaccoonProvider()
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, "").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(403, "").kind)
        assertEquals(ErrorKind.QUOTA, provider.classify(402, "").kind)
        assertEquals(ErrorKind.NOT_FOUND, provider.classify(404, "").kind)
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, "").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(500, "").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(503, "").kind)
        assertEquals(ErrorKind.CLIENT, provider.classify(400, "").kind)
        assertEquals(ErrorKind.CLIENT, provider.classify(418, "").kind)
    }

    @Test
    fun `classify 从响应体里取错误文案`() {
        val provider = RaccoonProvider()
        val err = provider.classify(401, """{"code":200003,"message":"authorization_verify_error","details":"authorization verify failed"}""")
        assertEquals(ErrorKind.SESSION_DEAD, err.kind)
        assertEquals("authorization_verify_error", err.message)
    }

    // ------------------------------------------------------------------ 请求体转换

    private fun prepare(body: String) = JsonParser.parseString(prepareRaccoonBody(body)).asJsonObject

    @Test
    fun `max_tokens 保持标准名（改名会被上游拒绝）`() {
        val obj = prepare("""{"model":"raccoon-chat","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(128L, obj.get("max_tokens").asLong)
        assertTrue(!obj.has("max_new_tokens"), "上游 v2 不认 max_new_tokens")
    }

    @Test
    fun `max_completion_tokens 映射为 max_tokens`() {
        val obj = prepare(
            """{"model":"m","max_completion_tokens":256,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(256L, obj.get("max_tokens").asLong)
        assertTrue(!obj.has("max_completion_tokens"), "上游不认 max_completion_tokens")
    }

    @Test
    fun `同时给两个上限时以 max_tokens 为准`() {
        val obj = prepare("""{"model":"m","max_tokens":64,"max_completion_tokens":256,"messages":[]}""")
        assertEquals(64L, obj.get("max_tokens").asLong)
    }

    @Test
    fun `采样参数同名保留且 stream 保留`() {
        val obj = prepare(
            """{"model":"m","temperature":0.7,"top_p":0.9,"frequency_penalty":0.1,"presence_penalty":0.2,"stream":true,"stop":["x"]}""",
        )
        assertEquals(0.7, obj.get("temperature").asDouble, 1e-9)
        assertEquals(0.9, obj.get("top_p").asDouble, 1e-9)
        assertEquals(0.1, obj.get("frequency_penalty").asDouble, 1e-9)
        assertEquals(0.2, obj.get("presence_penalty").asDouble, 1e-9)
        assertTrue(obj.get("stream").asBoolean)
        assertEquals("x", obj.getAsJsonArray("stop")[0].asString)
    }

    @Test
    fun `有 tools 时原样映射并带 tool_choice auto`() {
        val tools = """[{"type":"function","function":{"name":"f","description":"d","parameters":{"type":"object"}}}]"""
        val obj = prepare("""{"model":"m","messages":[],"tools":$tools}""")
        assertEquals(1, obj.getAsJsonArray("tools").size())
        assertEquals("f", obj.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
        assertEquals("auto", obj.get("tool_choice").asString)
    }

    @Test
    fun `无 tools 时不加 tool_choice 且非法 JSON 原样返回`() {
        val obj = prepare("""{"model":"m","messages":[]}""")
        assertTrue(!obj.has("tool_choice"))
        assertEquals("not-json", prepareRaccoonBody("not-json"))
    }

    @Test
    fun `客户端非流式请求也被强制上游流式`() {
        val obj = prepare("""{"model":"m","stream":false,"messages":[]}""")
        assertTrue(obj.get("stream").asBoolean, "上游拒绝非流式，必须强制 stream:true")
    }

    @Test
    fun `未传 stop 时补官方默认停止符且带 n=1`() {
        val obj = prepare("""{"model":"m","messages":[]}""")
        assertEquals("<|endofmessage|>", obj.get("stop").asString)
        assertEquals(1, obj.get("n").asInt)
    }

    @Test
    fun `web 通道 delta 为字符串时翻译成 content`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"status":{"code":0},"data":{"id":"abc","choices":[{"index":0,"delta":"你好"}]}}""",
        ).joinToString("")
        assertTrue(out.contains("\"content\":\"你好\""), out)
        assertTrue(out.contains("\"id\":\"abc\""), "应沿用上游帧 id：$out")
        assertTrue(out.contains("\"object\":\"chat.completion.chunk\""), out)
    }

    @Test
    fun `web 通道 status 非 0 时转错误帧`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"status":{"code":200001,"message":"authorization_empty_error"}}""",
        ).joinToString("")
        assertTrue(out.contains("\"error\""), out)
        assertTrue(out.contains("authorization_empty_error"), out)
    }

    @Test
    fun `web 通道非流式 delta 字符串包成 message`() {
        val body = """{"status":{"code":0},"data":{"id":"xyz","choices":[{"index":0,"delta":"答复","finish_reason":"stop"}]}}"""
        withUpstream(body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            val aggregated = assertNotNull(call.aggregated)
            val obj = JsonParser.parseString(aggregated).asJsonObject
            assertEquals("xyz", obj.get("id").asString)
            val message = obj.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            assertEquals("答复", message.get("content").asString)
        }
    }

    @Test
    fun `上游未包裹的 SSE 帧也能翻译`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"choices":[{"index":0,"delta":{"content":"裸"},"finish_reason":"stop"}]}""",
        ).joinToString("")
        assertTrue(out.contains("\"content\":\"裸\""), out)
        assertTrue(out.contains("\"object\":\"chat.completion.chunk\""), out)
    }

    // ------------------------------------------------------------------ SSE 翻译

    @Test
    fun `上游 SSE 翻译成 OpenAI SSE 并以 DONE 结尾`() {
        val translator = RaccoonSseTranslator("chatcmpl-test", "raccoon-chat", 1L)
        val out = ArrayList<String>()
        out += translator.translate(
            """data: {"status":{"code":0,"message":"ok"},"data":{"choices":[{"index":0,"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}}""",
        )
        // 心跳行忽略
        out += translator.translate(": ping")
        out += translator.translate("")
        out += translator.translate(
            """data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}]}}""",
        )
        out += translator.translate("data: [DONE]")

        val joined = out.joinToString("")
        assertTrue(joined.contains("\"object\":\"chat.completion.chunk\""), "必须是 OpenAI chunk：$joined")
        assertTrue(joined.contains("\"content\":\"你\""), "第一段内容缺失：$joined")
        assertTrue(joined.contains("\"content\":\"好\""), "第二段内容缺失：$joined")
        assertTrue(joined.contains("\"finish_reason\":\"stop\""), "收尾 chunk 应带 finish_reason：$joined")
        assertTrue(joined.endsWith("data: [DONE]\n\n"), "必须以 DONE 结尾：$joined")
        // 每帧都必须是 `data: ...\n\n` 形式
        out.filter { it.isNotEmpty() }.forEach {
            assertTrue(it.startsWith("data: ") && it.endsWith("\n\n"), "非法帧：$it")
        }
    }

    @Test
    fun `思维链 reasoning_content 透传`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate(
            """data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"reasoning_content":"想"},"finish_reason":null}]}}""",
        ).joinToString("")
        assertTrue(out.contains("\"reasoning_content\":\"想\""), out)
    }

    @Test
    fun `上游流内业务错误转成 OpenAI 错误帧并收尾`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        val out = translator.translate("""data: {"status":{"code":500001,"message":"quota exceeded"}}""")
        val joined = out.joinToString("")
        assertTrue(joined.contains("\"error\""), joined)
        assertTrue(joined.contains("quota exceeded"), joined)
        assertTrue(joined.endsWith("data: [DONE]\n\n"), joined)
        // 已收尾，close 不再重复补 DONE
        assertTrue(translator.close().isEmpty())
    }

    @Test
    fun `上游没发 DONE 时 close 补一个`() {
        val translator = RaccoonSseTranslator("id", "m", 1L)
        translator.translate("""data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":"stop"}]}}""")
        assertEquals(listOf("data: [DONE]\n\n"), translator.close())
    }

    // ------------------------------------------------------------------ 签到（桌面端登录奖励）

    @Test
    fun `签到携带桌面端请求头`() {
        withUpstreamHeaders(GRANT_PATH, """{"code":0,"data":{"granted":true,"popup":{"source":"desktop_login","points":3000}}}""") { base, captured ->
            RaccoonProvider(host = base).performAction(account(), ACTION_CHECKIN, JsonObject())
            val headers = captured()
            // 官方桌面客户端靠这两个头判定「是桌面端」，缺了上游不发放
            assertEquals("desktop-windows", headers["x-client-platform"]?.firstOrNull(), "缺 X-Client-Platform：$headers")
            assertTrue(
                headers["x-client-version"]?.firstOrNull()?.startsWith("v") == true,
                "缺 X-Client-Version（需带 v 前缀）：$headers",
            )
        }
    }

    @Test
    fun `发放成功时汇报 popup 里的积分`() {
        val body = """{"code":0,"data":{"granted":true,"popup":{"source":"desktop_login","points":3000,"expire_at":"2026-10-20"}}}"""
        withUpstreamHeaders(GRANT_PATH, body) { base, _ ->
            val result = RaccoonProvider(host = base).performAction(account(), ACTION_CHECKIN, JsonObject())
            assertTrue(result.ok, result.message)
            assertTrue(result.message.contains("3000"), "积分没从 data.popup.points 取：${result.message}")
        }
    }

    @Test
    fun `未发放时不报成签到成功`() {
        withUpstreamHeaders(GRANT_PATH, """{"code":0,"data":{"granted":false}}""") { base, _ ->
            val result = RaccoonProvider(host = base).performAction(account(), ACTION_CHECKIN, JsonObject())
            assertTrue(result.ok, result.message)
            assertTrue(
                result.message.contains("未发放"),
                "上游明确没发积分，不能报成签到成功：${result.message}",
            )
        }
    }

    // ------------------------------------------------------------------ 积分明细

    @Test
    fun `积分明细按上游字段映射且保留正负号`() {
        val body = """{"code":0,"data":{"items":[
            {"bill_id":"b1","event_name":"每日赠送积分","biz_type":"daily_grant","points":300,"created_at":"2026-10-14T00:00:05+08:00"},
            {"bill_id":"b2","biz_type":"daily_expire","points":-300,"created_at":"2026-10-14T23:59:59+08:00"}
        ],"paging":{"total":2}}}"""
        withUpstreamHeaders(BILLS_PATH, body) { base, _ ->
            val view = assertNotNull(RaccoonProvider(host = base).pointsBills(account()))
            assertEquals("", view.error)
            assertEquals(2, view.bills.size, view.bills.toString())
            assertEquals("每日赠送积分", view.bills[0].title)
            assertEquals(300L, view.bills[0].points)
            assertEquals("daily_grant", view.bills[0].bizType)
            assertTrue(view.bills[0].createdAtMillis > 0, "created_at 应被解析成毫秒")
            assertEquals(-300L, view.bills[1].points, "支出必须保留负号")
            assertEquals("daily_expire", view.bills[1].title, "event_name 缺失时回退 biz_type")
        }
    }

    // ------------------------------------------------------------------ 积分余额

    @Test
    fun `余额为 0 是真实值而不是缺字段`() {
        val body = """{"code":0,"data":{"available_points":0}}"""
        withUpstream(path = BALANCE_PATH, body = body) { base ->
            val info = assertNotNull(RaccoonProvider(host = base).creditInfo(account()))
            assertTrue(info.known, "上游明确给了 0，就是真实余额")
            assertEquals(0L, info.balance)
        }
    }

    @Test
    fun `余额字段缺失时不能报成 known 的 0`() {
        // 上游改口径（字段没了）时，界面必须显示「—」而不是「积分 0」
        val body = """{"code":0,"data":{"daily_points":300,"monthly_points":1200}}"""
        withUpstream(path = BALANCE_PATH, body = body) { base ->
            val info = assertNotNull(RaccoonProvider(host = base).creditInfo(account()))
            assertFalse(info.known, "没有积分字段就不能当已知额度：${info.detail}")
            assertEquals(0L, info.balance)
        }
    }

    // ------------------------------------------------------------------ 上游响应

    /** 起一个临时上游，并把收到的请求头暴露给断言（签到协议头回归用）。 */
    private fun withUpstreamHeaders(
        path: String,
        body: String,
        block: (baseUrl: String, captured: () -> Map<String, List<String>>) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val headers = AtomicReference<Map<String, List<String>>>(emptyMap())
        server.createContext(path) { exchange ->
            headers.set(exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}") { headers.get() }
        } finally {
            server.stop(0)
        }
    }

    private fun withUpstream(
        status: Int = 200,
        contentType: String = "application/json",
        body: String = "",
        path: String = "/api/web/llm/v2/chat/completions",
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `非流式响应解包 data 里的 choices`() {
        val body = """{"status":{"code":0},"data":{"choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}}"""
        withUpstream(body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"raccoon-chat","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            val aggregated = assertNotNull(call.aggregated)
            val obj = JsonParser.parseString(aggregated).asJsonObject
            assertEquals("chat.completion", obj.get("object").asString)
            assertEquals(
                "你好",
                obj.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString,
            )
        }
    }

    @Test
    fun `200 空 body 视为失败而不是空回复`() {
        withUpstream(body = "") { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated)
            val failure = assertNotNull(call.failure, "必须报失败")
            assertTrue(failure.message.contains("空响应"), failure.message)
            assertTrue(failure.message.contains("HTTP 200"), failure.message)
        }
    }

    @Test
    fun `200 但 data choices 为空视为失败`() {
        withUpstream(body = """{"status":{"code":0},"data":{"choices":[]}}""") { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.aggregated, "零内容不能当成功")
            assertNotNull(call.failure)
        }
    }

    @Test
    fun `客户端要流式而上游回 JSON 时包成 SSE`() {
        val body = """{"status":{"code":0},"data":{"choices":[{"index":0,"message":{"role":"assistant","content":"嗨"},"finish_reason":"stop"}]}}"""
        withUpstream(body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            val sse = assertNotNull(call.stream).readBytes().toString(Charsets.UTF_8)
            assertTrue(sse.contains("\"object\":\"chat.completion.chunk\""), sse)
            assertTrue(sse.contains("嗨"), sse)
            assertTrue(sse.trimEnd().endsWith("data: [DONE]"), sse)
        }
    }

    @Test
    fun `流式 SSE 上游实时翻译`() {
        val body = buildString {
            append("""data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{"role":"assistant","content":"嗨"},"finish_reason":null}]}}""")
            append("\n\n")
            append(": ping\n\n")
            append("""data: {"status":{"code":0},"data":{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        withUpstream(contentType = "text/event-stream", body = body) { base ->
            val call = RaccoonProvider(host = base).openChat(
                account(),
                """{"model":"m","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertNull(call.failure)
            val sse = assertNotNull(call.stream).readBytes().toString(Charsets.UTF_8)
            assertTrue(sse.contains("\"content\":\"嗨\""), sse)
            assertTrue(sse.contains("\"finish_reason\":\"stop\""), sse)
            assertTrue(sse.trimEnd().endsWith("data: [DONE]"), sse)
        }
    }

    // ------------------------------------------------------------------ 多账号登录

    /**
     * 同一 issuer 下的两个不同用户登录后必须得到不同 uid。
     *
     * 回归：从前拿 JWT 的 `iss`（签发方，全站同一个值）当用户 id，于是第二个账号算出的 uid
     * 与第一个相同，账号池按 providerId/uid 去重时后一个 upsert 直接覆盖前一个，
     * 表现为「小浣熊只能登录一个号，登另一个会取代第一个」（issue #5）。
     */
    @Test
    fun `同一 issuer 下的两个账号登录后 uid 不同`() {
        val iss = "https://xiaohuanxiong.com"
        val tokenA = fakeJwt("iss" to iss, "sub" to "user-1001")
        val tokenB = fakeJwt("iss" to iss, "sub" to "user-2002")
        // user_info 故意不回任何用户标识，逼出「JWT 用户声明」这条兵底路径
        withLoginUpstream(
            mapOf("code-a" to tokenA, "code-b" to tokenB),
            infoBody = """{"code":0,"data":{}}""",
        ) { base ->
            val provider = RaccoonProvider(host = base)
            val first = provider.completeWebLogin("http://127.0.0.1/cb?authorization_code=code-a")
            val second = provider.completeWebLogin("http://127.0.0.1/cb?authorization_code=code-b")
            assertNotEquals(first.uid, second.uid, "两个不同账号不能共用 uid，否则后登录的会覆盖前一个")
            assertNotEquals(iss, first.uid, "uid 不能是 issuer")
        }
    }

    @Test
    fun `user_info 回传 userId 时优先用它当 uid`() {
        val token = fakeJwt("iss" to "https://xiaohuanxiong.com", "sub" to "jwt-sub-1")
        withLoginUpstream(
            mapOf("c" to token),
            infoBody = """{"code":0,"data":{"userId":"real-42"}}""",
        ) { base ->
            val acc = RaccoonProvider(host = base).completeWebLogin("http://127.0.0.1/cb?authorization_code=c")
            assertEquals("real-42", acc.uid)
        }
    }

    /** 假上游：登录换 token 按 authorization_code 区分用户，user_info 回固定体。 */
    private fun withLoginUpstream(
        tokensByCode: Map<String, String>,
        infoBody: String,
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(RaccoonProvider.PATH_LOGIN_WITH_CODE) { exchange ->
            val request = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val code = runCatching {
                JsonParser.parseString(request).asJsonObject.get("authorization_code").asString
            }.getOrDefault("")
            respond(exchange, """{"code":0,"data":{"access_token":"${tokensByCode[code].orEmpty()}","refresh_token":"r"}}""")
        }
        server.createContext(RaccoonProvider.PATH_USER_INFO) { exchange ->
            respond(exchange, infoBody)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 造一个未签名 JWT：只要求 payload 能被 Base64 解开（解析不校验签名）。 */
    private fun fakeJwt(vararg claims: Pair<String, String>): String {
        val payload = JsonObject().apply { claims.forEach { (k, v) -> addProperty(k, v) } }.toString()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "eyJhbGciOiJIUzI1NiJ9.$encoded.sig"
    }
}
