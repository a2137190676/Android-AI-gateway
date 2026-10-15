package dev.aigw.core.provider.antigravity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 客户端版本解析。
 *
 * 上游按 `User-Agent` 里的版本决定可用模型，**低于 2.23.0 会被拒绝新模型**
 * （CLIProxyAPI 注释原话：Cloud Code rejects newer models for clients below 2.23.0）。
 * 版本写死会让账号随上游推进逐渐失效，因此改为从官方清单解析，失败时退回兜底值。
 */
class AntigravityVersionTest {

    @Test
    fun `从官方清单解析 version 字段`() {
        val manifest = """
            version: 2.23.1
            files:
              - url: https://storage.googleapis.com/antigravity-public/antigravity-hub/2.23.1-x/darwin-arm/Antigravity.zip
                sha512: abc==
            path: Antigravity.zip
        """.trimIndent()
        assertEquals("2.23.1", AntigravityVersion.parseManifest(manifest))
    }

    @Test
    fun `清单缺少 version 行时返回 null 交给兜底`() {
        assertEquals(null, AntigravityVersion.parseManifest("files:\n  - url: x"))
        assertEquals(null, AntigravityVersion.parseManifest(""))
    }

    @Test
    fun `兜底版本不低于上游下限`() {
        // 低于 2.23.0 会拿不到新模型，兜底值必须 >= 该下限
        val fallback = AntigravityVersion.FALLBACK_VERSION
        assertEquals("2.23.0", fallback)
        assertTrue(fallback >= "2.23.0", fallback)
    }
}
