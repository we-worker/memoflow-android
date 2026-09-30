package com.memoflow.ui

import android.media.MediaPlayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoflow.data.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private val EchoBlue = Color(0xFF3158ED)
private val EchoBg = Color(0xFFF6F8FC)
private val EchoInk = Color(0xFF1C263D)
private val EchoMuted = Color(0xFF7B879D)
private val EchoNavy = Color(0xFF182749)

private enum class EchoSection(val label: String, val icon: ImageVector) {
    Today("今天", Icons.Outlined.Home),
    History("记录", Icons.Outlined.History),
    Voices("声音", Icons.Outlined.GraphicEq),
    Connect("连接", Icons.Outlined.Computer),
    Settings("设置", Icons.Outlined.Settings),
}

@Composable
fun EchoApp(
    viewModel: EchoViewModel,
    recordingActive: Boolean,
    recordingStartedAtMs: Long,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val chunks by viewModel.chunks.collectAsState()
    val rangeCount by viewModel.rangeCount.collectAsState()
    val transcriptCount by viewModel.transcriptCount.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val asrState by viewModel.asrState.collectAsState()

    var section by rememberSaveable { mutableStateOf(EchoSection.Today) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailChunk = chunks.firstOrNull { it.id == detailId }

    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = EchoBlue,
                background = EchoBg,
                surface = Color.White,
                onSurface = EchoInk,
                onBackground = EchoInk,
            ),
    ) {
        Scaffold(
            containerColor = EchoBg,
            bottomBar = {
                if (detailChunk == null) {
                    NavigationBar(containerColor = Color.White) {
                        EchoSection.entries.forEach { item ->
                            NavigationBarItem(
                                selected = section == item,
                                onClick = { section = item },
                                icon = { Icon(item.icon, item.label) },
                                label = { Text(item.label) },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = EchoBlue,
                                        selectedTextColor = EchoBlue,
                                        indicatorColor = Color(0xFFEDF1FF),
                                    ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            if (detailChunk != null) {
                RecordingDetailScreen(
                    chunk = detailChunk,
                    ranges = remember(detailChunk.id) { viewModel.ranges(detailChunk.id) },
                    transcripts = remember(detailChunk.id) { viewModel.transcripts(detailChunk.id) },
                    asrState = asrState,
                    onBack = { detailId = null },
                    onTranscribe = { viewModel.transcribe(detailChunk) },
                    onDelete = { viewModel.deleteChunk(detailChunk) { detailId = null } },
                    modifier = Modifier.padding(padding),
                )
            } else {
                when (section) {
                    EchoSection.Today ->
                        TodayScreen(
                            chunks = chunks,
                            rangeCount = rangeCount,
                            transcriptCount = transcriptCount,
                            recordingActive = recordingActive,
                            recordingStartedAtMs = recordingStartedAtMs,
                            onStartRecording = onStartRecording,
                            onStopRecording = onStopRecording,
                            onOpen = { detailId = it.id },
                            modifier = Modifier.padding(padding),
                        )
                    EchoSection.History ->
                        HistoryScreen(
                            chunks = chunks,
                            onOpen = { detailId = it.id },
                            modifier = Modifier.padding(padding),
                        )
                    EchoSection.Voices ->
                        VoicesScreen(
                            rangeCount = rangeCount,
                            transcriptCount = transcriptCount,
                            modifier = Modifier.padding(padding),
                        )
                    EchoSection.Connect ->
                        ConnectScreen(
                            settings = settings,
                            serverStatus = serverStatus,
                            onSaveBaseUrl = viewModel::saveBaseUrl,
                            onTest = viewModel::testServer,
                            onSyncNow = viewModel::syncNow,
                            modifier = Modifier.padding(padding),
                        )
                    EchoSection.Settings ->
                        SettingsScreen(
                            settings = settings,
                            onWifiOnly = viewModel::setWifiOnly,
                            onAutoResume = viewModel::setAutoResume,
                            onVadThreshold = viewModel::setVadThreshold,
                            modifier = Modifier.padding(padding),
                        )
                }
            }
        }
    }
}

@Composable
private fun Page(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = EchoInk)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = EchoMuted)
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TodayScreen(
    chunks: List<AudioChunkEntity>,
    rangeCount: Int,
    transcriptCount: Int,
    recordingActive: Boolean,
    recordingStartedAtMs: Long,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpen: (AudioChunkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val todayChunks =
        chunks.filter {
            Instant.ofEpochMilli(it.startTimeUtcMs).atZone(ZoneId.systemDefault()).toLocalDate() == today
        }
    val totalDuration = todayChunks.sumOf { it.durationMs }
    val uploaded = todayChunks.count { it.state == "UPLOADED" }

    Page(
        title = "今日记录",
        subtitle = "${formatDate(today)} · 每一段声音，都有迹可循",
        modifier = modifier,
    ) {
        RecordingHero(
            active = recordingActive,
            startedAtMs = recordingStartedAtMs,
            onStart = onStartRecording,
            onStop = onStopRecording,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat("记录", "${todayChunks.size} 段", Modifier.weight(1f))
            SmallStat("时长", formatDuration(totalDuration), Modifier.weight(1f))
            SmallStat("VAD", "$rangeCount 段", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat("已同步", "$uploaded 段", Modifier.weight(1f))
            SmallStat("转写", "$transcriptCount 句", Modifier.weight(1f))
            SmallStat("格式", "M4A", Modifier.weight(1f))
        }

        SectionTitle("今天的时间轴", todayChunks.size)
        if (todayChunks.isEmpty()) {
            EmptyCard("还没有历史录音。开始记录并结束一次录音后，它会出现在这里。")
        } else {
            todayChunks.take(8).forEach { chunk ->
                RecordingRow(chunk = chunk, onClick = { onOpen(chunk) })
            }
        }
    }
}

@Composable
private fun RecordingHero(
    active: Boolean,
    startedAtMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var elapsed by remember(active, startedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(active, startedAtMs) {
        while (active) {
            elapsed =
                if (startedAtMs > 0) {
                    ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
                } else {
                    elapsed + 1
                }
            delay(1000)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = EchoNavy),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Mic, null, tint = Color(0xFFB9CAFF))
                Spacer(Modifier.width(8.dp))
                Text("VAD 持续记录", color = Color(0xFFC8D7FA), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (active) {
                    Surface(color = Color(0xFF243A6B), shape = RoundedCornerShape(30.dp)) {
                        Text(
                            "录音中",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Text(
                if (active) "正在记录今天的声音" else "让记录，随对话开始",
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (active) {
                    "原始音频持续保存；VAD 只标记语音区间，不控制录音开关。"
                } else {
                    "录音以 10 分钟 M4A 分片保存，可在历史记录中回听和转写。"
                },
                color = Color(0xFFA4B4D4),
                fontSize = 13.sp,
            )

            if (active) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    repeat(22) { i ->
                        val h = 8 + ((i * 19 + elapsed.toInt() * 7) % 38)
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(h.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (i % 3 == 0) Color(0xFFB3C7FF) else Color(0xFF7195FF)
                                ),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatClock(elapsed),
                    color = Color.White,
                    fontSize = 28.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = if (active) onStop else onStart,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = if (active) Color(0xFFF8FAFF) else EchoBlue,
                            contentColor = if (active) Color(0xFF284BCB) else Color.White,
                        ),
                ) {
                    Icon(if (active) Icons.Outlined.Stop else Icons.Outlined.Mic, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (active) "结束记录" else "开始记录")
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    chunks: List<AudioChunkEntity>,
    onOpen: (AudioChunkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered =
        chunks.filter {
            query.isBlank() ||
                formatDateTime(it.startTimeUtcMs).contains(query, ignoreCase = true) ||
                it.state.contains(query, ignoreCase = true)
        }

    Page(
        title = "全部记录",
        subtitle = "按时间回到某一段真实录音。",
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索日期、时间或状态") },
            singleLine = true,
        )
        if (filtered.isEmpty()) {
            EmptyCard("没有找到符合条件的录音。")
        } else {
            filtered.forEach { chunk ->
                RecordingRow(chunk, onClick = { onOpen(chunk) })
            }
        }
    }
}

@Composable
private fun RecordingRow(
    chunk: AudioChunkEntity,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateTime(chunk.startTimeUtcMs), color = EchoMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                StateBadge(chunk.state)
            }
            Text("录音记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatDuration(chunk.durationMs)} · ${formatBytes(chunk.fileSize)} · ${chunk.sampleRate / 1000} kHz · ${chunk.bitrate / 1000} kbps",
                color = EchoMuted,
                fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, tint = EchoBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("点击回听、查看 VAD 与转写", color = Color(0xFF65728C), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronRight, null, tint = EchoMuted)
            }
        }
    }
}

@Composable
private fun RecordingDetailScreen(
    chunk: AudioChunkEntity,
    ranges: Flow<List<AudioRangeEntity>>,
    transcripts: Flow<List<TranscriptSegmentEntity>>,
    asrState: AsrUiState,
    onBack: () -> Unit,
    onTranscribe: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rangeList by ranges.collectAsState(initial = emptyList())
    val transcriptList by transcripts.collectAsState(initial = emptyList())
    var tab by rememberSaveable(chunk.id) { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize().background(EchoBg)) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text("录音详情", fontWeight = FontWeight.SemiBold)
                Text(formatDateTime(chunk.startTimeUtcMs), fontSize = 12.sp, color = EchoMuted)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, "删除", tint = Color(0xFFBE4757))
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原始录音", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatDuration(chunk.durationMs)} · ${formatBytes(chunk.fileSize)} · ${chunk.codec.uppercase()} / ${chunk.container.uppercase()}",
                        color = EchoMuted,
                    )
                    StateBadge(chunk.state)
                }
            }

            AudioPlayer(path = chunk.audioPath)

            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("对话原文") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("VAD 区间") })
            }

            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ASR 转写", fontWeight = FontWeight.SemiBold)
                        Text(
                            "由你配置的 PC 服务处理，结果按 chunk + offset 保存。",
                            color = EchoMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Button(
                        onClick = onTranscribe,
                        enabled = asrState !is AsrUiState.Running,
                    ) {
                        if (asrState is AsrUiState.Running && asrState.chunkId == chunk.id) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(Icons.Outlined.CloudUpload, null)
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(if (transcriptList.isEmpty()) "开始转写" else "重新转写")
                    }
                }

                when (val state = asrState) {
                    is AsrUiState.Error ->
                        if (state.chunkId == chunk.id) {
                            AssistChip(
                                onClick = {},
                                label = { Text(state.message) },
                                leadingIcon = { Icon(Icons.Outlined.ErrorOutline, null) },
                            )
                        }
                    is AsrUiState.Done ->
                        if (state.chunkId == chunk.id) {
                            AssistChip(
                                onClick = {},
                                label = { Text("已写入 ${state.count} 段转写") },
                                leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                            )
                        }
                    else -> Unit
                }

                if (transcriptList.isEmpty()) {
                    EmptyCard("暂无转写。配置电脑 ASR 地址后，点击“开始转写”。")
                } else {
                    transcriptList.forEach { seg -> TranscriptCard(seg) }
                }
            } else {
                Text("检测到 ${rangeList.size} 段语音活动", color = EchoMuted, fontSize = 13.sp)
                if (rangeList.isEmpty()) {
                    EmptyCard("这条录音没有已保存的 VAD 区间。")
                } else {
                    rangeList.forEachIndexed { index, range ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(color = Color(0xFFEDF1FF), shape = CircleShape) {
                                    Text(
                                        "${index + 1}",
                                        color = EchoBlue,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "${formatMs(range.startOffsetMs)} → ${formatMs(range.endOffsetMs)}",
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "speech · ${range.modelId ?: "VAD"} ${range.modelVersion ?: ""}",
                                        color = EchoMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptCard(seg: TranscriptSegmentEntity) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(formatMs(seg.startOffsetMs), color = EchoBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("→ ${formatMs(seg.endOffsetMs)}", color = EchoMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(seg.language.orEmpty(), color = EchoMuted, fontSize = 12.sp)
            }
            Text(seg.text, lineHeight = 23.sp)
            Text(
                "${seg.modelId ?: "ASR"} ${seg.modelVersion ?: ""}".trim(),
                color = EchoMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun AudioPlayer(path: String) {
    val file = remember(path) { File(path) }
    var prepared by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var duration by remember(path) { mutableIntStateOf(1) }
    var position by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) { MediaPlayer() }

    DisposableEffect(path) {
        if (file.exists()) {
            runCatching {
                player.setDataSource(path)
                player.prepare()
                duration = player.duration.coerceAtLeast(1)
                prepared = true
                player.setOnCompletionListener {
                    playing = false
                    position = 0
                    it.seekTo(0)
                }
            }
        }
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(250)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFBFE)),
        border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = {
                        if (!prepared) return@FilledIconButton
                        if (playing) player.pause() else player.start()
                        playing = !playing
                    },
                    enabled = prepared,
                ) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                }
                Spacer(Modifier.width(10.dp))
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { position = it.toInt() },
                    onValueChangeFinished = { if (prepared) player.seekTo(position) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (prepared) {
                    "${formatMs(position.toLong())} / ${formatMs(duration.toLong())}"
                } else {
                    "音频文件不可用"
                },
                color = EchoMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 58.dp),
            )
        }
    }
}

