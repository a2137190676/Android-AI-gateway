package dev.aigw.app.ui

import java.util.Locale

/** 千分位计数（如 1,234,567）。 */
fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

/** 人类友好缩写：3.82M / 276.6K。 */
fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

/** 字节数：1.58 MB / 812 KB。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 带宽：3.2 MB/s。 */
fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> "—"
    bytesPerSecond >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)
    bytesPerSecond >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}
