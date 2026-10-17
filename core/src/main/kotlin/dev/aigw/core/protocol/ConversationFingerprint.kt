package dev.aigw.core.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull

/**
 * 会话指纹：由 OpenAI 请求体的**稳定前缀**（system / developer 消息 + 首条 user 消息）
 * 确定性派生一个 UUID，会话尾部（后续多轮）不参与，因此对话增长不改变该值。
 *
 * 为什么必须确定性派生：上游普遍按「会话 id」给提示词缓存分桶，同一段对话每请求换一个 id
 * 就会落到不同桶——长上下文下实测 0% 前缀命中、积分多花约 3 倍（CodeBuddy 的
 * `X-Conversation-ID` 踩过这个坑；参照实现 `simplast/workbuddy-api` 的 `deriveConversationId`）。
 * 所以凡是上游要「会话 id / chatid / sessionId」的地方都必须用本函数，不要用随机 UUID。
 *
 * 请与「每请求必须换新」的 id 区分开：请求 id / 消息 id / traceparent 每请求换新才是对的。
 *
 * 没有可用消息时返回空串，让调用方退回 [FALLBACK_CONVERSATION_ID]（固定值，而不是随机）。
 */
fun stableConversationId(openAiBody: String): String {
    val obj = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull() ?: return ""
    val messages = obj.arrayOrNull("messages") ?: return ""
    val seed = StringBuilder()
    var firstUser: String? = null
    for (element in messages) {
        val message = runCatching { element.asJsonObject }.getOrNull() ?: continue
        val role = message.get("role")?.asString.orEmpty()
        if (role == "system" || role == "developer") {
            seed.append(role).append(':').append(contentTextOf(message)).append('\n')
        } else if (role == "user" && firstUser == null) {
            firstUser = contentTextOf(message)
        }
    }
    if (firstUser != null) seed.append("user:").append(firstUser).append('\n')
    if (seed.isEmpty()) return ""
    return uuidFromDigest(seed.toString())
}

/** 会话指纹算不出来时的固定会话 ID：保持「同会话同值」，绝不用随机值。 */
const val FALLBACK_CONVERSATION_ID = "00000000-0000-0000-0000-000000000000"

/** 把消息 content（字符串或多模态数组）压成纯文本，用于生成会话指纹。 */
private fun contentTextOf(message: JsonObject): String {
    val content = message.get("content") ?: return ""
    if (content.isJsonPrimitive && content.asJsonPrimitive.isString) return content.asString
    val array = content as? com.google.gson.JsonArray ?: return ""
    return array.mapNotNull { part ->
        runCatching { part.asJsonObject.get("text")?.asString }.getOrNull()
    }.joinToString("")
}

/** SHA-256 摘要 → 标准 UUID 字符串（版本位不做修正，只需稳定且唯一）。 */
private fun uuidFromDigest(seed: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
