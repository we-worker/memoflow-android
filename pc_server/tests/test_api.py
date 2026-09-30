from fastapi.testclient import TestClient
from pc_server import main

client = TestClient(main.app)


def test_health_reports_available_backend():
    response = client.get("/health")
    assert response.status_code == 200
    payload = response.json()
    assert payload["ok"] is True
    assert payload["asr_backend"] in {"stub", "faster-whisper"}


def test_audio_upload_persists_bytes(tmp_path):
    main.AUDIO_ROOT = tmp_path
    response = client.post(
        "/audio",
        data={"chunk_id": "chunk-1", "metadata_json": "{}"},
        files={"audio": ("sample.m4a", b"fake-m4a-data", "audio/mp4")},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "stored"
    assert (tmp_path / "chunk-1.m4a").read_bytes() == b"fake-m4a-data"


def test_asr_stub_preserves_chunk_offsets(tmp_path):
    main.AUDIO_ROOT = tmp_path
    main.asr.model = None
    response = client.post(
        "/asr",
        data={"chunk_id": "chunk-2", "start_ms": "1200", "end_ms": "3400"},
        files={"audio": ("sample.m4a", b"fake-m4a-data", "audio/mp4")},
    )
    assert response.status_code == 200
    segment = response.json()["segments"][0]
    assert segment["chunk_id"] == "chunk-2"
    assert segment["start_ms"] == 1200
    assert segment["end_ms"] == 3400
    assert segment["model_id"] == "stub"
