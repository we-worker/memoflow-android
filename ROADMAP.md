# MemoFlow 迭代记录

## 阶段 1：可靠录音底座

- Foreground Service + AudioRecord + AAC-LC + MediaMuxer
- 10 分钟 M4A 分片
- 录音完成后写入 Room AudioChunk
- VAD 结果写入 AudioRange
- 启动时支持 boot receiver 自动恢复

## 阶段 2：后台同步

- WorkManager 每 15 分钟扫描待上传 chunk
- HTTP multipart 上传到 PC `/audio`
- 成功、失败、重试状态
- 文件不存在时标记 FAILED

## 阶段 3：PC ASR

- FastAPI `/asr`
- faster-whisper 可选后端
- 返回 chunk offset 对齐的 TranscriptSegment
- Android RemoteAsrEngine 已预留

## 阶段 4：持续稳定性（下一步）

- 真机 8–12 小时测试
- 低存储保护
- 麦克风占用重试
- 蓝牙输入切换
- 电池优化引导
- 实际 Silero VAD 替换能量 VAD
- 音频播放和时间线诊断页