@Composable
private fun VoicesScreen(
    rangeCount: Int,
    transcriptCount: Int,
    modifier: Modifier = Modifier,
) {
    Page(
        title = "声音档案",
        subtitle = "V1 先把真实声音数据留好，再接说话人识别。",
        modifier = modifier,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEDF2FF)),
            border = BorderStroke(1.dp, Color(0xFFDCE5FF)),
        ) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        null,
                        tint = EchoBlue,
                        modifier = Modifier.padding(16.dp).size(32.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("声音数据已经开始积累", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("$rangeCount 段 VAD 语音区间 · $transcriptCount 段 ASR 文本", color = Color(0xFF7486A9), fontSize = 13.sp)
                }
            }
        }

        InfoPanel(
            "我的声纹",
            "尚未启用。当前 Demo 不会假装识别“我”和其他人。下一阶段可接 sherpa-onnx Speaker Identification，并沿用现有 chunkId + offsetMs 数据。",
        )
        InfoPanel(
            "说话人分离",
            "尚未启用。PC 端后续可加入 diarization，结果直接关联当前 AudioChunk，不需要重做录音层。",
        )
    }
}

@Composable
private fun ConnectScreen(
    settings: EchoSettings,
    serverStatus: String,
    onSaveBaseUrl: (String) -> Unit,
    onTest: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }

    Page(
        title = "电脑与 MCP",
        subtitle = "手机负责稳定记录，电脑负责 ASR 与后续 AI 处理。",
        modifier = modifier,
    ) {
        InfoPanel(
            "PC Processing Server",
            "填写运行 pc_server 的电脑地址。局域网 Demo 可直接使用，例如 http://192.168.1.10:8787。",
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:8787") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSaveBaseUrl(url); onTest() }) {
                Icon(Icons.Outlined.Cloud, null)
                Spacer(Modifier.width(7.dp))
                Text("保存并检测")
            }
            OutlinedButton(onClick = onSyncNow) {
                Icon(Icons.Outlined.Sync, null)
                Spacer(Modifier.width(7.dp))
                Text("立即同步")
            }
        }
        AssistChip(
            onClick = {},
            label = { Text(serverStatus, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Computer, null) },
        )
        InfoPanel(
            "MCP",
            "当前仓库已保留 PC 处理边界，但 MCP Server 还没有做成发布功能。Demo 不会把网页原型里的 MCP 调用伪装成真实能力。",
        )
    }
}

