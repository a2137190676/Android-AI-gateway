package dev.aigw.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.core.update.SpeedProbe
import dev.aigw.core.update.UpdateSources
import java.util.Locale

/** 应用内更新页：线路测速、下载进度与安装。 */
@Composable
fun UpdateScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val info = state.updateInfo
    val download = state.updateDownload
    val busy = download is UpdateDownload.Probing || download is UpdateDownload.Downloading
    // 已测速的结果优先按带宽排序；还没测过时列出内置线路，供手动指定
    val probes = state.updateProbes.takeIf { it.isNotEmpty() }
    var showNotes by remember { mutableStateOf(true) }

    PageScaffold(
        title = "应用更新",
        subtitle = "多线路测速 · 多线程下载",
        leading = { BackButton(onBack) },
    ) {
        SectionCard {
            SectionLabel("版本")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VersionCell("当前", viewModel.installedVersionName(), Modifier.weight(1f))
                VersionCell("最新", info?.version ?: "—", Modifier.weight(1f))
            }
            if (info == null) {
                Text(
                    text = "尚未检查到新版本。点下方「检查更新」从 GitHub Releases 获取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (info != null) {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showNotes = !showNotes },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionLabel("更新内容")
                    Text(
                        text = if (showNotes) "收起" else "展开",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalAppColors.current.goldText,
                    )
                }
                if (showNotes) {
                    Text(
                        text = info.notes.ifEmpty { "该版本没有提供更新说明。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                SectionLabel("下载线路 · MIRROR SPEED")
                Text(
                    text = if (probes == null) {
                        "开始更新后并行测速 GitHub 直连与国内镜像，自动使用最快的一条。"
                    } else {
                        "已测量 ${probes.size} 条线路，点击任一条可指定使用。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (probes != null) {
                    probes.forEach { probe ->
                        ProbeRow(probe) { viewModel.downloadUpdateFrom(info, probe.source) }
                    }
                } else {
                    UpdateSources.DOWNLOAD.forEach { source ->
                        ProbeRow(SpeedProbe(source, 0, 0, error = "未测速"), null)
                    }
                }
            }

            SectionCard {
                when (download) {
                    is UpdateDownload.Probing -> {
                        SectionLabel("准备下载")
                        Text(
                            text = "正在测速…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateDownload.Downloading -> {
                        SectionLabel("下载中 · ${download.source}")
                        Text(
                            text = "${percentText(download.downloaded, download.total)} · " +
                                "${formatBytes(download.downloaded)} / ${formatBytes(download.total)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        LinearProgressIndicator(
                            progress = { ratio(download.downloaded, download.total) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Text(
                            text = "速度 ${formatSpeed(download.bytesPerSecond)} · ${download.threads} 线程并发",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateDownload.Ready -> {
                        SectionLabel("下载完成")
                        Text(
                            text = "来自 ${download.source}，已校验安装包。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    is UpdateDownload.Failed -> {
                        SectionLabel("下载失败")
                        Text(
                            text = download.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    null -> {
                        SectionLabel("安装包")
                        Text(
                            text = if (info.apkUrl == null) {
                                "该版本没有附带 APK，只能前往发布页手动下载。"
                            } else {
                                "APK ${formatBytes(info.apkSize)}，支持断点续传与多线程加速。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when (download) {
                    is UpdateDownload.Ready -> {
                        OutlineActionButton("立即安装") { viewModel.installUpdate() }
                        Text(
                            text = "首次安装需在系统设置里允许本应用安装未知来源应用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { viewModel.installUpdate() },
                        )
                    }
                    is UpdateDownload.Failed -> {
                        OutlineActionButton("重试（自动选最快线路）") { viewModel.downloadUpdate(info) }
                        Text(
                            text = "前往发布页手动下载",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalAppColors.current.goldText,
                            modifier = Modifier.clickable { viewModel.openReleasePage(info.url) },
                        )
                    }
                    is UpdateDownload.Probing, is UpdateDownload.Downloading -> {
                        OutlineActionButton("取消下载") { viewModel.cancelUpdateDownload() }
                    }
                    null -> {
                        OutlineActionButton(
                            text = if (info.apkUrl == null) "打开发布页" else "测速并下载",
                        ) {
                            if (info.apkUrl == null) viewModel.openReleasePage(info.url) else viewModel.downloadUpdate(info)
                        }
                    }
                }
            }
        } else {
            SectionCard {
                OutlineActionButton(if (state.updateChecking) "检查中…" else "检查更新") {
                    if (!state.updateChecking) viewModel.checkUpdate()
                }
            }
        }

        if (busy) {
            Text(
                text = "下载在应用内进行，可保持本页或切到后台；中断后重试会重新测速选线。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VersionCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifEmpty { "—" },
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 一条线路：名称 + 实测带宽/失败原因；[onPick] 非空时可点击指定该线路下载。 */
@Composable
private fun ProbeRow(probe: SpeedProbe, onPick: (() -> Unit)?) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onPick != null) it.clickable(onClick = onPick) else it }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = probe.source.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    probe.ok -> formatSpeed(probe.bytesPerSecond)
                    probe.error != null -> probe.error.orEmpty()
                    else -> "不可用"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (probe.ok) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun ratio(done: Long, total: Long): Float =
    if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

private fun percentText(done: Long, total: Long): String =
    if (total <= 0) "—" else String.format(Locale.US, "%.1f%%", done * 100.0 / total)

/** 字节数：1.58 MB / 812 KB。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 带宽：3.2 MB/s。 */
internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> "—"
    bytesPerSecond >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)
    bytesPerSecond >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}
