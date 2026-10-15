package dev.aigw.core.provider.loomy

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Loomy 错误分类。
 *
 * 上游鉴权失败与部分业务错误回的是 **HTTP 200 + 业务码**，不是 4xx；未知模型 id 也会
 * 200 静默回落默认模型。因此分类必须「**先看正文 marker、后看状态码**」，否则失效账号会被
 * 判成普通客户端错误——既不禁用也不冷却，问题被静默吃掉。
 * 参照实现：`multi2api/internal/loomy/errorclassifier.go`、`Patrick130306/loomy2api`。
 */
class LoomyErrorsTest {

    @Test
    fun `200 加业务码也能识别会话失效`() {
        // 这是 Loomy 最典型的形态：HTTP 200，错误藏在业务码里
        assertEquals(
            LoomyErrorKind.SESSION_DEAD,
            LoomyErrors.fromStatus(200, """{"code":"${LoomyConstants.CODE_TOKEN_INVALID}","desc":"缺少 token"}"""),
        )
        assertEquals(
            LoomyErrorKind.SESSION_DEAD,
            LoomyErrors.fromStatus(200, """{"code":"${LoomyConstants.CODE_SESSION_INVALID}"}"""),
        )
    }

    @Test
    fun `正文 marker 优先于状态码`() {
        assertEquals(
            LoomyErrorKind.SESSION_DEAD,
            LoomyErrors.fromStatus(200, """{"message":"登录已失效，请重新登录"}"""),
        )
        assertEquals(
            LoomyErrorKind.SESSION_DEAD,
            LoomyErrors.fromStatus(200, """{"message":"session expired"}"""),
        )
        assertEquals(
            LoomyErrorKind.QUOTA,
            LoomyErrors.fromStatus(200, """{"message":"insufficient credits"}"""),
        )
    }

    @Test
    fun `403 也判会话失效且状态码分支仍生效`() {
        assertEquals(LoomyErrorKind.SESSION_DEAD, LoomyErrors.fromStatus(403, ""))
        assertEquals(LoomyErrorKind.SESSION_DEAD, LoomyErrors.fromStatus(401, ""))
        assertEquals(LoomyErrorKind.NOT_FOUND, LoomyErrors.fromStatus(404, ""))
        assertEquals(LoomyErrorKind.SOFT_RATE, LoomyErrors.fromStatus(429, ""))
        assertEquals(LoomyErrorKind.SERVER, LoomyErrors.fromStatus(500, ""))
        // 无 marker、无非 2xx：仍是客户端错误
        assertEquals(LoomyErrorKind.CLIENT, LoomyErrors.fromStatus(400, "bad request"))
    }
}
