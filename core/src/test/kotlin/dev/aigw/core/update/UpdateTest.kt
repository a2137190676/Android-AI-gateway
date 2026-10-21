package dev.aigw.core.update

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Release 解析与版本比较。 */
class UpdateRepositoryTest {

    private val releaseJson = """
        {
          "tag_name": "v2.13.0",
          "html_url": "https://github.com/xibai6668/Android-AI-gateway/releases/tag/v2.13.0",
          "body": "## 更新内容\n- 多线程下载",
          "assets": [
            {"name": "ai-gateway-2.13.0-release.apk", "size": 1657350,
             "browser_download_url": "https://github.com/xibai6668/Android-AI-gateway/releases/download/v2.13.0/ai-gateway-2.13.0-release.apk"},
            {"name": "checksums.txt", "size": 120,
             "browser_download_url": "https://github.com/xibai6668/Android-AI-gateway/releases/download/v2.13.0/checksums.txt"}
          ]
        }
    """.trimIndent()

    @Test
    fun `解析出 tag、更新日志与 APK 资产`() {
        val info = UpdateRepository.parse(releaseJson)
        assertEquals("2.13.0", info.version)
        assertEquals(1_657_350L, info.apkSize)
        assertEquals(
            "https://github.com/xibai6668/Android-AI-gateway/releases/download/v2.13.0/ai-gateway-2.13.0-release.apk",
            info.apkUrl,
        )
        assertTrue(info.notes.contains("多线程下载"))
    }

    @Test
    fun `没有 APK 资产时 apkUrl 为空`() {
        val info = UpdateRepository.parse(
            """{"tag_name":"v2.13.0","html_url":"https://example.com","body":"","assets":[]}""",
        )
        assertEquals(null, info.apkUrl)
        assertEquals(0L, info.apkSize)
    }

    @Test
    fun `版本比较按段进行`() {
        val info = UpdateRepository.parse(releaseJson)
        assertTrue(info.isNewerThan("2.12.1"))
        assertTrue(info.isNewerThan("2.12"))
        assertTrue(!info.isNewerThan("2.13.0"))
        assertTrue(!info.isNewerThan("2.13.1"))
        assertTrue(!info.isNewerThan("3.0"))
    }

    @Test
    fun `镜像改写只在前缀非空时生效`() {
        val url = "https://github.com/a/b.apk"
        assertEquals(url, UpdateSources.DOWNLOAD.first().map(url))
        assertEquals("https://ghfast.top/$url", UpdateSources.DOWNLOAD.first { it.id == "ghfast" }.map(url))
    }
}

/** 分段切分与多线程下载（本地起一个支持 Range 的 HTTP 服务，不依赖外网）。 */
class ApkDownloaderTest {

    private val downloader = ApkDownloader(threads = 4, maxAttempts = 2)

    /** 1.5MB 伪随机字节，用于校验分段落盘是否严丝合缝。 */
    private val payload = ByteArray(1_500_000) { (it * 31 % 251).toByte() }

    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        var rangeRequests = 0
        override fun serve(session: IHTTPSession): Response {
            if (session.uri.contains("missing")) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
            if (session.method == Method.HEAD) {
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/octet-stream",
                    ByteArrayInputStream(ByteArray(0)),
                    payload.size.toLong(),
                )
                return response
            }
            val range = session.headers["range"]
            if (range != null) rangeRequests++
            val total = payload.size.toLong()
            if (range == null || !range.startsWith("bytes=")) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/octet-stream",
                    ByteArrayInputStream(payload),
                    total,
                )
            }
            val spec = range.removePrefix("bytes=")
            val from = spec.substringBefore('-').toLong()
            val to = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
            val body = payload.copyOfRange(from.toInt(), (to + 1).toInt())
            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                "application/octet-stream",
                ByteArrayInputStream(body),
                body.size.toLong(),
            )
            response.addHeader("Content-Range", "bytes $from-$to/$total")
            return response
        }
    }

    @AfterTest
    fun stopServer() {
        server.stop()
    }

    private fun start(): String {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        return "http://127.0.0.1:${server.listeningPort}/app.apk"
    }

    @Test
    fun `分段切分覆盖全文件且不重叠`() {
        val segments = downloader.split(4_000_000, 4)
        assertEquals(4, segments.size)
        assertEquals(0L, segments.first().first)
        assertEquals(3_999_999L, segments.last().last)
        segments.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun `小于阈值时只分一段`() {
        assertEquals(1, downloader.split(200_000, 4).size)
    }

    @Test
    fun `多线程分段落盘内容与源文件一致`() {
        val url = start()
        val target = File.createTempFile("aigw-apk", ".bin").apply { deleteOnExit() }
        val progress = mutableListOf<Long>()
        val result = downloader.download(
            url = url,
            source = MirrorSource("direct", "直连", ""),
            targetPath = target.path,
            onProgress = { done, _, _ -> synchronized(progress) { progress += done } },
        )
        assertEquals(payload.size.toLong(), result.bytes)
        assertEquals(4, result.threads)
        assertTrue(server.rangeRequests >= 4, "应发起多段 Range 请求，实际 ${server.rangeRequests}")
        assertContentEquals(payload, target.readBytes())
        assertNotNull(progress.lastOrNull())
    }

    @Test
    fun `线路返回 404 时抛出而不是静默成功`() {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val target = File.createTempFile("aigw-apk", ".bin").apply { deleteOnExit() }
        val thrown = runCatching {
            downloader.download(
                url = "http://127.0.0.1:${server.listeningPort}/missing.apk",
                source = MirrorSource("direct", "直连", ""),
                targetPath = target.path,
            )
        }.exceptionOrNull()
        assertNotNull(thrown)
    }
}

/** 测速：失败线路记错误，可用线路按带宽排序。 */
class MirrorSpeedTestProbeTest {

    @Test
    fun `全部线路不可达时返回失败结果而不是抛异常`() {
        val probes = MirrorSpeedTest(probeBytes = 4096, timeoutMs = 800).measure(
            url = "http://127.0.0.1:1/app.apk",
            sources = listOf(
                MirrorSource("direct", "直连", ""),
                MirrorSource("mirror", "镜像", "http://127.0.0.1:2/"),
            ),
        )
        assertEquals(2, probes.size)
        assertTrue(probes.none { it.ok })
    }
}