@Composable
private fun SettingsScreen(
    settings: EchoSettings,
    onWifiOnly: (Boolean) -> Unit,
    onAutoResume: (Boolean) -> Unit,
    onVadThreshold: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Page(
        title = "录音设置",
        subtitle = "按照实际环境调整记录与同步行为。",
        modifier = modifier,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("语音活动检测", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("灵敏度只影响 VAD 标记，不会停止原始音频录制。", color = EchoMuted, fontSize = 13.sp)
                Text(
                    "阈值 ${"%.3f".format(settings.vadThreshold)}",
                    color = EchoBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = settings.vadThreshold,
                    onValueChange = onVadThreshold,
                    valueRange = 0.003f..0.040f,
                )
                Text(
                    "越低越敏感；安静环境可适当降低，嘈杂环境可提高。新设置从下一次录音开始生效。",
                    color = EchoMuted,
                    fontSize = 12.sp,
                )
            }
        }

        SettingSwitch(
            "仅 Wi‑Fi 自动同步",
            "周期同步时只在非计费网络执行；手动“立即同步”仍可使用当前网络。",
            settings.wifiOnly,
            onWifiOnly,
        )
        SettingSwitch(
            "重启后恢复意图",
            "Android 14/15 不允许开机广播直接启动麦克风前台服务，因此会在下次打开应用时恢复录音。",
            settings.autoResume,
            onAutoResume,
        )
        InfoPanel(
            "录音格式",
            "16 kHz · 单声道 · PCM16 → AAC-LC 24 kbps → M4A；每 10 分钟滚动生成一个 chunk。",
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = EchoMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun InfoPanel(title: String, text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(text, color = EchoMuted, lineHeight = 21.sp, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(label, color = EchoMuted, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Surface(color = Color(0xFFE9EDF5), shape = RoundedCornerShape(6.dp)) {
            Text(
                "$count",
                color = Color(0xFF8190A9),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFDBE2EF)),
    ) {
        Text(text, color = EchoMuted, modifier = Modifier.padding(22.dp), lineHeight = 22.sp)
    }
}

@Composable
private fun StateBadge(state: String) {
    val (label, bg, fg) =
        when (state) {
            "UPLOADED" -> Triple("已同步", Color(0xFFEAF6F2), Color(0xFF23846F))
            "FAILED" -> Triple("同步失败", Color(0xFFFFEDEF), Color(0xFFBE4757))
            "UPLOAD_PENDING" -> Triple("同步中", Color(0xFFFFF5E4), Color(0xFFAC7B2C))
            else -> Triple("仅本机", Color(0xFFEDF1FF), EchoBlue)
        }
    Surface(color = bg, shape = RoundedCornerShape(7.dp)) {
        Text(
            label,
            color = fg,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M 月 d 日，EEEE"))

private fun formatDateTime(ms: Long): String =
    Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "$minutes 分 $seconds 秒" else "$seconds 秒"
}

private fun formatClock(seconds: Long): String =
    "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

private fun formatMs(ms: Long): String =
    "%02d:%02d.%03d".format(ms / 60_000, (ms / 1000) % 60, ms % 1000)

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
