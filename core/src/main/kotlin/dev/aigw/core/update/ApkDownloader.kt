package dev.aigw.core.update

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一次测速结果。[bytesPerSecond] 为测得带宽（低于 1KB 的探测视作失败，记 0）。
 */
data class SpeedProbe(
    val source: MirrorSource,
    val bytesPerSecond: Long,
    val latencyMs: Long,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && bytesPerSecond > 0
}

/** 下载任务的最终落地信息。 */
data class DownloadResult(
    /** 最终使用的线路。 */
    val source: MirrorSource,
    val filePath: String,
    val bytes: Long,
    /** 实际使用的线程数（服务器不支持 Range 时退化为 1）。 */
    val threads: Int,
    val elapsedMs: Long,
) {
    val bytesPerSecond: Long get() = if (elapsedMs <= 0) 0L else bytes * 1_000 / elapsedMs
}

/** 下载进度回调（[total] 未知时为 0）。在下载线程上调用，界面侧自行切主线程。 */
typealias ProgressCallback = (downloaded: Long, total: Long, bytesPerSecond: Long) -> Unit

/**
 * GitHub 下载线路测速。
 *
 * 用 HTTP Range 只取文件头部一小段，既避开「测全量」的流量浪费，又能真实反映到源站/CDN
 * 的带宽。测速与下载共用同一套 URL 改写规则（[MirrorSource.map]），所以测出来快的线路
 * 就是真下载会用的线路。
 *
 * 采样窗口取 1~2 秒：更短会被 TCP 慢启动夸大差异，更长让用户等得不耐烦（4 条线路并行测）。
 * 另有整体截止时间：镜像偶发卡住时（实测某镜像首次请求挂了 18 秒）不能拖住整个测速阶段。
 */
