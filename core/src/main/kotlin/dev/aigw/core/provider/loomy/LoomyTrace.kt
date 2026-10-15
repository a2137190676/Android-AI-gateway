package dev.aigw.core.provider.loomy

import dev.aigw.core.util.randomHex

/**
 * W3C Trace Context 的 `traceparent`。
 *
 * 上游模型服务对缺少该头的请求会一直挂着直到超时（官方客户端源码原话：
 * 「实测 Loomy iModel 缺 traceparent 时 chat/completions 会挂死直到超时」），
 * 所以每个请求都必须带。
 */
object LoomyTrace {

    fun newTraceparent(): String = "00-${randomHex(16)}-${randomHex(8)}-01"

    /** 会话/消息 id 用；与官方客户端的 `chat-<uuid>`、`msg-<uuid>` 形状一致。 */
    fun newUuid(): String = java.util.UUID.randomUUID().toString()
}
