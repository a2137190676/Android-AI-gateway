package dev.aigw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aigw.app.ui.theme.LocalAppColors
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.util.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 趋势图展示天数。 */
private const val TREND_DAYS = 14

/** 首页：数据仪表盘（网关控制台 + 消耗趋势 + 遥测指标 + 快捷操作 + 配额池）。 */
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openProviders: () -> Unit,
    openTaskCenter: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    val lanUrl = state.lanUrls.firstOrNull()

    // Android 13+ 通知权限被拒时前台服务照常跑，但常驻通知会被系统静默隐藏，
    // 用户看起来就是「服务没起来」；启动前先补上授权请求。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startGateway() }

    fun startWithNotificationPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startGateway()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    PageScaffold(
        title = "ai-gateway",
        subtitle = "本地 OpenAI 兼容网关",
        actions = { StatusChip(state.running) },
    ) {
        ConsoleCard(
            state = state,
            lanUrl = lanUrl,
            onStart = { startWithNotificationPermission() },
            onStop = { viewModel.stopGateway() },
            onRefreshCredits = { viewModel.refreshAllCredits() },
            onCopy = { text, message ->
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )

        LiveRoutingTopologyCard(state, Modifier.staggeredAppear(1))

        TelemetryGrid(state, Modifier.staggeredAppear(2))

        // 批量动作进行中/结束：首页也要看得到进度与失败原因（签到按钮就在本页）
        BatchProgressHost(state.batchProgress)
        BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }

        TrendCard(state)

        SectionCard(modifier = Modifier.staggeredAppear(4)) {
            SectionHeader("快捷操作 · ACTIONS")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .clickable { viewModel.checkinAll() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(gold))
                        Text(
                            "批量签到",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.surface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, gold, MaterialTheme.shapes.medium)
                        .clickable { openTaskCenter() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "任务中心",
                        style = MaterialTheme.typography.labelLarge,
                        color = goldText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        AntigravityQuotaSection(state, viewModel, baseIndex = 5)
    }
}

/** 控制台卡：状态行 + 接入地址 + 启停与刷额度。 */
@Composable
private fun ConsoleCard(
    state: AppUiState,
    lanUrl: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshCredits: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(0)) {
        SectionHeader("网关状态 · CORE CONSOLE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state.running)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (state.running) "SERVICE ACTIVE" else "SERVICE STOPPED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "LISTEN PORT",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ":${state.port}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val localUrl = state.localUrl.ifEmpty { "http://127.0.0.1:${state.port}/v1" }
        AddressRow("本机", localUrl) { onCopy(localUrl, "已复制本机地址") }
        AddressRow(
            label = "局域网",
            value = lanUrl ?: "未连接 Wi-Fi（局域网不可用）",
            onCopy = lanUrl?.let { url -> { onCopy(url, "已复制局域网地址") } },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable { if (state.running) onStop() else onStart() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(gold))
                    Text(
                        text = if (state.running) "停止网关服务" else "启动网关服务",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.surface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.medium)
                    .clickable(onClick = onRefreshCredits)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "刷新全部额度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * 实时调度拓扑卡：
 * 1. 标题旁呼吸指示灯（运行中呼吸发光，停止时静止置灰）
 * 2. 拓扑节点（客户端 → 网关中枢 → 上游账号）及流动虚线与光点
 * 3. 网关中枢卡片金色描边与微发光晕
 * 4. 可用账号各供应商分段彩色进度条（平滑过渡，严格说明真实含义）
 * 5. 动态大号指标数字（实时请求数翻滚跳变）
 */
@Composable
private fun LiveRoutingTopologyCard(state: AppUiState, modifier: Modifier = Modifier) {
    val gold = LocalAppColors.current.goldStart
    val goldEnd = LocalAppColors.current.goldEnd
    val isRunning = state.running
    val latestCall = state.calls.firstOrNull()
    val hasTraffic = isRunning && latestCall != null

    // 虚线与流动光斑进度：有活跃请求且网关运行时循环流动，否则静止以极致省电
    val flowPhase = if (hasTraffic) {
        val transition = rememberInfiniteTransition(label = "topologyFlow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 20f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "flowPhase",
        ).value
    } else {
        0f
    }

    SectionCard(modifier = modifier) {
        // 顶部小节头 + 呼吸指示灯
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.breathingDot(
                    color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    size = 7.dp,
                    breathing = isRunning,
                    haloSize = 15.dp,
                ),
            )
            Text(
                text = "实时调度拓扑 · LIVE TOPOLOGY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 右侧今日请求大数字，跳变动效
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                AnimatedContent(
                    targetState = state.today.requests,
                    transitionSpec = {
                        slideInVertically(
                            animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                        ) { it / 2 } + fadeIn() togetherWith
                            slideOutVertically(
                                animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                            ) { -it / 2 } + fadeOut()
                    },
                    label = "todayRequests",
                ) { reqCount ->
                    Text(
                        text = if (reqCount > 0) formatCount(reqCount) else "—",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = if (reqCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 拓扑主体：三节点 + 两条「只在节点间隙里」流动的连线。
        // 连线由布局定位在窄缝里，不按整屏比例画——否则模型名一长，线就会穿过右侧文字。
        val lineColor = MaterialTheme.colorScheme.outlineVariant
        val activeLineColor = if (isRunning) gold.copy(alpha = 0.85f) else lineColor

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧节点：客户端
            TopologyNode(
                title = "CLIENT",
                subtitle = if (isRunning) "127.0.0.1" else "STOPPED",
                modifier = Modifier.weight(1f),
                alignStart = true,
            )

            FlowConnector(
                active = hasTraffic,
                phase = flowPhase,
                color = activeLineColor,
                dotColor = goldEnd,
                modifier = Modifier
                    .weight(0.35f)
                    .height(24.dp),
            )

            // 中间节点：网关中枢（带金色描边与微发光晕）
            GatewayHubNode(
                isRunning = isRunning,
                port = state.port,
                gold = gold,
                goldEnd = goldEnd,
            )

            FlowConnector(
                active = hasTraffic,
                phase = flowPhase,
                color = activeLineColor,
                dotColor = goldEnd,
                modifier = Modifier
                    .weight(0.35f)
                    .height(24.dp),
            )

            // 右侧节点：最新路由目标
            val targetProvider = latestCall?.providerId?.uppercase() ?: "UPSTREAM"
            val targetModel = latestCall?.model?.ifEmpty { null } ?: "—"
            TopologyNode(
                title = targetProvider,
                subtitle = targetModel,
                modifier = Modifier.weight(1f),
                alignStart = false,
            )
        }

        // 分段进度条：各供应商可用账号占比（平滑动画过渡）
        ProviderAccountDistributionBar(state)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 块 2：最近一个请求的路由流水
        RecentCallRoutingFlow(state.calls.firstOrNull())

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 块 3 与 块 4：上下文容量监控 + 明细列表（按模型聚合近 50 次请求）
        ContextCapacityAndModelBreakdown(state)
    }
}

/**
 * 网关中枢节点：运行中时金色描边 + 微发光晕，停止时置灰。
 */
@Composable
private fun GatewayHubNode(isRunning: Boolean, port: Int, gold: Color, goldEnd: Color) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .drawBehind {
                if (isRunning) {
                    drawRoundRect(
                        color = gold.copy(alpha = 0.12f),
                        size = size,
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (isRunning) gold else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "AI-GATEWAY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = if (isRunning) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = ":$port",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (isRunning) goldEnd else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 节点之间的流动连线：虚线 + 沿线移动的光点。
 *
 * 画的是布局分给它的那条窄缝（两端各接一个节点），所以两侧文字长短都不会被线穿过。
 * [active] 为 false 时静止成灰虚线、不发光点，省电也不假装有流量。
 * [phase] 单位是 px，一个循环正好等于虚线周期（段 12px + 隙 8px = 20px），循环处不会跳帧。
 */
@Composable
private fun FlowConnector(
    active: Boolean,
    phase: Float,
    color: Color,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val centerY = size.height / 2f
        drawLine(
            color = color,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), phase),
        )
        if (active) {
            val progress = (phase / 20f).coerceIn(0f, 1f)
            drawCircle(
                color = dotColor,
                radius = 2.5.dp.toPx(),
                center = Offset(size.width * progress, centerY),
            )
        }
    }
}

/** 拓扑端点标签 */
@Composable
private fun TopologyNode(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    alignStart: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.End,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 各供应商可用账号分布条：
 * 数据源严格来自 state.accounts，按 usable 汇总展示真实占比。
 */
@Composable
private fun ProviderAccountDistributionBar(state: AppUiState) {
    val usableAccounts = remember(state.accounts) { state.accounts.filter { it.usable } }
    val totalUsable = usableAccounts.size

    val providerGroups = remember(usableAccounts) {
        usableAccounts
            .groupBy { it.providerId }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
    }

    // 供应商区分色取主题（不要在页面里就地写一份，见 AppColors.providerPalette）
    val palette = LocalAppColors.current.providerPalette

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "各供应商可用账号占比",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (totalUsable > 0) "$totalUsable 个可用" else "暂无可用账号",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 分段进度条实体
        if (totalUsable > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                providerGroups.forEachIndexed { index, (providerId, count) ->
                    val fraction = count.toFloat() / totalUsable
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                        label = "providerBar$providerId",
                    )
                    val color = palette[index % palette.size]
                    Box(
                        modifier = Modifier
                            .weight(animatedFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(color),
                    )
                }
            }

            // 图例一览（按比例取前 4 个展示，避免换行臃肿）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                providerGroups.take(4).forEachIndexed { index, (providerId, count) ->
                    val color = palette[index % palette.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = "${providerId.uppercase()} ($count)",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (providerGroups.size > 4) {
                    Text(
                        text = "+${providerGroups.size - 4}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // 无可用账号时展示占位细条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

/**
 * 块 2：最近一个请求的路由流水。
 * 三行流水（小圆点 + 文案）：
 * 1. 客户端请求 <model>
 * 2. 路由到 <PROVIDER·大写> · <accountNickname 或 accountUid>
 * 3. 结果 <成功/失败/中断> · HTTP <httpStatus> · 耗时 <x.x s> · tokens <in>→<out>
 */
@Composable
private fun RecentCallRoutingFlow(call: CallRecord?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "最近路由记录 · LATEST DISPATCH",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (call == null) {
            Text(
                text = "暂无请求记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@Column
        }

        val dotNeutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        val resultColor = when (call.status) {
            CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
            CallStatus.FAILED -> MaterialTheme.colorScheme.error
            CallStatus.ABORTED -> Color(0xFFC97F0A)
        }
        val resultText = when (call.status) {
            CallStatus.SUCCESS -> "成功"
            CallStatus.FAILED -> "失败"
            CallStatus.ABORTED -> "客户端中断"
        }

        val modelName = call.model.ifEmpty { "未记录" }
        val accountDisplay = call.accountNickname.ifEmpty { call.accountUid.ifEmpty { "默认账号" } }
        val durationSec = String.format(Locale.US, "%.1fs", call.durationMillis / 1000.0)

        // 行 1：客户端请求
        RoutingFlowRow(
            dotColor = dotNeutral,
            label = "客户端请求",
            detail = modelName,
        )

        // 行 2：路由去向
        RoutingFlowRow(
            dotColor = LocalAppColors.current.goldStart,
            label = "路由到",
            detail = "${call.providerId.uppercase()} · $accountDisplay",
        )

        // 行 3：结果与开销
        RoutingFlowRow(
            dotColor = resultColor,
            label = "结果",
            detail = "$resultText · HTTP ${call.httpStatus} · 耗时 $durationSec · tokens ${call.promptTokens}→${call.completionTokens}",
        )
    }
}

@Composable
private fun RoutingFlowRow(dotColor: Color, label: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 单个模型的近 50 次请求聚合统计。 */
private data class ModelTokenAggregation(
    val model: String,
    val requests: Int,
    val promptTokens: Long,
)

/**
 * 在 state.models 里匹配 contextWindow：
 * 1. 用 providerId + model (完全匹配 ProviderModel.id 或 name，并支持剥离 routePrefix)；
 * 2. 退化为任意 provider 下匹配模型名；
 * 3. 0 或未匹配返回 0L (表示未知)。
 */
private fun resolveContextWindow(
    providerId: String,
    modelName: String,
    models: List<RoutedModel>,
): Long {
    if (modelName.isEmpty() || models.isEmpty()) return 0L
    val cleanName = modelName.substringAfterLast('/')

    // 1. 精确匹配同 providerId
    val matched = models.firstOrNull { routed ->
        routed.providerId.equals(providerId, ignoreCase = true) &&
            (routed.model.id.equals(modelName, ignoreCase = true) ||
                routed.model.id.equals(cleanName, ignoreCase = true) ||
                routed.model.name.equals(cleanName, ignoreCase = true) ||
                routed.fullId.equals(modelName, ignoreCase = true))
    }
    if (matched != null && matched.model.contextWindow > 0L) {
        return matched.model.contextWindow
    }

    // 2. 任意 provider 跨域同名匹配
    val fallback = models.firstOrNull { routed ->
        routed.model.id.equals(cleanName, ignoreCase = true) ||
            routed.model.name.equals(cleanName, ignoreCase = true)
    }
    if (fallback != null && fallback.model.contextWindow > 0L) {
        return fallback.model.contextWindow
    }
    return 0L
}

/**
 * 块 3 与 块 4：上下文容量监控 + 明细列表（近 50 次请求按模型聚合）
 */
@Composable
private fun ContextCapacityAndModelBreakdown(state: AppUiState) {
    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val latestCall = recentCalls.firstOrNull()

    // 块 3：大数字计算
    val promptTokens = latestCall?.promptTokens ?: 0L
    val cachedTokens = latestCall?.cachedTokens ?: 0L
    val contextWindow = remember(latestCall, state.models) {
        if (latestCall != null) {
            resolveContextWindow(latestCall.providerId, latestCall.model, state.models)
        } else {
            0L
        }
    }

    // 近 50 次聚合列表（按 promptTokens 倒序）
    val aggregated = remember(recentCalls) {
        recentCalls
            .groupBy { it.model.ifEmpty { "未命名模型" } }
            .map { (model, calls) ->
                ModelTokenAggregation(
                    model = model,
                    requests = calls.size,
                    promptTokens = calls.sumOf { it.promptTokens },
                )
            }
            .sortedByDescending { it.promptTokens }
    }
    val totalPromptTokens = remember(aggregated) { aggregated.sumOf { it.promptTokens } }

    val palette = LocalAppColors.current.providerPalette
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 标题说明
        Text(
            text = "上下文容量与分布 · CONTEXT & MODELS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 块 3：左右双指标
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 左列：输入 tokens / contextWindow
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (latestCall != null) formatTokens(promptTokens) else "—",
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (contextWindow > 0L) " / ${formatTokens(contextWindow)}" else " / —",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp, start = 2.dp),
                    )
                }
                Text(
                    text = if (contextWindow > 0L && contextWindow >= promptTokens) {
                        "剩余 ${formatTokens(contextWindow - promptTokens)}"
                    } else if (contextWindow > 0L) {
                        "已超出上限"
                    } else {
                        "剩余 未知"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 右列：缓存命中率
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                val hitRateText = if (promptTokens > 0L) {
                    String.format(Locale.US, "%.1f%%", (cachedTokens.toDouble() / promptTokens) * 100.0)
                } else {
                    "—"
                }
                Text(
                    text = hitRateText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (cachedTokens > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "缓存命中",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 分段条标题：严格口径
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "近 50 次请求的输入 token 按模型",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (totalPromptTokens > 0L) formatTokens(totalPromptTokens) else "暂无 token 数据",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 分段进度条
        if (totalPromptTokens > 0L) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                aggregated.forEachIndexed { index, item ->
                    val fraction = item.promptTokens.toFloat() / totalPromptTokens
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                        label = "modelTokenBar$index",
                    )
                    val color = palette[index % palette.size]
                    Box(
                        modifier = Modifier
                            .weight(animatedFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(color),
                    )
                }
            }

            // 图例一览（最多 4 项）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                aggregated.take(4).forEachIndexed { index, item ->
                    val color = palette[index % palette.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = "${item.model} (${formatTokens(item.promptTokens)})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (aggregated.size > 4) {
                    Text(
                        text = "+${aggregated.size - 4}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }

        // 块 4：明细列表（可折叠）
        if (aggregated.isNotEmpty()) {
            val visibleCount = if (expanded) aggregated.size else 5
            val displayItems = aggregated.take(visibleCount)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                displayItems.forEachIndexed { index, item ->
                    val color = palette[index % palette.size]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = item.model,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${item.requests} 次",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTokens(item.promptTokens),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 展开/收起按钮
                if (aggregated.size > 5) {
                    Text(
                        text = if (expanded) "收起" else "显示全部 ${aggregated.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.goldText,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 状态圆点：运行中绿色带浅光环，停止灰色。 */
@Composable
private fun StatusDot(running: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)))
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}

/** 一行接入地址：浅灰底 8dp 圆角 + 等宽地址 + 黑色复制小块。 */
@Composable
private fun AddressRow(label: String, value: String, onCopy: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

/** 消耗趋势卡：近 14 天输入/输出 tokens 折线。 */
@Composable
private fun TrendCard(state: AppUiState) {
    val trend = remember(state.daily) { state.daily.takeLast(TREND_DAYS) }
    val drawable = remember(trend) {
        trend.size >= 2 && trend.any { it.promptTokens > 0 || it.completionTokens > 0 }
    }
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(2)) {
        SectionHeader("消耗趋势 · TREND", action = if (trend.isEmpty()) null else "近 ${trend.size} 天")
        if (drawable) {
            TrendChart(trend, startOfDay(System.currentTimeMillis()))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LegendDot(gold, "输入")
                LegendDot(Color(0xFF0D0D0D), "输出")
            }
        } else {
            Text(
                text = "暂无足够历史，累积几天后显示趋势",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendChart(daily: List<UsageStats>, todayStart: Long) {
    val gold = LocalAppColors.current.goldStart
    val dark = Color(0xFF0D0D0D)
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val n = daily.size
    val maxValue = remember(daily) {
        daily.maxOf { maxOf(it.promptTokens, it.completionTokens) }.coerceAtLeast(1L)
    }
    val labels = remember(daily, todayStart) {
        List(n) { index -> dayLabel(todayStart - (n - 1 - index) * 86_400_000L) }
    }

    // 复用同一支画笔：绘制阶段只改对齐方式，不再每次重绘都新建
    val axisPaint = remember { android.graphics.Paint() }
    val inFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].promptTokens.toFloat() / maxValue }
    }
    val outFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].completionTokens.toFloat() / maxValue }
    }
    val xs = remember(n) { FloatArray(n) }
    val inY = remember(n) { FloatArray(n) }
    val outY = remember(n) { FloatArray(n) }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val gutterLeft = 40.dp.toPx()
        val gutterBottom = 22.dp.toPx()
        val padTop = 6.dp.toPx()
        val padRight = 6.dp.toPx()
        val chartWidth = (size.width - gutterLeft - padRight).coerceAtLeast(1f)
        val chartHeight = (size.height - gutterBottom - padTop).coerceAtLeast(1f)
        val originY = padTop + chartHeight
        val stepX = chartWidth / (n - 1)
        for (i in 0 until n) {
            xs[i] = gutterLeft + i * stepX
            inY[i] = originY - inFractions[i] * chartHeight
            outY[i] = originY - outFractions[i] * chartHeight
        }

        drawLine(gridColor, Offset(gutterLeft, originY), Offset(gutterLeft + chartWidth, originY), strokeWidth = 1f)
        drawSeries(xs, inY, originY, gold)
        drawSeries(xs, outY, originY, dark)

        drawIntoCanvas { canvas ->
            axisPaint.isAntiAlias = true
            axisPaint.color = labelColor.toArgb()
            axisPaint.textSize = 9.sp.toPx()
            axisPaint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(formatAxis(maxValue), gutterLeft - 6.dp.toPx(), originY, axisPaint)
            axisPaint.textAlign = android.graphics.Paint.Align.CENTER
            val labelY = originY + 14.dp.toPx()
            for (index in listOf(0, (n - 1) / 2, n - 1).distinct()) {
                canvas.nativeCanvas.drawText(labels[index], xs[index], labelY, axisPaint)
            }
        }
    }
}

/** 一条序列：先画半透明填充，再画主线。 */
private fun DrawScope.drawSeries(xs: FloatArray, ys: FloatArray, baseline: Float, color: Color) {
    if (xs.size < 2) return
    val fillPath = Path().apply {
        moveTo(xs[0], baseline)
        for (i in xs.indices) lineTo(xs[i], ys[i])
        lineTo(xs.last(), baseline)
        close()
    }
    drawPath(fillPath, color = color.copy(alpha = 0.12f))
    val linePath = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) lineTo(xs[i], ys[i])
    }
    drawPath(
        linePath,
        color = color,
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** 2×3 遥测指标网格。 */
@Composable
private fun TelemetryGrid(state: AppUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProvidersTile(state, Modifier.weight(1f))
            ReadinessTile(state, Modifier.weight(1f))
            ModelsTile(state, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RequestsTile(state, Modifier.weight(1f))
            TodayTokensTile(state, Modifier.weight(1f))
            CreditsTile(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .height(112.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        content()
    }
}

/** 大号细体数字 + 小号后缀。 */
@Composable
private fun BigNumber(value: String, suffix: String? = null, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** 指标卡底部灰色小备注。 */
@Composable
private fun TileNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ProvidersTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "供应商 · PROVIDERS") {
        BigNumber(state.providers.size.toString())
        TileNote(
            if (state.providers.isEmpty()) "暂无供应商" else state.providers.joinToString(" · ") { it.displayName },
        )
    }
}

@Composable
private fun ReadinessTile(state: AppUiState, modifier: Modifier) {
    val total = state.pool.total
    val usable = state.pool.usable
    val percent = if (total > 0) (usable.toFloat() / total * 100).toInt() else 0
    Tile(modifier, "账号就绪 · READINESS") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BigNumber(usable.toString(), suffix = "/ $total")
                TileNote("池内可用账号")
            }
            ReadinessRing(percent, if (total > 0) usable.toFloat() / total else 0f)
        }
    }
}

/** 环形进度：金色弧 + 灰底环，中间百分比。 */
@Composable
private fun ReadinessRing(percent: Int, fraction: Float) {
    val ringColor = LocalAppColors.current.goldStart
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val strokeWidth = 4.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(strokeWidth),
            )
            if (fraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "聚合模型 · MODELS") {
        BigNumber(state.models.size.toString())
        TileNote(
            when {
                state.modelsError.isNotEmpty() -> state.modelsError
                state.models.isEmpty() -> "尚未拉取模型目录"
                else -> "各供应商模型目录合计"
            },
        )
    }
}

@Composable
private fun RequestsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日请求 · REQUESTS") {
        BigNumber(formatCount(state.today.requests))
        // 增长需要真实昨日数据：daily 末尾是今天，倒数第二个才是昨天。
        val yesterday = if (state.daily.size >= 2) state.daily[state.daily.size - 2] else null
        if (yesterday != null && yesterday.requests > 0) {
            val delta = (state.today.requests - yesterday.requests).toDouble() / yesterday.requests * 100
            Text(
                text = (if (delta >= 0) "↑ " else "↓ ") + String.format(Locale.US, "%.1f%%", kotlin.math.abs(delta)) + " 较昨日",
                style = MaterialTheme.typography.labelSmall,
                color = if (delta >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TodayTokensTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日 TOKENS · TOKENS") {
        BigNumber(formatTokens(state.today.totalTokens))
        TileNote("输入 ${formatTokens(state.today.promptTokens)} · 输出 ${formatTokens(state.today.completionTokens)}")
    }
}

@Composable
private fun CreditsTile(state: AppUiState, modifier: Modifier) {
    val known = state.pool.creditsKnown > 0
    val goldText = LocalAppColors.current.goldText
    Tile(modifier, "已知额度 · TOTAL CREDITS") {
        BigNumber(
            value = if (known) formatCount(state.pool.totalCredits) else "—",
            color = if (known) goldText else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TileNote(if (known) "已同步 ${state.pool.creditsKnown} 个账号" else "尚未同步额度")
    }
}

/** Antigravity 的模型组配额窗口（每组 × 每窗口一条），首页与账号详情共用。 */
@Composable
private fun AntigravityQuotaSection(state: AppUiState, viewModel: AppViewModel, baseIndex: Int) {
    val accounts = state.accountsOf("antigravity")
    if (accounts.isEmpty()) return

    // 进首页拉一次；账号集合变化时重拉
    LaunchedEffect(accounts.map { it.uid }) {
        accounts.forEach { viewModel.loadCreditPacks("antigravity", it.uid) }
    }

    SectionCard(modifier = Modifier.staggeredAppear(baseIndex)) {
        SectionHeader("上游配额池 · ANTIGRAVITY QUOTA")
        for (account in accounts) {
            val packs = state.creditPacks["antigravity/${account.uid}"].orEmpty()
            if (accounts.size > 1) {
                Text(
                    text = account.nickname.ifEmpty { account.uid },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (packs.isEmpty()) {
                Text(
                    text = if (account.detail.isNotEmpty()) account.detail else "尚未拉取配额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pack in packs) {
                        QuotaPackBar(pack)
                    }
                }
            }
        }
    }
}

/** 坐标轴上限缩写：1.2k / 3.4M。 */
private fun formatAxis(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private val DAY_LABEL_FORMAT = SimpleDateFormat("M/d", Locale.US)

private fun dayLabel(millis: Long): String = DAY_LABEL_FORMAT.format(Date(millis))
