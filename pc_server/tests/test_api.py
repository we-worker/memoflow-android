from fastapi.testclient import TestClient
from pc_server import main

TEST_KEY = "memo-test-key-please-change"
client = TestClient(main.app)


def auth_headers(key=TEST_KEY):
    return {"X-MemoFlow-Key": key}


def setup_function():
    main.API_KEY = TEST_KEY


def test_health_requires_key():
    assert client.get("/health").status_code == 401
    assert client.get("/health", headers=auth_headers("wrong")).status_code == 401

    response = client.get("/health", headers=auth_headers())
    assert response.status_code == 200
    payload = response.json()
    assert payload["ok"] is True
    assert payload["auth"] == "fixed-key"
    assert payload["asr_backend"] in {"stub", "faster-whisper"}


def test_unconfigured_server_fails_closed():
    main.API_KEY = ""
    response = client.get("/health", headers=auth_headers())
    assert response.status_code == 503


def test_audio_upload_persists_audio_and_metadata(tmp_path):
    main.AUDIO_ROOT = tmp_path
    response = client.post(
        "/audio",
        headers=auth_headers(),
        data={
            "chunk_id": "chunk-1",
            "metadata_json": '{"speechRanges":[{"startOffsetMs":10,"endOffsetMs":20}]}',
        },
        files={"audio": ("sample.m4a", b"fake-m4a-data", "audio/mp4")},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "stored"
    assert (tmp_path / "chunk-1.m4a").read_bytes() == b"fake-m4a-data"
    assert (tmp_path / "chunk-1.json").exists()
    assert "speechRanges" in (tmp_path / "chunk-1.json").read_text(encoding="utf-8")


def test_chunk_id_rejects_path_traversal(tmp_path):
    main.AUDIO_ROOT = tmp_path
    response = client.post(
        "/audio",
        headers=auth_headers(),
        data={"chunk_id": "../escape", "metadata_json": "{}"},
        files={"audio": ("sample.m4a", b"fake-m4a-data", "audio/mp4")},
    )
    assert response.status_code == 400
    assert not (tmp_path.parent / "escape.m4a").exists()


def test_asr_stub_preserves_chunk_offsets(tmp_path):
    main.AUDIO_ROOT = tmp_path
    main.asr.model = None
    response = client.post(
        "/asr",
        headers=auth_headers(),
        data={"chunk_id": "chunk-2", "start_ms": "1200", "end_ms": "3400"},
        files={"audio": ("sample.m4a", b"fake-m4a-data", "audio/mp4")},
    )
    assert response.status_code == 200
    segment = response.json()["segments"][0]
    assert segment["chunk_id"] == "chunk-2"
    assert segment["start_ms"] == 1200
    assert segment["end_ms"] == 3400
    assert segment["model_id"] == "stub"


def test_chunks_lists_synced_audio(tmp_path):
    main.AUDIO_ROOT = tmp_path
    (tmp_path / "chunk-a.m4a").write_bytes(b"abc")
    (tmp_path / "chunk-a.json").write_text("{}", encoding="utf-8")

    response = client.get("/chunks", headers=auth_headers())
    assert response.status_code == 200
    item = response.json()["chunks"][0]
    assert item["chunk_id"] == "chunk-a"
    assert item["audio_bytes"] == 3
    assert item["has_metadata"] is True
