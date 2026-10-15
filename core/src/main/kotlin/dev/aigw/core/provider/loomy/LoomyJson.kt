package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * 确保流式请求带 `stream_options.include_usage = true`。
 *
 * 上游只有收到该标志时，SSE 末帧才回 `usage`；缺了它用量统计会缺。
 * 客户端已传 `stream_options` 时只合并 `include_usage`，不覆盖其它字段；
 * 请求体不是合法 JSON 时原样返回（不因为一次改写失败而拆掉整个请求）。
 */
internal fun withIncludeUsage(body: String): String {
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
    val existing = obj.get("stream_options") as? JsonObject
    if (existing != null && existing.get("include_usage") != null) return body
    val options = existing ?: JsonObject().also { obj.add("stream_options", it) }
    options.addProperty("include_usage", true)
    return obj.toString()
}

/**
 * 解析上游的统一响应壳 `{code, desc, data, trace_id}`。
 *
 * 业务码非 `000000` 时抛 [LoomyApiException]（携带 code 供上层分类），
 * 这样调用方只需要处理一种异常类型。
 */
internal fun decodeEnvelope(status: Int, body: String): JsonObject {
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        ?: throw LoomyApiException(status, LoomyErrors.fromStatus(status, body), body)
    val code = obj.str("code")
    if (code.isNotEmpty() && code != LoomyConstants.CODE_OK) {
        throw LoomyApiException(status, LoomyErrors.fromStatus(status, body), body, code)
    }
    return obj
}
