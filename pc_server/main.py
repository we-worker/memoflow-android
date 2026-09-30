from __future__ import annotations
import os
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, Form

app = FastAPI(title="MemoFlow Processing Server", version="0.1.0")
AUDIO_ROOT = Path(os.getenv("MEMOFLOW_AUDIO_ROOT", "./audio"))
AUDIO_ROOT.mkdir(exist_ok=True)
MODEL_NAME = os.getenv("MEMOFLOW_WHISPER_MODEL", "small")

class AsrEngine:
    def __init__(self):
        self.model = None
        try:
            from faster_whisper import WhisperModel
            self.model = WhisperModel(
                MODEL_NAME,
                device=os.getenv("MEMOFLOW_WHISPER_DEVICE", "cpu"),
                compute_type=os.getenv("MEMOFLOW_WHISPER_COMPUTE", "int8"),
            )
        except Exception:
            pass

    def transcribe(self, path, chunk_id, start_ms=0, end_ms=0):
        if self.model is None:
            return [{
                "chunk_id": chunk_id,
                "start_ms": start_ms,
                "end_ms": end_ms,
                "text": "[ASR model unavailable]",
                "model_id": "stub",
                "model_version": "0",
            }]
        segments, info = self.model.transcribe(str(path), vad_filter=True)
        return [{
            "chunk_id": chunk_id,
            "start_ms": int(s.start * 1000) + start_ms,
            "end_ms": int(s.end * 1000) + start_ms,
            "text": s.text.strip(),
            "language": info.language,
            "model_id": "faster-whisper",
            "model_version": MODEL_NAME,
        } for s in segments]

asr = AsrEngine()

@app.post("/audio")
async def upload_audio(
    chunk_id: str = Form(...),
    metadata_json: str = Form("{}"),
    audio: UploadFile = File(...),
):
    target = AUDIO_ROOT / f"{chunk_id}.m4a"
    target.write_bytes(await audio.read())
    return {"chunk_id": chunk_id, "path": str(target), "status": "stored"}

@app.post("/asr")
async def transcribe(
    chunk_id: str = Form(...),
    start_ms: int = Form(0),
    end_ms: int = Form(0),
    audio: UploadFile = File(...),
):
    target = AUDIO_ROOT / f"{chunk_id}.m4a"
    target.write_bytes(await audio.read())
    return {
        "chunk_id": chunk_id,
        "segments": asr.transcribe(target, chunk_id, start_ms, end_ms),
    }

@app.get("/health")
def health():
    return {
        "ok": True,
        "asr_backend": "faster-whisper" if asr.model else "stub",
    }
