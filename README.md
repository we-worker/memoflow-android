# MemoFlow / 回声 Android Demo

当前主链路：

`AudioRecord -> AAC-LC/M4A -> sherpa-onnx Silero VAD -> Room -> LAN sync -> PC ASR`

## Android 端

- Android microphone Foreground Service
- 16 kHz / mono / PCM16 连续采集
- AAC-LC 24 kbps，10 分钟滚动 M4A
- **sherpa-onnx v1.13.8 + Silero VAD**
- VAD 只标记语音区间，不裁掉原始录音
- Room 保存 `AudioChunk`、`AudioRange`、`TranscriptSegment`
- 历史录音可真实回放，可查看 VAD 区间和 PC ASR 结果

### Silero VAD 依赖

构建时从 sherpa-onnx 官方 GitHub Release 获取：

- `sherpa-onnx-1.13.8.aar`
- `silero_vad.onnx`

模型 SHA-256 固定为：

`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`

因此仓库不直接提交大体积 AAR 和模型；Gradle 构建会自动拉取并校验模型。

## 局域网同步 + 固定密钥

Demo 的安全边界：

1. PC 和手机位于同一个可信局域网。
2. PC 配置一个足够长的固定密钥。
3. 手机“电脑与 MCP”页面填写 PC 地址和同一个密钥。
4. `/health`、`/audio`、`/asr`、`/chunks` 都要求 `X-MemoFlow-Key`。
5. PC 用常量时间比较校验密钥；PC 未配置密钥时默认拒绝访问。

> 当前方案是局域网 HTTP + PSK，适合个人 Demo。不要把 8787 端口暴露到公网。

### PC 启动：Linux / macOS

```bash
python -m venv .venv
source .venv/bin/activate
pip install fastapi uvicorn python-multipart faster-whisper

export MEMOFLOW_API_KEY='请替换成至少 24~32 位随机字符串'
export MEMOFLOW_AUDIO_ROOT='./audio'
uvicorn pc_server.main:app --host 0.0.0.0 --port 8787
```

### PC 启动：Windows PowerShell

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install fastapi uvicorn python-multipart faster-whisper

$env:MEMOFLOW_API_KEY = "请替换成至少 24~32 位随机字符串"
$env:MEMOFLOW_AUDIO_ROOT = ".\audio"
uvicorn pc_server.main:app --host 0.0.0.0 --port 8787
```

手机端服务器地址示例：

`http://192.168.1.10:8787`

同步后 PC 目录会得到：

```text
audio/
  <chunk-id>.m4a
  <chunk-id>.json
```

JSON 中包含 chunk 元数据以及 Silero VAD 的 `speechRanges`。PC 的 `GET /chunks` 可检查已同步的 chunk。

## ASR

录音详情页可以调用 PC `/asr`。默认使用 faster-whisper；没有安装模型时服务仍能启动并返回 stub 结果。

## GitHub Actions

CI 会运行：

- JVM 单元测试
- Debug APK 构建
- PC FastAPI 鉴权 / 上传 / 路径安全测试
- Android 15 / API 35 模拟器集成测试
- sherpa-onnx AAR + Silero ONNX 模型实际加载测试
