package com.memoflow.ui

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoflow.asr.*
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
    val asrSettings by viewModel.asrSettings.collectAsState()
    val localModels by viewModel.localModels.collectAsState()

    var section by rememberSaveable { mutableStateOf(EchoSection.Today) }
    val sectionHistory = remember { mutableStateListOf<EchoSection>() }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailChunk = chunks.firstOrNull { it.id == detailId }

    fun navigateSection(target: EchoSection) {
        if (target == section) return
        sectionHistory += section
        section = target
    }

    BackHandler(
        enabled = detailChunk != null || sectionHistory.isNotEmpty() || section != EchoSection.Today,
    ) {
        if (detailChunk != null) {
            detailId = null
        } else if (sectionHistory.isNotEmpty()) {
            section = sectionHistory.removeAt(sectionHistory.lastIndex)
        } else {
            section = EchoSection.Today
        }
    }

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
                                onClick = { navigateSection(item) },
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
                LaunchedEffect(detailChunk.id, detailChunk.postProcessState) {
                    viewModel.ensurePostProcessed(detailChunk)
                }

                RecordingDetailScreen(
                    chunk = detailChunk,
                    ranges = remember(detailChunk.id) { viewModel.ranges(detailChunk.id) },
                    transcripts = remember(detailChunk.id) { viewModel.transcripts(detailChunk.id) },
                    asrState = asrState,
                    loadWaveform = { viewModel.loadWaveform(detailChunk.waveformPath) },
                    onBack = { detailId = null },
                    onTranscribe = { viewModel.transcribe(detailChunk) },
                    onDeleteOriginal = { viewModel.deleteOriginal(detailChunk) },
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
                            onSaveApiKey = viewModel::saveApiKey,
                            onTest = viewModel::testServer,
                            onSyncNow = viewModel::syncNow,
                            modifier = Modifier.padding(padding),
                        )
                    EchoSection.Settings ->
                        SettingsScreen(
                            settings = settings,
                            asrSettings = asrSettings,
                            localModels = localModels,
                            chunks = chunks,
                            onWifiOnly = viewModel::setWifiOnly,
                            onAutoResume = viewModel::setAutoResume,
                            onVadThreshold = viewModel::setVadThreshold,
                            onVadSegmentGapMinutes = viewModel::setVadSegmentGapMinutes,
                            onCleanupRetentionDays = viewModel::setCleanupRetentionDays,
                            onDeleteOriginals = viewModel::deleteOriginals,
                            onSetAsrProvider = viewModel::setAsrProvider,
                            onSelectLocalModel = viewModel::selectLocalModel,
                            onDownloadLocalModel = viewModel::downloadLocalModel,
                            onCancelLocalModelDownload = viewModel::cancelLocalModelDownload,
                            onDeleteLocalModel = viewModel::deleteLocalModel,
                            onSaveAliyunApiKey = viewModel::saveAliyunApiKey,
                            onSaveAliyunModel = viewModel::saveAliyunModel,
                            onSaveDoubaoApiKey = viewModel::saveDoubaoApiKey,
                            onSaveDoubaoModel = viewModel::saveDoubaoModel,
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
            SmallStat("会话段", "$rangeCount 段", Modifier.weight(1f))
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
                Text("低功耗持续录音", color = Color(0xFFC8D7FA), fontSize = 13.sp)
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
                    "录音期间不运行 VAD；每个 10 分钟 chunk 完成后再后台分析和裁剪。"
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
    loadWaveform: suspend () -> List<Float>,
    onBack: () -> Unit,
    onTranscribe: () -> Unit,
    onDeleteOriginal: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rangeList by ranges.collectAsState(initial = emptyList())
    val transcriptList by transcripts.collectAsState(initial = emptyList())
    val waveform by produceState(
        initialValue = emptyList<Float>(),
        key1 = chunk.waveformPath,
        key2 = chunk.postProcessState,
    ) {
        value = loadWaveform()
    }

    var tab by rememberSaveable(chunk.id) { mutableIntStateOf(0) }
    var previewRequest by remember(chunk.id) { mutableStateOf<VadPreviewRequest?>(null) }
    var confirmDeleteOriginal by remember { mutableStateOf(false) }

    if (confirmDeleteOriginal) {
        AlertDialog(
            onDismissRequest = { confirmDeleteOriginal = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null) },
            title = { Text("删除原始录音？") },
            text = {
                Text(
                    "只删除原始 M4A。VAD 裁剪版、波形、VAD 区间和转写结果会保留。删除后将无法再用原始时间轴精确复核 VAD 边界。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteOriginal = false
                        onDeleteOriginal()
                    },
                ) { Text("确认删除原素材", color = Color(0xFFBE4757)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteOriginal = false }) { Text("取消") }
            },
        )
    }

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
                Icon(Icons.Outlined.Delete, "删除整条记录", tint = Color(0xFFBE4757))
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("原始录音", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (!chunk.originalAvailable) {
                            AssistChip(onClick = {}, label = { Text("原素材已删除") })
                        }
                    }
                    Text(
                        formatDuration(chunk.durationMs) + " · " +
                            formatBytes(chunk.fileSize) + " · " +
                            chunk.codec.uppercase() + " / " + chunk.container.uppercase(),
                        color = EchoMuted,
                    )
                    StateBadge(chunk.state)

                    when (chunk.postProcessState) {
                        "PENDING", "PROCESSING" -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                if (chunk.postProcessState == "PENDING") {
                                    "等待后台 VAD / 波形 / 裁剪处理"
                                } else {
                                    "正在后台运行 Silero VAD 并生成裁剪版…"
                                },
                                color = EchoMuted,
                                fontSize = 12.sp,
                            )
                        }
                        "FAILED" -> {
                            Text("后处理失败；再次打开该记录会重新尝试。", color = Color(0xFFBE4757), fontSize = 12.sp)
                        }
                        "DONE" -> {
                            Text(
                                "Silero 后处理完成 · " + rangeList.size + " 个会话段",
                                color = Color(0xFF23846F),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            if (chunk.originalAvailable && File(chunk.audioPath).exists()) {
                WaveformAudioPlayer(
                    path = chunk.audioPath,
                    waveform = waveform,
                    ranges = rangeList,
                    previewRequest = previewRequest,
                )
            } else if (!chunk.speechAudioPath.isNullOrBlank()) {
                InfoPanel(
                    "原始时间轴不可用",
                    "原始素材已由你确认删除。下面仍可播放 VAD 裁剪版，但 VAD 区间保留的是原始录音时间坐标。",
                )
                SimpleAudioPlayer(path = chunk.speechAudioPath)
            }

            if (!chunk.speechAudioPath.isNullOrBlank() && File(chunk.speechAudioPath).exists()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F7FF)),
                    border = BorderStroke(1.dp, Color(0xFFDCE5FF)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("VAD 裁剪版", fontWeight = FontWeight.SemiBold)
                        Text(
                            "按会话段保留音频；小于分段阈值的停顿也会保留，并在段首尾加入 400 ms / 500 ms 保护。裁剪后约 " +
                                formatDuration(chunk.speechDurationMs) + "。",
                            color = EchoMuted,
                            fontSize = 12.sp,
                        )
                        SimpleAudioPlayer(path = chunk.speechAudioPath)
                        if (chunk.originalAvailable) {
                            OutlinedButton(onClick = { confirmDeleteOriginal = true }) {
                                Icon(Icons.Outlined.DeleteSweep, null)
                                Spacer(Modifier.width(7.dp))
                                Text("删除原始素材…")
                            }
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("对话原文") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("会话段") })
            }

            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ASR 转写", fontWeight = FontWeight.SemiBold)
                        Text(
                            "使用设置中选择的 ASR 引擎处理；本地、PC、阿里云和豆包最终统一写入 chunk + offset 转写结果。",
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
                                label = { Text("已写入 " + state.count + " 段转写") },
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
                Text(
                    "整理为 " + rangeList.size + " 个会话段。只有连续静音达到设置的分段间隔才会开始新段；点击任意会话段可直接试听验证。",
                    color = EchoMuted,
                    fontSize = 13.sp,
                )

                if (rangeList.isEmpty()) {
                    EmptyCard(
                        if (chunk.postProcessState == "DONE") {
                            "Silero 未在这条录音中检测到语音。"
                        } else {
                            "VAD 后处理尚未完成。"
                        }
                    )
                } else {
                    rangeList.forEachIndexed { index, range ->
                        val clickable = chunk.originalAvailable && File(chunk.audioPath).exists()
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = clickable) {
                                        previewRequest =
                                            VadPreviewRequest(
                                                startMs = (range.startOffsetMs - 500L).coerceAtLeast(0L),
                                                endMs = (range.endOffsetMs + 500L).coerceAtMost(chunk.durationMs),
                                                token = System.nanoTime(),
                                            )
                                    },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(color = Color(0xFFEDF1FF), shape = CircleShape) {
                                    Text(
                                        (index + 1).toString(),
                                        color = EchoBlue,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        formatMs(range.startOffsetMs) + " → " +
                                            formatMs(range.endOffsetMs),
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "会话段 · " + (range.modelId ?: "VAD") + " " +
                                            (range.modelVersion ?: ""),
                                        color = EchoMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                                Icon(
                                    Icons.Outlined.PlayCircle,
                                    if (clickable) "试听该 VAD 区间" else null,
                                    tint = if (clickable) EchoBlue else EchoMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class VadPreviewRequest(
    val startMs: Long,
    val endMs: Long,
    val token: Long,
)

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
                Text("→ " + formatMs(seg.endOffsetMs), color = EchoMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(seg.language.orEmpty(), color = EchoMuted, fontSize = 12.sp)
            }
            Text(seg.text, lineHeight = 23.sp)
            Text(
                ((seg.modelId ?: "ASR") + " " + (seg.modelVersion ?: "")).trim(),
                color = EchoMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun WaveformAudioPlayer(
    path: String,
    waveform: List<Float>,
    ranges: List<AudioRangeEntity>,
    previewRequest: VadPreviewRequest?,
) {
    val file = remember(path) { File(path) }
    var prepared by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var duration by remember(path) { mutableIntStateOf(1) }
    var position by remember(path) { mutableIntStateOf(0) }
    var previewEndMs by remember(path) { mutableStateOf<Int?>(null) }
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
                    previewEndMs = null
                    position = 0
                    it.seekTo(0)
                }
            }
        }
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(previewRequest?.token, prepared) {
        val request = previewRequest
        if (prepared && request != null) {
            val start = request.startMs.coerceIn(0L, duration.toLong()).toInt()
            val end = request.endMs.coerceIn(start.toLong(), duration.toLong()).toInt()
            player.seekTo(start)
            position = start
            previewEndMs = end
            player.start()
            playing = true
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            val end = previewEndMs
            if (end != null && position >= end) {
                runCatching { player.pause() }
                playing = false
                previewEndMs = null
                position = end
                break
            }
            delay(100)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFBFE)),
        border = BorderStroke(1.dp, Color(0xFFE6EBF3)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = {
                        if (!prepared) return@FilledIconButton
                        previewEndMs = null
                        if (playing) player.pause() else player.start()
                        playing = !playing
                    },
                    enabled = prepared,
                ) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("原始波形时间轴", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (prepared) {
                            formatMs(position.toLong()) + " / " + formatMs(duration.toLong())
                        } else {
                            "音频文件不可用"
                        },
                        color = EchoMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(116.dp),
            ) {
                Canvas(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
                    val total = duration.coerceAtLeast(1).toFloat()

                    ranges.forEach { range ->
                        val left = size.width * (range.startOffsetMs / total)
                        val right = size.width * (range.endOffsetMs / total)
                        drawRect(
                            color = EchoBlue.copy(alpha = 0.13f),
                            topLeft = Offset(left, 0f),
                            size = Size((right - left).coerceAtLeast(1f), size.height),
                        )
                    }

                    if (waveform.isNotEmpty()) {
                        val bars =
                            minOf(
                                waveform.size,
                                (size.width / 3f).toInt().coerceAtLeast(1),
                            )
                        val center = size.height / 2f
                        for (bar in 0 until bars) {
                            val from = bar * waveform.size / bars
                            val to = ((bar + 1) * waveform.size / bars).coerceAtMost(waveform.size)
                            var amplitude = 0f
                            for (sample in from until to) {
                                amplitude = maxOf(amplitude, waveform[sample])
                            }
                            val half = (amplitude.coerceAtLeast(0.04f) * size.height * 0.42f)
                            val x = (bar + 0.5f) * size.width / bars
                            drawLine(
                                color = Color(0xFF71809B),
                                start = Offset(x, center - half),
                                end = Offset(x, center + half),
                                strokeWidth = 2f,
                                cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        drawLine(
                            color = Color(0xFFD5DDEA),
                            start = Offset(0f, size.height / 2f),
                            end = Offset(size.width, size.height / 2f),
                            strokeWidth = 2f,
                        )
                    }

                    val playX = size.width * (position.coerceIn(0, duration).toFloat() / total)
                    drawLine(
                        color = EchoBlue,
                        start = Offset(playX, 0f),
                        end = Offset(playX, size.height),
                        strokeWidth = 3f,
                    )
                }

                Slider(
                    value = position.coerceIn(0, duration).toFloat(),
                    onValueChange = {
                        previewEndMs = null
                        position = it.toInt()
                    },
                    onValueChangeFinished = {
                        if (prepared) player.seekTo(position)
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = EchoBlue,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = EchoBlue.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.size(width = 22.dp, height = 10.dp),
                ) {}
                Spacer(Modifier.width(7.dp))
                Text("蓝色背景 = 合并后的会话段（可包含短暂停顿）", color = EchoMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SimpleAudioPlayer(path: String?) {
    if (path.isNullOrBlank()) return

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
            delay(200)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (!prepared) return@IconButton
                if (playing) player.pause() else player.start()
                playing = !playing
            },
            enabled = prepared,
        ) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
        }
        Slider(
            value = position.coerceIn(0, duration).toFloat(),
            onValueChange = { position = it.toInt() },
            onValueChangeFinished = { if (prepared) player.seekTo(position) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatMs(position.toLong()) + " / " + formatMs(duration.toLong()),
            color = EchoMuted,
            fontSize = 11.sp,
        )
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
    onSaveApiKey: (String) -> Unit,
    onTest: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }

    Page(
        title = "电脑与 MCP",
        subtitle = "手机稳定记录；PC 用于同步，也可以作为可选 ASR/AI 处理端。",
        modifier = modifier,
    ) {
        InfoPanel(
            "PC Processing Server",
            "填写运行 pc_server 的电脑地址和同一把固定访问密钥。同步、健康检查，以及选择 PC ASR 时的转写请求都会携带 X-MemoFlow-Key。",
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:8787") },
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("访问密钥") },
            placeholder = { Text("与 PC 的 MEMOFLOW_API_KEY 保持一致") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Text(
            "Demo 使用局域网 HTTP + 固定密钥。密钥保存在本机，不要把 PC 的 8787 端口暴露到公网。",
            color = EchoMuted,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                onSaveBaseUrl(url)
                onSaveApiKey(apiKey)
                onTest()
            }) {
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
    asrSettings: AsrSettings,
    localModels: List<LocalAsrModelState>,
    chunks: List<AudioChunkEntity>,
    onWifiOnly: (Boolean) -> Unit,
    onAutoResume: (Boolean) -> Unit,
    onVadThreshold: (Float) -> Unit,
    onVadSegmentGapMinutes: (Int) -> Unit,
    onCleanupRetentionDays: (Int) -> Unit,
    onDeleteOriginals: (List<AudioChunkEntity>) -> Unit,
    onSetAsrProvider: (AsrProvider) -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onDownloadLocalModel: (String) -> Unit,
    onCancelLocalModelDownload: (String) -> Unit,
    onDeleteLocalModel: (String) -> Unit,
    onSaveAliyunApiKey: (String) -> Unit,
    onSaveAliyunModel: (String) -> Unit,
    onSaveDoubaoApiKey: (String) -> Unit,
    onSaveDoubaoModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cutoff =
        System.currentTimeMillis() -
            settings.cleanupRetentionDays.toLong() * 24L * 60L * 60L * 1000L
    val cleanupCandidates =
        chunks.filter {
            it.originalAvailable &&
                it.state == "UPLOADED" &&
                it.postProcessState == "DONE" &&
                !it.speechAudioPath.isNullOrBlank() &&
                it.endTimeUtcMs < cutoff
        }
    var confirmCleanup by remember { mutableStateOf(false) }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("删除 " + cleanupCandidates.size + " 个原始录音？") },
            text = {
                Text(
                    "这些记录已经同步到电脑、完成 VAD 裁剪，并超过保留天数。只会删除手机上的原始 M4A；裁剪版、波形、VAD 和转写继续保留。此操作需要你本次明确确认。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteOriginals(cleanupCandidates)
                        confirmCleanup = false
                    },
                ) { Text("确认删除原素材", color = Color(0xFFBE4757)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) { Text("取消") }
            },
        )
    }

    Page(
        title = "录音设置",
        subtitle = "按照实际环境调整记录、后处理与存储策略。",
        modifier = modifier,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("语音活动检测", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Silero 不再常驻录音主循环；每个 chunk 完成后后台运行，并同时生成波形与语音裁剪版。",
                    color = EchoMuted,
                    fontSize = 13.sp,
                )
                Text(
                    "阈值 " + "%.3f".format(settings.vadThreshold),
                    color = EchoBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = settings.vadThreshold,
                    onValueChange = onVadThreshold,
                    valueRange = 0.10f..0.90f,
                )
                Text(
                    "Silero 默认 0.50；越低越敏感，越高越保守。修改后，已有原始录音会标记为待重算；重新进入详情时自动重新 VAD。",
                    color = EchoMuted,
                    fontSize = 12.sp,
                )
                HorizontalDivider(color = Color(0xFFE9EDF5))
                Text(
                    "连续静音达到 " + settings.vadSegmentGapMinutes + " 分钟才开始新的一段",
                    color = EchoBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = settings.vadSegmentGapMinutes.toFloat(),
                    onValueChange = { onVadSegmentGapMinutes(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                )
                Text(
                    "默认 5 分钟。小于这个时长的停顿会保留在同一个会话段内，例如停顿 10 秒不会再拆成两段。修改后同样会让旧 VAD 结果在下次进入详情时重算。",
                    color = EchoMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
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

        AsrSettingsCard(
            settings = asrSettings,
            localModels = localModels,
            onSetProvider = onSetAsrProvider,
            onSelectLocalModel = onSelectLocalModel,
            onDownloadLocalModel = onDownloadLocalModel,
            onCancelLocalModelDownload = onCancelLocalModelDownload,
            onDeleteLocalModel = onDeleteLocalModel,
            onSaveAliyunApiKey = onSaveAliyunApiKey,
            onSaveAliyunModel = onSaveAliyunModel,
            onSaveDoubaoApiKey = onSaveDoubaoApiKey,
            onSaveDoubaoModel = onSaveDoubaoModel,
        )

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("原始素材清理", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "系统只自动识别清理候选，不会自动删除。候选必须同时满足：已同步 PC、VAD 裁剪完成、原始文件仍存在、超过保留天数。",
                    color = EchoMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "原始录音至少保留 " + settings.cleanupRetentionDays + " 天",
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = settings.cleanupRetentionDays.toFloat(),
                    onValueChange = { onCleanupRetentionDays(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
                Text(
                    "当前有 " + cleanupCandidates.size + " 个原始文件符合清理条件。",
                    color = if (cleanupCandidates.isEmpty()) EchoMuted else Color(0xFFAC7B2C),
                    fontSize = 12.sp,
                )
                Button(
                    onClick = { confirmCleanup = true },
                    enabled = cleanupCandidates.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.DeleteSweep, null)
                    Spacer(Modifier.width(7.dp))
                    Text("审查并删除候选原素材")
                }
            }
        }

        InfoPanel(
            "录音格式",
            "16 kHz · 单声道 · PCM16 → AAC-LC 24 kbps → M4A；每 10 分钟滚动一个 chunk。chunk 完成后才运行 sherpa-onnx Silero VAD。",
        )
    }
}

@Composable
private fun AsrSettingsCard(
    settings: AsrSettings,
    localModels: List<LocalAsrModelState>,
    onSetProvider: (AsrProvider) -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onDownloadLocalModel: (String) -> Unit,
    onCancelLocalModelDownload: (String) -> Unit,
    onDeleteLocalModel: (String) -> Unit,
    onSaveAliyunApiKey: (String) -> Unit,
    onSaveAliyunModel: (String) -> Unit,
    onSaveDoubaoApiKey: (String) -> Unit,
    onSaveDoubaoModel: (String) -> Unit,
) {
    var aliyunKey by remember(settings.aliyunApiKey) { mutableStateOf(settings.aliyunApiKey) }
    var aliyunModel by remember(settings.aliyunModel) { mutableStateOf(settings.aliyunModel) }
    var doubaoKey by remember(settings.doubaoApiKey) { mutableStateOf(settings.doubaoApiKey) }
    var doubaoModel by remember(settings.doubaoModel) { mutableStateOf(settings.doubaoModel) }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("语音识别", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "可在 PC、本地 sherpa-onnx、阿里云和豆包之间切换。App 不会自动下载任何本地大模型。",
                color = EchoMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            Text("识别引擎", fontWeight = FontWeight.Medium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    AsrProvider.PC to "PC",
                    AsrProvider.LOCAL to "本地",
                    AsrProvider.ALIYUN to "阿里云",
                    AsrProvider.DOUBAO to "豆包",
                ).forEach { (provider, label) ->
                    FilterChip(
                        selected = settings.provider == provider,
                        onClick = { onSetProvider(provider) },
                        label = { Text(label) },
                    )
                }
            }

            when (settings.provider) {
                AsrProvider.PC -> {
                    InfoPanel(
                        "PC ASR",
                        "继续使用“连接”页里的 MemoFlow PC Server /asr 接口。",
                    )
                }

                AsrProvider.LOCAL -> {
                    Text("本地模型", fontWeight = FontWeight.SemiBold)
                    if (localModels.isEmpty()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        localModels.forEach { state ->
                            LocalAsrModelCard(
                                state = state,
                                selected = settings.localModelId == state.spec.id,
                                onSelect = { onSelectLocalModel(state.spec.id) },
                                onDownload = { onDownloadLocalModel(state.spec.id) },
                                onCancel = { onCancelLocalModelDownload(state.spec.id) },
                                onDelete = { onDeleteLocalModel(state.spec.id) },
                            )
                        }
                    }
                    Text(
                        "下载默认要求非计费网络（通常为 Wi‑Fi）且电量不低。Qwen3/Fun-ASR 安装时建议至少预留约 2 GB 空间。",
                        color = EchoMuted,
                        fontSize = 11.sp,
                    )
                }

                AsrProvider.ALIYUN -> {
                    OutlinedTextField(
                        value = aliyunKey,
                        onValueChange = { aliyunKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("DashScope API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = aliyunModel,
                        onValueChange = { aliyunModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型") },
                        placeholder = { Text("qwen3-asr-flash") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            onSaveAliyunApiKey(aliyunKey)
                            onSaveAliyunModel(aliyunModel)
                        },
                    ) { Text("保存阿里云配置") }
                    Text(
                        "API Key 由 Android Keystore 加密保存。长于约 5 分钟的录音会自动切成技术分片提交，再按原时间轴合并。",
                        color = EchoMuted,
                        fontSize = 11.sp,
                    )
                }

                AsrProvider.DOUBAO -> {
                    OutlinedTextField(
                        value = doubaoKey,
                        onValueChange = { doubaoKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ark API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = doubaoModel,
                        onValueChange = { doubaoModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("支持音频输入的模型 ID / Endpoint ID") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            onSaveDoubaoApiKey(doubaoKey)
                            onSaveDoubaoModel(doubaoModel)
                        },
                    ) { Text("保存豆包配置") }
                    Text(
                        "API Key 由 Android Keystore 加密保存。这里使用火山方舟兼容 Chat API 的 input_audio Base64 输入。",
                        color = EchoMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalAsrModelCard(
    state: LocalAsrModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val spec = state.spec
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = if (selected) Color(0xFFF2F5FF) else Color(0xFFFAFBFE),
            ),
        border =
            BorderStroke(
                1.dp,
                if (selected) EchoBlue.copy(alpha = 0.55f) else Color(0xFFE6EBF3),
            ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        spec.languages + " · 下载包 " + formatBytes(spec.archiveBytes),
                        color = EchoMuted,
                        fontSize = 11.sp,
                    )
                }
                RadioButton(selected = selected, onClick = onSelect)
            }
            Text(spec.description, color = EchoMuted, fontSize = 12.sp)

            when (state.status) {
                LocalModelInstallStatus.INSTALLED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("已安装") },
                            leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                        )
                        TextButton(onClick = onDelete) { Text("删除模型") }
                    }
                }

                LocalModelInstallStatus.DOWNLOADING,
                LocalModelInstallStatus.VERIFYING,
                LocalModelInstallStatus.INSTALLING -> {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.status.name + " · " + state.progressPercent + "% " + state.detail,
                            color = EchoMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }

                LocalModelInstallStatus.WAITING_FOR_WIFI -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("等待 Wi‑Fi / 电量条件", color = EchoMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }

                LocalModelInstallStatus.FAILED -> {
                    Text(
                        "下载/安装失败：" + state.detail,
                        color = Color(0xFFBE4757),
                        fontSize = 11.sp,
                    )
                    OutlinedButton(onClick = onDownload) { Text("重试下载") }
                }

                else -> {
                    OutlinedButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text("下载")
                    }
                }
            }
        }
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
