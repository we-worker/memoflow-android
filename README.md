# MemoFlow Android V1

原生 Kotlin + Jetpack Compose 的录音基础工程，按原型和技术路线实现：

- Android `microphone` Foreground Service，异常时 `START_STICKY`
- `AudioRecord` 采集 16 kHz/mono PCM16
- PCM 同时进入 AAC 编码和本地 VAD（能量 VAD，接口可替换为 sherpa-onnx Silero）
- 10 分钟滚动 M4A 以及 `.part` 崩溃恢复边界
- Room 数据库实体：`AudioChunk`、`AudioRange`
- ASR 通过 PC FastAPI 接口，`AsrEngine` 预留 faster-whisper / sherpa-onnx 实现

## 构建

使用 Android Studio Hedgehog+、JDK 17、Android SDK 35 打开目录。真机需要 Android 10+，首次启动授予麦克风权限。录音文件位于 app 私有目录 `files/audio`。

## PC ASR

```bash
cd pc_server
python -m venv .venv && . .venv/bin/activate
pip install fastapi uvicorn python-multipart faster-whisper
uvicorn main:app --host 0.0.0.0 --port 8787
```

默认使用 `faster-whisper`；未安装或模型不可用时服务仍会启动并返回占位状态。Android 通过 multipart `/asr` 上传 M4A，并获得带 chunk offset 的 TranscriptSegment。

## GitHub Actions

仓库中的 `.github/workflows/android.yml` 会自动安装 JDK 17、Android SDK 35 和 Gradle 8.7，构建 Debug APK，并把 APK 上传为 workflow artifact。

## 本轮迭代

- 录音完成后写入 Room `AudioChunk` 和 `AudioRange`
- WorkManager 每 15 分钟同步已完成 chunk
- 上传失败自动重试，缺失文件标记 FAILED
- 增加网络权限、同步状态和 Android 构建工作流
