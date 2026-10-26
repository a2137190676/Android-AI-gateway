package dev.aigw.core.provider.raccoon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.PointsBill
import dev.aigw.core.provider.PointsBillListView
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.queryParam
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.firstLong
import dev.aigw.core.util.firstString
import dev.aigw.core.util.jwtClaim
import dev.aigw.core.util.jwtClaimLong
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.randomHex
import dev.aigw.core.util.stringOrNull
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

/**
 * 商汤「小浣熊」（`xiaohuanxiong.com`）的插件账号通道。
 *
 * 登录是网页回调：浏览器打开 `/login?appname=Raccoon&redirect=<回调>`，用户登录后重定向回
 * 回调地址并带上 `authorization_code`，再换 access/refresh token。
 *
 * 对话端点 `POST /api/plugin/llm/v1/chat-completions` 虽然字段名接近 OpenAI，但**真正的
 * OpenAI 结构被包在 `data` 里**（外层是 `{"status":{...},"data":{...}}`），且请求体
 * `max_tokens` 保持标准名；响应流带外层包裹，需逐行翻译回标准 OpenAI SSE。
 * 这些差异全部封在本类内部，网关侧零感知。
 */
class RaccoonProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val host: String = HOST,
) : Provider, WebLoginSupport {

    override val id: String = ID
    override val displayName: String = "小浣熊"
    override val authKind: AuthKind = AuthKind.WEBVIEW_CALLBACK
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN, ProviderCapability.POINTS_BILLS)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val credential = account?.let { parse(it) }
        if (credential == null) {
            // 没有账号不算「失败」——登录前本就不该有模型，不能报红吓人。
            return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        }
        return try {
            val models = fetchModels(credential)
            if (models.isEmpty()) {
                ProviderModelCatalogView(emptyList(), fromFallback = false, error = "上游返回空模型列表")
            } else {
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            hooks.onLog("小浣熊拉取模型目录失败：${e.message ?: "未知错误"}")
            ProviderModelCatalogView(emptyList(), fromFallback = false, error = e.message ?: "拉取模型失败")
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return DEFAULT_MODEL
        return model
    }

    override fun hosts(): List<String> = listOf("xiaohuanxiong.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreamingBody(openAiBody)
        val model = resolveModel(requestedModelOf(openAiBody))
        val body = prepareRaccoonBody(openAiBody)

        val conn = try {
            openChatConnection(credential, body)
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        // 优先用 v2（桌面客户端通道，与能用的反代实现一致）；v1 只在 v2 不存在时回退。
        if (status == 404) {
            conn.disconnect()
            val fallback = try {
                open("$host$PATH_CHAT_V1", body, chatHeaders(credential))
            } catch (e: Exception) {
                return FailedChatCall(0, e.message ?: "连接失败")
            }
            val v1Status = try {
                fallback.responseCode
            } catch (e: Exception) {
                fallback.disconnect()
                return FailedChatCall(0, e.message ?: "连接失败")
            }
            return finishChat(fallback, v1Status, streaming, model)
        }
        return finishChat(conn, status, streaming, model)
    }

    /** v2 优先，v2 不存在时回退 v1。 */
    private fun openChatConnection(credential: Credential, body: String): HttpURLConnection =
        open("$host$PATH_CHAT", body, chatHeaders(credential))

    private fun finishChat(
        conn: HttpURLConnection,
        status: Int,
        streaming: Boolean,
        model: String,
    ): ChatCall {
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            val translator = RaccoonSseTranslator(newCompletionId(), model, nowMillis() / 1000)
            val transformed = LineTransformStream(
                source = stream,
                transform = { line -> translator.translate(line) },
                onFinish = { translator.close() },
            )
            if (streaming) {
                return StreamingChatCall(status, transformed) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(transformed, model)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return emptyUpstreamFailure(status, contentType, aggregated)
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        if (text.isBlank()) return emptyUpstreamFailure(status, contentType, text)
        val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return StreamFailureChatCall(
                status,
                UpstreamError(ErrorKind.CLIENT, "上游响应不是合法 JSON（HTTP $status）：${text.trim().take(200)}"),
            )
        val bizCode = parsed.objOrNull("status")?.longOrNull("code") ?: 0L
        if (bizCode != 0L) {
            val message = extractMessage(text).ifEmpty { "上游业务错误（code=$bizCode）" }
            return StreamFailureChatCall(status, UpstreamError(ErrorKind.CLIENT, message))
        }
        val completion = jsonToCompletion(parsed, model) ?: return StreamFailureChatCall(
            status,
            UpstreamError(ErrorKind.CLIENT, "上游响应缺少 choices（HTTP $status）：${text.trim().take(200)}"),
        )
        if (OpenAiSseAggregator.isEmptyCompletion(completion)) {
            return emptyUpstreamFailure(status, contentType, completion)
        }
        if (streaming) {
            return StreamingChatCall(
                status,
                OpenAiSseAggregator.completionAsSse(completion).byteInputStream(StandardCharsets.UTF_8),
            )
        }
        return AggregatedChatCall(status, completion)
    }

    /** 上游 200 但没给出任何内容：保持可检测的失败，不能报成「成功的空回复」。 */
    private fun emptyUpstreamFailure(status: Int, contentType: String, raw: String): ChatCall = StreamFailureChatCall(
        status,
        UpstreamError(
            ErrorKind.CLIENT,
            "上游返回了空响应（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}" +
                if (raw.isBlank()) "）" else "，片段：${raw.trim().take(120)}）",
        ),
    )

    /** 把上游响应里的 choices 包成标准 `chat.completion`。 */
    private fun jsonToCompletion(envelope: JsonObject, model: String): String? {
        val data = envelope.objOrNull("data")
        val rawChoices = data?.arrayOrNull("choices") ?: envelope.arrayOrNull("choices") ?: return null
        // web 通道非流式的 choices[].delta 是字符串，需转成标准 message；OpenAI 风格的 message 直接透传。
        val choices = JsonArray().apply {
            for (element in rawChoices) {
                val choice = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val out = JsonObject()
                out.addProperty("index", choice.longOrNull("index")?.toInt() ?: 0)
                val message = choice.objOrNull("message")
                when {
                    message != null -> out.add("message", message)
                    choice.get("delta")?.isJsonPrimitive == true -> out.add(
                        "message",
                        JsonObject().apply {
                            addProperty("role", "assistant")
                            addProperty("content", choice.get("delta").asString)
                        },
                    )
                    else -> out.add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "") })
                }
                out.addProperty("finish_reason", choice.stringOrNull("finish_reason").orEmpty().ifEmpty { "stop" })
                add(out)
            }
        }
        return JsonObject().apply {
            addProperty("id", data?.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: newCompletionId())
            addProperty("object", "chat.completion")
            addProperty("created", nowMillis() / 1000)
            addProperty("model", model)
            add("choices", choices)
            (data?.objOrNull("usage") ?: envelope.objOrNull("usage"))?.let { add("usage", it) }
        }.toString()
    }

    /** 查询可用积分余额（web 通道）。 */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parse(account) ?: return null
        return try {
            val (status, text) = getJson("$host$PATH_BALANCE", jsonHeaders(credential))
            if (status !in 200..299) return CreditInfo(0, known = false, detail = "上游 HTTP $status")
            val data = envelopeData(text) ?: return CreditInfo(0, known = false, detail = "响应缺少 data")
            // 不能走 firstLong：它把 0 当「未提供」，而积分真的是 0 与字段缺失必须区分——
            // 字段缺失要报 known=false（界面显示「—」），不能显示成「积分 0」。
            val points = BALANCE_POINT_KEYS.firstNotNullOfOrNull { data.longOrNull(it) }
                ?: return CreditInfo(0, known = false, detail = "响应里没有积分字段（键：${data.keySet().joinToString(",")}）")
            CreditInfo(points, known = true, detail = "可用积分 $points")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询积分失败")
        }
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    // ------------------------------------------------------------------ 凭证刷新

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: return null
        val nowSeconds = nowMillis() / 1000
        if (credential.expiresAt > 0 && credential.expiresAt - nowSeconds > skewSeconds) return null
        if (credential.refreshToken.isEmpty()) return null
        // 上游该端点若不可用（404/405/400），安全降级为 null：宁可过期后由 401 触发重新登录，
        // 也不要在这里抛异常或清空凭证误伤账号。
        return runCatching {
            val (status, text) = postJson(
                "$host$PATH_REFRESH",
                JsonObject().apply { addProperty("refresh_token", credential.refreshToken) }.toString(),
                jsonHeaders(credential),
            )
            if (status !in 200..299) return@runCatching null
            val data = envelopeData(text) ?: return@runCatching null
            val access = data.firstString("access_token", "accessToken")
            if (access.isEmpty()) return@runCatching null
            val refreshed = credential.copy(
                accessToken = access,
                refreshToken = data.firstString("refresh_token", "refreshToken").ifEmpty { credential.refreshToken },
                expiresAt = jwtClaimLong(access, "exp") ?: credential.expiresAt,
            )
            toProviderAccount(refreshed, existingUid = account.uid)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 网页登录

    /**
     * 修历史遗留的 uid：早期版本把 JWT 的 `iss`（签发方，同站点所有用户同一个值）当成了用户 id，
     * 于是所有小浣熊账号在池里共用一个 key，后登录的覆盖前一个。
     *
     * 只在**能确定**旧 uid 就是那个 iss、且能从 JWT 算出真实用户标识时才返回新账号；
     * 拿不准一律返回 null，交给用户手动处理，不猜。
     */
    override fun migrateAccount(account: ProviderAccount): ProviderAccount? {
        val credential = parse(account) ?: return null
        val issuer = jwtClaim(credential.accessToken, "iss")
        if (issuer.isEmpty() || account.uid != issuer) return null
        val userId = jwtClaim(credential.accessToken, "user_id", "userId", "uid", "sub")
        if (userId.isEmpty() || userId == issuer) return null
        return toProviderAccount(credential.copy(userId = userId), existingUid = userId)
    }

    override fun beginWebLogin(callbackUrl: String, region: String): WebLoginTicket {
        // 不要带 login_source=desktop：授权页会走 `office-raccoon://auth/callback` 自定义 scheme 分支
        // （手机上没有 App 能接），反而不回 redirect。不传时授权页才把 `?authorization_code=…` 回推到 callbackUrl。
        val loginUrl = "$host$PATH_LOGIN" +
            "?appname=" + enc(APP_NAME) +
            "&redirect=" + enc(callbackUrl)
        return WebLoginTicket(randomHex(16), loginUrl, callbackUrl)
    }

    override fun completeWebLogin(callbackUrl: String): ProviderAccount {
        val code = queryParam(callbackUrl, "authorization_code").orEmpty()
        if (code.isEmpty()) throw IllegalStateException("回调链接缺少 authorization_code")
        val (status, text) = postJson(
            "$host$PATH_LOGIN_WITH_CODE",
            JsonObject().apply { addProperty("authorization_code", code) }.toString(),
            emptyMap(),
        )
        if (status !in 200..299) {
            throw IllegalStateException(extractMessage(text).ifEmpty { "换取凭证失败（HTTP $status）" })
        }
        val data = envelopeData(text) ?: throw IllegalStateException("换取凭证响应异常：${text.take(200)}")
        val accessToken = data.firstString("access_token", "accessToken")
        val refreshToken = data.firstString("refresh_token", "refreshToken")
        if (accessToken.isEmpty()) throw IllegalStateException("换取凭证响应缺少 access_token")

        var nickname = jwtClaim(accessToken, "name")
        var pro = false
        var orgCode = ""
        var userId = ""
        val userInfo = runCatching { fetchUserInfo(accessToken) }.getOrNull()
        if (userInfo != null) {
            nickname = userInfo.firstString("name", "nickname", "nick_name", "username").ifEmpty { nickname }
            pro = userInfo.boolOrNull("pro") == true
            orgCode = firstOrgCode(userInfo)
            userId = userInfo.firstString("userId", "user_id", "uid", "sub")
        }
        // 不能用 JWT 的 iss 当用户 id：iss 是签发方，同站点所有用户同一个值，拿它做账号池主键
        // （key = providerId/uid）会让后登录的账号直接覆盖前一个，表现为「登另一个会取代第一个」。
        // 取法与 CodeBuddy 一致：先 user_info 的用户字段，再退到 JWT 的用户声明。
        if (userId.isEmpty()) {
            userId = jwtClaim(accessToken, "user_id", "userId", "uid", "sub")
        }

        val credential = Credential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = jwtClaimLong(accessToken, "exp") ?: 0L,
            userId = userId,
            nickname = nickname,
            orgCode = orgCode,
        )
        if (pro) hooks.onLog("小浣熊：账号为 Pro，可用 Pro 模型")
        return toProviderAccount(credential)
    }

    /** 粘贴回调链接导入：与网页登录同一条换取链路。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return completeWebLogin(trimmed)
    }

    /** 供应商专属动作（web 通道）。 */
    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    /**
     * 领桌面端登录奖励（`/api/web/desktop/v1/login/points/grant`）。
     *
     * 上游不是「点一下就加积分」的签到：这笔奖励由服务端判定 `data.granted`，
     * 而且只认官方桌面客户端——调用必须带 `X-Client-Platform` / `X-Client-Version`，
     * 否则不发。发成功时积分在 `data.popup.points`（不是 `data.points`）。
     */
    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            val (status, text) = postJson("$host$PATH_GRANT", "{}", jsonHeaders(credential) + desktopHeaders())
            if (status !in 200..299) {
                return ProviderActionResult.failure(extractMessage(text).ifEmpty { "签到失败（HTTP $status）" })
            }
            val data = runCatching { JsonParser.parseString(text).asJsonObject.objOrNull("data") }.getOrNull()
            val granted = data?.boolOrNull("granted") == true
            val points = data?.objOrNull("popup")?.firstLong("points") ?: 0L
            val message = extractMessage(text)
            ProviderActionResult.success(
                when {
                    granted && points > 0 -> "签到成功，+$points 积分"
                    granted -> message.ifEmpty { "签到成功" }
                    message.isNotEmpty() -> friendlyCheckinResult(message)
                    else -> "本次未发放积分（可能今日已领取）"
                },
            )
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    /**
     * 上游回执多是英文短词，直接透出对中文用户不友好；认得的翻成中文，
     * **不认识的原样返回**（不猜、不吞掉上游信息）。
     */
    private fun friendlyCheckinResult(raw: String): String {
        val text = raw.trim()
        return when (text.lowercase()) {
            "success", "ok", "true", "succeeded", "successful" -> "签到成功"
            "already checked in", "already_checkin", "already signed", "already_sign" -> "今日已签到"
            "not checked in", "not_checkin" -> "今日尚未签到"
            else -> text
        }
    }

    /** 积分流水（`/api/web/points/v1/bills`），最近 [POINTS_BILLS_LIMIT] 条。 */
    override fun pointsBills(account: ProviderAccount): PointsBillListView? {
        val credential = parse(account) ?: return null
        val url = "$host$PATH_BILLS?paging.limit=$POINTS_BILLS_LIMIT&paging.offset=0&type=all"
        val (status, text) = getJson(url, jsonHeaders(credential))
        if (status !in 200..299) throw IllegalStateException("积分明细 HTTP $status")
        val data = envelopeData(text) ?: throw IllegalStateException("积分明细响应缺少 data")
        val items = data.arrayOrNull("items") ?: return PointsBillListView(emptyList())
        return PointsBillListView(
            items.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                PointsBill(
                    id = item.firstString("bill_id", "id"),
                    title = item.firstString("event_name", "title", "biz_type").ifEmpty { "积分变动" },
                    // 不能用 firstLong：它把 ≤ 0 当作「未提供」，会把支出（负数）吞成 0。
                    points = item.longOrNull("points") ?: item.longOrNull("amount") ?: 0L,
                    bizType = item.firstString("biz_type"),
                    createdAtMillis = isoMillisOf(item.firstString("created_at", "createdAt")),
                )
            },
        )
    }

    // ------------------------------------------------------------------ 上游

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val (status, text) = getJson("$host$PATH_MODELS", jsonHeaders(credential))
        if (status !in 200..299) throw IllegalStateException("模型接口 HTTP $status")
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型响应不是合法 JSON")
        // 上游目录结构不稳定（data 可能是数组，也可能嵌套在任意键下），递归找「最像模型列表」的数组。
        val array = findModelArray(root)
            ?: throw IllegalStateException("模型响应里找不到模型数组（顶层键：${root.keySet().joinToString(",")}）")
        val result = ArrayList<ProviderModel>()
        val seen = HashSet<String>()
        for (element in array) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val modelId = item.firstString("id", "model", "model_id", "modelId", "name", "code")
            if (modelId.isEmpty() || !seen.add(modelId)) continue
            result.add(
                ProviderModel(
                    id = modelId,
                    name = item.firstString("display_name", "displayName", "title", "name", "model_name")
                        .ifEmpty { modelId },
                    contextWindow = item.firstLong(
                        "context_window", "contextWindow", "max_input_tokens", "maxInputTokens", "max_context",
                    ),
                ),
            )
        }
        return result
    }

    /**
     * 在任意嵌套层级里找「最像模型列表」的数组：元素是对象、且带 id/model/model_id/name 之一。
     * 取命中元素最多的那个数组。
     */
    private fun findModelArray(root: JsonElement): JsonArray? {
        val candidates = ArrayList<JsonArray>()
        fun idOf(obj: JsonObject): String =
            obj.firstString("id", "model", "model_id", "modelId", "name", "code")
        fun walk(element: JsonElement) {
            when {
                element.isJsonArray -> {
                    val arr = element.asJsonArray
                    val looksLikeModels = arr.size() > 0 &&
                        arr.count { it.isJsonObject && idOf(it.asJsonObject).isNotEmpty() } >= arr.size() / 2 + 1
                    if (looksLikeModels) candidates.add(arr)
                    arr.forEach { walk(it) }
                }
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { walk(it.value) }
            }
        }
        walk(root)
        return candidates.maxByOrNull { it.size() }
    }

    private fun fetchUserInfo(accessToken: String): JsonObject? {
        val (status, text) = getJson("$host$PATH_USER_INFO", bearerHeaders(accessToken))
        if (status !in 200..299) return null
        return envelopeData(text)
    }

    private fun firstOrgCode(userInfo: JsonObject): String {
        val orgs = userInfo.arrayOrNull("orgs") ?: userInfo.arrayOrNull("organizations") ?: return ""
        for (element in orgs) {
            val org = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val code = org.firstString("code", "org_code", "orgCode", "id")
            if (code.isNotEmpty()) return code
        }
        return ""
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(url: String, body: String?, headers: Map<String, String>): HttpURLConnection {
        val merged = buildMap {
            put("Accept", "text/event-stream, application/json")
            putAll(headers)
            if (body != null) put("Content-Type", "application/json")
        }
        return HttpSupport.open(
            url = url,
            method = if (body == null) "GET" else "POST",
            headers = merged,
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
    }

    private fun postJson(url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = open(url, body, headers)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = open(url, null, headers)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(bearerHeaders(credential.accessToken))
        put("User-Agent", CLIENT_UA)
        put("Accept-Language", "zh-Hans")
        if (credential.orgCode.isNotEmpty()) put("X-Org-Code", credential.orgCode)
    }

    /**
     * 桌面客户端标识头。上游靠它判定「调用方是官方桌面端」，缺了就不发登录奖励。
     */
    private fun desktopHeaders(): Map<String, String> = mapOf(
        "X-Client-Platform" to CLIENT_PLATFORM,
        "X-Client-Version" to CLIENT_VERSION,
    )

    /** 上游 `created_at` 是 ISO-8601 字符串，转毫秒；解析不了就当未知（0）。 */
    private fun isoMillisOf(value: String): Long = runCatching {
        OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun bearerHeaders(accessToken: String): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "Bearer $accessToken",
    )

    /** 对话请求头。web 通道需要伪装成官方桌面客户端。 */
    private fun chatHeaders(credential: Credential): Map<String, String> = buildMap {
        put("Accept", "text/event-stream, application/json")
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${credential.accessToken}")
        put("User-Agent", CLIENT_UA)
        put("Accept-Language", "zh-Hans")
        if (credential.orgCode.isNotEmpty()) put("X-Org-Code", credential.orgCode)
    }

    // ------------------------------------------------------------------ 工具

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val access = obj.firstString("accessToken", "access_token")
        if (access.isEmpty()) return null
        return Credential(
            accessToken = access,
            refreshToken = obj.firstString("refreshToken", "refresh_token"),
            expiresAt = obj.firstLong("expiresAt", "expires_at"),
            userId = obj.firstString("userId", "user_id", "uid"),
            nickname = obj.firstString("nickname", "nickName", "name"),
            orgCode = obj.firstString("orgCode", "org_code"),
        )
    }

    private fun toProviderAccount(credential: Credential, existingUid: String = ""): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("userId", credential.userId)
            addProperty("nickname", credential.nickname)
            if (credential.orgCode.isNotEmpty()) addProperty("orgCode", credential.orgCode)
        }.toString()
        val uid = existingUid.ifEmpty { credential.userId }.ifEmpty {
            "raccoon-" + credential.accessToken.hashCode().toUInt().toString(16)
        }
        return ProviderAccount(id, uid, credential.nickname, secret)
    }

    private fun envelopeData(body: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        return obj.objOrNull("data")
    }

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.stringOrNull("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        for (key in listOf("message", "msg", "details", "detail")) {
            obj.stringOrNull(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val nickname: String,
        val orgCode: String,
    )

    companion object {
        const val ID = "raccoon"

        const val HOST = "https://xiaohuanxiong.com"

        const val APP_NAME = "Raccoon"

        // ---- web 通道（官网/桌面客户端在用）----
        const val PATH_LOGIN = "/code/authorize"
        const val PATH_LOGIN_WITH_CODE = "/api/web/auth/v1/login_with_authorization_code"
        const val PATH_USER_INFO = "/api/web/auth/v1/user_info"
        const val PATH_REFRESH = "/api/web/auth/v1/refresh"
        const val PATH_CHAT = "/api/web/llm/v2/chat/completions"
        const val PATH_CHAT_V1 = "/api/web/llm/v1/chat/completions"
        const val PATH_MODELS = "/api/web/llm/v2/model_catalog"
        const val PATH_BALANCE = "/api/web/points/v1/balance"

        /** 余额响应里可能放可用积分的键（按优先级）。字段缺失与「真是 0」必须区分。 */
        private val BALANCE_POINT_KEYS =
            listOf("available_points", "availablePoints", "points", "balance", "credits")

        /** 桌面端登录奖励（不是每日签到）。 */
        const val PATH_GRANT = "/api/web/desktop/v1/login/points/grant"

        /** 积分流水明细。 */
        const val PATH_BILLS = "/api/web/points/v1/bills"

        /** 桌面客户端标识：上游只给「官方桌面端」发登录奖励，伪装成 Windows 桌面端（与 [CLIENT_UA] 一致）。 */
        const val CLIENT_PLATFORM = "desktop-windows"

        const val CLIENT_VERSION = "v1.0.38"

        /** 积分明细一次拉取的条数（官方客户端用 20）。 */
        const val POINTS_BILLS_LIMIT = 20

        /** 伪装成官方桌面 Web 客户端的 UA（web 通道的调用方就是它）。 */
        const val CLIENT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** 对话默认停止符，与官方 Web 前端一致。 */
        const val DEFAULT_STOP = "<|endofmessage|>"

        /** 默认模型（官网标注为「默认」的 Raccoon Chat）。 */
        const val DEFAULT_MODEL = "raccoon-chat-ml-5-5"

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}

/**
 * 把标准 OpenAI 请求体改写为小浣熊上游格式。
 *
 * - 字段名保持 OpenAI 标准（上游 v2 是 LiteLLM 兼容层，`max_tokens` 等标准名直接认；
 *   改名成 `max_new_tokens` 会被上游以 500 unexpected keyword argument 拒绝）；
 * - 保留 `max_tokens`/`temperature`/`top_p`/`frequency_penalty`/`presence_penalty`/`stop`；
 *   新版 OpenAI 客户端的 `max_completion_tokens` 按同义映射到 `max_tokens`，否则会被静默丢弃；
 * - **强制 `stream:true`**（上游拒绝非流式），非流式由本类聚合；
 * - `tools` 原样映射，并带上 `tool_choice:"auto"`。
 *
 * 上游只接受上述字段，其它字段（`n`/`user`/`response_format` 等）一律丢弃。
 */
internal fun prepareRaccoonBody(src: String): String {
    val obj = runCatching { JsonParser.parseString(src).asJsonObject }.getOrNull() ?: return src
    return JsonObject().apply {
        obj.stringOrNull("model")?.let { addProperty("model", it) }
        obj.arrayOrNull("messages")?.let { add("messages", it) }
        // 新版 OpenAI 客户端发 max_completion_tokens：按同义映射，否则会被上游静默丢弃
        (obj.longOrNull("max_tokens") ?: obj.longOrNull("max_completion_tokens"))
            ?.let { addProperty("max_tokens", it) }
        obj.doubleOrNull("temperature")?.let { addProperty("temperature", it) }
        obj.doubleOrNull("top_p")?.let { addProperty("top_p", it) }
        obj.doubleOrNull("frequency_penalty")?.let { addProperty("frequency_penalty", it) }
        obj.doubleOrNull("presence_penalty")?.let { addProperty("presence_penalty", it) }
        addProperty("n", 1)
        addProperty("stream", true)
        // 官方 Web 前端固定用这个停止符；客户端没传时补上，传了则尊重客户端
        val stop = obj.get("stop")?.takeIf { !it.isJsonNull }
        if (stop != null) add("stop", stop) else addProperty("stop", RaccoonProvider.DEFAULT_STOP)
        obj.arrayOrNull("tools")?.let { tools ->
            add("tools", tools)
            addProperty("tool_choice", "auto")
        }
    }.toString()
}

/**
 * 小浣熊上游 SSE → 标准 OpenAI SSE 的逐行翻译器。
 *
 * 上游每行形如 `data: {"status":{"code":0},"data":{"choices":[...]}}`，真正的 OpenAI
 * 结构在 `data` 里；以 `data: [DONE]` 结束，另有 `: ping` 心跳行需忽略。逐行转换，
 * 返回值即待写出的 SSE 文本，便于单测断言。
 */
internal class RaccoonSseTranslator(
    private val id: String,
    private val model: String,
    private val created: Long,
) {
    private var done = false
    private var frameId = id

    fun translate(rawLine: String): List<String> {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith(":")) return emptyList()
        if (!line.startsWith("data:")) return emptyList()
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") {
            done = true
            return listOf(SSE_DONE)
        }
        val envelope = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return emptyList()
        val status = envelope.objOrNull("status")
        val code = status?.longOrNull("code") ?: 0L
        if (code != 0L) {
            done = true
            val message = status?.stringOrNull("message").orEmpty().ifEmpty { "上游流内错误（code=$code）" }
            return listOf(errorFrame(code, message), SSE_DONE)
        }
        // 上游流式帧带外层包裹（`{"status":..,"data":{...}}`）；也兼容直接是 OpenAI chunk 的情形。
        val data = envelope.objOrNull("data") ?: envelope
        envelope.objOrNull("data")?.stringOrNull("id")?.let { frameId = it }
        val choices = data.arrayOrNull("choices") ?: return emptyList()
        val out = ArrayList<String>(1)
        for (element in choices) {
            val choice = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            // web 通道的 delta 是**字符串**（正文片段）；OpenAI 风格的 delta 是对象，两者都兼容。
            val deltaElement = choice.get("delta")
            val delta: JsonObject = when {
                deltaElement == null || deltaElement.isJsonNull -> JsonObject()
                deltaElement.isJsonObject -> deltaElement.asJsonObject
                deltaElement.isJsonPrimitive -> JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", deltaElement.asString)
                }
                else -> JsonObject()
            }
            val finishReason = choice.stringOrNull("finish_reason")
            if (delta.size() == 0 && finishReason == null) continue
            out.add(chunk(choice.longOrNull("index")?.toInt() ?: 0, delta, finishReason))
        }
        return out
    }

    fun close(): List<String> = if (done) emptyList() else listOf(SSE_DONE)

    private fun chunk(index: Int, delta: JsonObject, finishReason: String?): String {
        val choice = JsonObject().apply {
            addProperty("index", index)
            add("delta", delta)
            if (finishReason != null) addProperty("finish_reason", finishReason)
        }
        val obj = JsonObject().apply {
            addProperty("id", frameId)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        return "data: $obj\n\n"
    }

    /** 用 OpenAI 标准错误帧（顶层 error 对象）：网关的 parseChunk 能识别它并把调用记为失败。 */
    private fun errorFrame(code: Long, message: String): String {
        val obj = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
                addProperty("type", ErrorKind.CLIENT.name)
            })
        }
        return "data: $obj\n\n"
    }
}

private fun JsonObject.doubleOrNull(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asDouble
