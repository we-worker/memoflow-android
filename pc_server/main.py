from __future__ import annotations

import hmac
import json
import os
import re
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

app = FastAPI(title="MemoFlow Processing Server", version="0.2.0")

AUDIO_ROOT = Path(os.getenv("MEMOFLOW_AUDIO_ROOT", "./audio"))
AUDIO_ROOT.mkdir(parents=True, exist_ok=True)
MODEL_NAME = os.getenv("MEMOFLOW_WHISPER_MODEL", "small")
API_KEY = os.getenv("MEMOFLOW_API_KEY", "")
CHUNK_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


def require_api_key(x_memoflow_key: Annotated[str | None, Header()] = None) -> None:
    if not API_KEY:
        raise HTTPException(
            status_code=503,
            detail="MEMOFLOW_API_KEY is not configured on the PC server",
        )
    if not hmac.compare_digest(x_memoflow_key or "", API_KEY):
        raise HTTPException(status_code=401, detail="Invalid MemoFlow API key")


def safe_chunk_id(chunk_id: str) -> str:
    if not CHUNK_ID_PATTERN.fullmatch(chunk_id):
        raise HTTPException(status_code=400, detail="Invalid chunk_id")
    return chunk_id


def write_chunk(chunk_id: str, audio_bytes: bytes, metadata_json: str | None = None):
    chunk_id = safe_chunk_id(chunk_id)
    audio_target = AUDIO_ROOT / f"{chunk_id}.m4a"
    audio_tmp = AUDIO_ROOT / f"{chunk_id}.m4a.tmp"
    audio_tmp.write_bytes(audio_bytes)
    audio_tmp.replace(audio_target)

    metadata_target = None
    if metadata_json is not None:
        try:
            metadata = json.loads(metadata_json)
        except json.JSONDecodeError as exc:
            raise HTTPException(status_code=400, detail="metadata_json is invalid JSON") from exc

        metadata_target = AUDIO_ROOT / f"{chunk_id}.json"
        metadata_tmp = AUDIO_ROOT / f"{chunk_id}.json.tmp"
        metadata_tmp.write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        metadata_tmp.replace(metadata_target)

    return audio_target, metadata_target


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
            "start_ms": int(segment.start * 1000) + start_ms,
            "end_ms": int(segment.end * 1000) + start_ms,
            "text": segment.text.strip(),
            "language": info.language,
            "model_id": "faster-whisper",
            "model_version": MODEL_NAME,
        } for segment in segments]


asr = AsrEngine()


@app.post("/audio")
async def upload_audio(
    chunk_id: str = Form(...),
    metadata_json: str = Form("{}"),
    audio: UploadFile = File(...),
    x_memoflow_key: Annotated[str | None, Header()] = None,
):
    require_api_key(x_memoflow_key)
    audio_target, metadata_target = write_chunk(
        chunk_id,
        await audio.read(),
        metadata_json,
    )
    return {
        "chunk_id": chunk_id,
        "path": str(audio_target),
        "metadata_path": str(metadata_target),
        "status": "stored",
    }


@app.post("/asr")
async def transcribe(
    chunk_id: str = Form(...),
    start_ms: int = Form(0),
    end_ms: int = Form(0),
    audio: UploadFile = File(...),
    x_memoflow_key: Annotated[str | None, Header()] = None,
):
    require_api_key(x_memoflow_key)
    audio_target, _ = write_chunk(chunk_id, await audio.read())
    return {
        "chunk_id": chunk_id,
        "segments": asr.transcribe(audio_target, chunk_id, start_ms, end_ms),
    }


@app.get("/chunks")
def list_chunks(x_memoflow_key: Annotated[str | None, Header()] = None):
    require_api_key(x_memoflow_key)
    result = []
    for path in sorted(
        AUDIO_ROOT.glob("*.m4a"),
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    ):
        chunk_id = path.stem
        metadata_path = AUDIO_ROOT / f"{chunk_id}.json"
        result.append({
            "chunk_id": chunk_id,
            "audio_bytes": path.stat().st_size,
            "has_metadata": metadata_path.exists(),
        })
    return {"chunks": result}


@app.get("/health")
def health(x_memoflow_key: Annotated[str | None, Header()] = None):
    require_api_key(x_memoflow_key)
    return {
        "ok": True,
        "auth": "fixed-key",
        "asr_backend": "faster-whisper" if asr.model else "stub",
    }