class MirrorSpeedTest(
    private val probeBytes: Int = 512 * 1024,
    private val timeoutMs: Int = 8_000,
    /** 整个测速阶段的上限；超时仍无结果的线路按「超时」判不可用。 */
    private val totalBudgetMs: Long = 3_500,
) {

    /**
     * 并发测量所有线路，按「可用优先、速度快优先」返回。
     *
     * 某条线路失败不影响其它线路；全部失败时返回空列表，由调用方报错。
     */
    fun measure(url: String, sources: List<MirrorSource>): List<SpeedProbe> {
        if (sources.isEmpty()) return emptyList()
        val pool: ExecutorService = Executors.newFixedThreadPool(sources.size) { r ->
            Thread(r, "mirror-probe").apply { isDaemon = true }
        }
        return try {
            val futures = sources.map { source -> pool.submit(Callable { probe(source, url) }) }
            val deadline = System.currentTimeMillis() + totalBudgetMs
            futures.mapIndexed { index, future ->
                val remaining = deadline - System.currentTimeMillis()
                val result = if (remaining > 0) {
                    runCatching { future.get(remaining, TimeUnit.MILLISECONDS) }.getOrNull()
                } else {
                    null
                }
                // 超时/异常的线路也要占位：界面上一行「超时」比线路凭空消失更好懂
                result ?: SpeedProbe(sources[index], 0, 0, "超时")
            }.sortedWith(compareByDescending<SpeedProbe> { it.ok }.thenByDescending { it.bytesPerSecond })
        } finally {
            pool.shutdownNow()
        }
    }

    /** 探测单条线路：`Range: bytes=0-N`，按实际收到的字节与耗时算带宽。 */
    fun probe(source: MirrorSource, url: String): SpeedProbe {
        val startedAt = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            // 镜像本身就是国内入口，绕开用户代理；直连则尊重代理设置
            val opened = if (source.isDirect) {
                URL(source.map(url)).openConnection()
            } else {
                URL(source.map(url)).openConnection(java.net.Proxy.NO_PROXY)
            }
            conn = (opened as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-${probeBytes - 1}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "ai-gateway-android")
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                return SpeedProbe(source, 0, 0, "HTTP $status")
            }
            val firstByteAt = System.currentTimeMillis()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            conn.inputStream.use { input ->
                while (total < probeBytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    // 够一段时间就停：小文件会提前 EOF，用实际字节算带宽
                    if (System.currentTimeMillis() - firstByteAt >= 1_200) break
                }
            }
            val elapsed = (System.currentTimeMillis() - firstByteAt).coerceAtLeast(1)
            val latency = firstByteAt - startedAt
            // 低于 16KB 说明基本没传起来（多为镜像限流/空响应），按不可用处理
            if (total < 16 * 1024) return SpeedProbe(source, 0, latency, "响应过小")
            return SpeedProbe(source, total * 1_000 / elapsed, latency)
        } catch (e: Exception) {
            return SpeedProbe(source, 0, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * 多线程分段下载器。
 *
 * 思路：先 HEAD 拿总长度并确认 `Accept-Ranges: bytes`，再按线路分片用 `Range: bytes=a-b`
 * 并行拉取，各自写进同一个文件的不同偏移。任一段彻底失败（重试用尽）则整单失败——
 * 残缺的 APK 不能交给系统安装，宁可让界面提示换线路重试。
 *
 * 不复用连接池：HttpURLConnection 自带 keep-alive，分段各开一条连接即可。
 */
class ApkDownloader(
    private val threads: Int = THREADS,
    /** 单段最多重试次数（网络抖动/镜像偶发 5xx）。 */
    private val maxAttempts: Int = 3,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 20_000,
) {

    /**
     * 下载 [url] 到 [targetPath]。
     *
     * [onProgress] 会被多个线程并发调用，回调里只做计数与状态更新，不做耗时操作。
     */
    fun download(
        url: String,
        source: MirrorSource,
        targetPath: String,
        onProgress: ProgressCallback? = null,
        isCancelled: () -> Boolean = { false },
    ): DownloadResult {
        val startedAt = System.currentTimeMillis()
        val total = contentLength(source.map(url), source)
        val segments = split(total, threads)
        if (total > 0) {
            java.io.RandomAccessFile(targetPath, "rw").use { it.setLength(total) }
        }
        val progress = DownloadProgress(total, startedAt, onProgress)
        val pool = Executors.newFixedThreadPool(segments.size.coerceAtLeast(1)) { r ->
            Thread(r, "apk-segment").apply { isDaemon = true }
        }
        try {
            // 长度未知时不能按偏移写，退化成从头到尾的单流下载
            val futures = if (total > 0) {
                segments.map { range ->
                    pool.submit(Callable { fetchSegment(url, source, targetPath, range, progress, isCancelled) })
                }
            } else {
                listOf(pool.submit(Callable { fetchWhole(url, source, targetPath, progress, isCancelled) }))
            }
            for (future in futures) future.get()
        } catch (e: Exception) {
            throw (e.cause as? Exception) ?: e
        } finally {
            pool.shutdownNow()
        }
        progress.emit(force = true)
        val bytes = java.io.File(targetPath).length()
        if (total > 0 && bytes != total) throw IllegalStateException("下载不完整（$bytes/$total 字节）")
        return DownloadResult(
            source = source,
            filePath = targetPath,
            bytes = bytes,
            threads = if (total > 0) segments.size else 1,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** 取总长度：先 HEAD，镜像不认 HEAD 时退回 `Range: bytes=0-0` 读 Content-Range。 */
    internal fun contentLength(url: String, source: MirrorSource): Long {
        val head = open(url, direct = !source.isDirect)
        try {
            head.requestMethod = "HEAD"
            val status = head.responseCode
            if (status in 200..299) {
                val length = head.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                if (length > 0) return length
            }
        } catch (_: Exception) {
            // 落到 Range 探测
        } finally {
            head.disconnect()
        }
        val probe = open(url, direct = !source.isDirect).apply {
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            if (probe.responseCode !in 200..299) throw IllegalStateException("HTTP ${probe.responseCode}")
            probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { return it }
            return probe.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
        } finally {
            probe.disconnect()
        }
    }

    /** 总长度未知时的退化路径：一次性写整个文件。 */
    private fun fetchWhole(
        url: String,
        source: MirrorSource,
        targetPath: String,
        progress: DownloadProgress,
        isCancelled: () -> Boolean,
    ) {
        val conn = open(source.map(url), direct = !source.isDirect)
        try {
            if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
            conn.inputStream.use { stream ->
                java.io.File(targetPath).outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw java.io.InterruptedIOException("已取消")
                        val read = stream.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        progress.add(read.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchSegment(
        url: String,
        source: MirrorSource,
        targetPath: String,
        range: LongRange,
        progress: DownloadProgress,
        isCancelled: () -> Boolean,
    ) {
        // 进度按已落盘字节累加，重试从当前 offset 续传，不会重复计数
        var position = range.first
        val end = range.last
        var attempts = 0
        while (position <= end) {
            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
            attempts++
            val conn = open(source.map(url), direct = !source.isDirect).apply {
                setRequestProperty("Range", "bytes=$position-$end")
            }
            try {
                val status = conn.responseCode
                if (status == 416) return
                if (status !in 200..299) throw HttpStatusException(status)
                // 服务器忽略 Range 回整文件时，从中间偏移写入会拼出损坏的 APK，必须拒绝
                val partial = status == 206 || conn.getHeaderField("Content-Range") != null
                if (position > 0 && !partial) throw IllegalStateException("该线路不支持分段下载")
                conn.inputStream.use { stream ->
                    java.io.RandomAccessFile(targetPath, "rw").use { out ->
                        out.seek(position)
                        val buffer = ByteArray(64 * 1024)
                        while (position <= end) {
                            if (isCancelled()) throw java.io.InterruptedIOException("已取消")
                            val want = minOf(buffer.size.toLong(), end - position + 1).toInt()
                            val read = stream.read(buffer, 0, want)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            position += read
                            progress.add(read.toLong())
                        }
                    }
                }
                if (position > end) return
                throw java.io.IOException("段落提前结束 $position/$end")
            } catch (e: Exception) {
                // 4xx（除限流）重试也一样，直接失败，免得白等几轮
                val retryable = e !is HttpStatusException || e.status >= 500 || e.status == 429
                if (!retryable || attempts >= maxAttempts || isCancelled()) throw e
                Thread.sleep(300L * attempts)
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * [direct] 为 true 时绕开全局 [java.net.ProxySelector]：镜像本身就是国内入口，
     * 用户为访问 GitHub 开的代理反而会让它变慢或失败；GitHub 直连则尊重用户代理设置。
     */
    private fun open(url: String, direct: Boolean): HttpURLConnection {
        val connection = if (direct) {
            URL(url).openConnection(java.net.Proxy.NO_PROXY)
        } else {
            URL(url).openConnection()
        }
        return (connection as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // Range 必须落到最终资源；镜像前面通常还有 302
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "ai-gateway-android")
        }
    }

    /** 把 [total] 均分成 [count] 段；总长未知时退化为单段（0..Long.MAX_VALUE）。 */
    internal fun split(total: Long, count: Int): List<LongRange> {
        if (total <= 0) return listOf(0L..Long.MAX_VALUE)
        val n = count.coerceIn(1, 16)
        // 每段至少 256KB，小文件不值得多线程
        val maxSegments = (total / MIN_SEGMENT_BYTES).toInt().coerceAtLeast(1)
        val actual = minOf(n, maxSegments)
        if (actual <= 1) return listOf(0L..(total - 1))
        val chunk = total / actual
        return (0 until actual).map { i ->
            val start = i * chunk
            val end = if (i == actual - 1) total - 1 else (start + chunk - 1)
            start..end
        }
    }

    /** 进度聚合：多线程各自累加，按 200ms 节流回调（避免主线程被高频刷新拖死）。 */
    private class DownloadProgress(
        val total: Long,
        val startedAt: Long,
        val callback: ProgressCallback?,
    ) {
        private val done = java.util.concurrent.atomic.AtomicLong(0)
        private val lastEmit = java.util.concurrent.atomic.AtomicLong(0)
        private val lastBytes = java.util.concurrent.atomic.AtomicLong(0)
        private val lastAt = java.util.concurrent.atomic.AtomicLong(startedAt)

        fun add(bytes: Long) {
            done.addAndGet(bytes)
            emit(force = false)
        }

        fun emit(force: Boolean) {
            val callback = callback ?: return
            val now = System.currentTimeMillis()
            val previous = lastEmit.get()
            if (!force && now - previous < 200) return
            if (!lastEmit.compareAndSet(previous, now) && !force) return
            val current = done.get().coerceAtLeast(0)
            val elapsed = (now - lastAt.get()).coerceAtLeast(1)
            val delta = current - lastBytes.get()
            val speed = if (delta <= 0) 0L else delta * 1_000 / elapsed
            lastBytes.set(current)
            lastAt.set(now)
            callback(current, total, speed)
        }
    }

    companion object {
        /** 默认并发分段数。 */
        const val THREADS = 4

        private const val MIN_SEGMENT_BYTES = 256L * 1024
    }
}

/** 上游返回非 2xx；区分 4xx/5xx 用于决定是否重试。 */
private class HttpStatusException(val status: Int) : java.io.IOException("HTTP $status")
