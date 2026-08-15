import time
from pathlib import Path

from fastapi.testclient import TestClient

from app import main


def _wait_for_terminal(client: TestClient, job_id: str) -> dict:
    for _ in range(100):
        response = client.get(f"/v1/jobs/{job_id}")
        assert response.status_code == 200
        payload = response.json()
        if payload["status"] in {"completed", "failed", "canceled"}:
            return payload
        time.sleep(0.01)
    raise AssertionError("job did not finish")


def test_job_contract_returns_text_and_deletes_audio(monkeypatch, tmp_path: Path):
    monkeypatch.setattr(main, "WORK_ROOT", tmp_path)

    def fake_process(**kwargs):
        assert kwargs["source"].read_bytes() == b"fake audio"
        kwargs["progress"](75, "test processing")
        return "[00:01.00] পরীক্ষা\n[00:02.00]"

    monkeypatch.setattr(main, "process_audio", fake_process)
    client = TestClient(main.app)
    response = client.post(
        "/v1/jobs",
        data={"mode": "transcribe", "language": "bn"},
        files={"audio": ("song.mp3", b"fake audio", "audio/mpeg")},
    )

    assert response.status_code == 202
    job_id = response.json()["job_id"]
    payload = _wait_for_terminal(client, job_id)
    assert payload["status"] == "completed"
    assert payload["progress"] == 100
    assert payload["result"]["lrc"].endswith("[00:02.00]")
    assert not (tmp_path / job_id).exists()


def test_align_mode_requires_lyrics(monkeypatch, tmp_path: Path):
    monkeypatch.setattr(main, "WORK_ROOT", tmp_path)
    client = TestClient(main.app)

    response = client.post(
        "/v1/jobs",
        data={"mode": "align", "language": "auto"},
        files={"audio": ("song.flac", b"audio", "audio/flac")},
    )

    assert response.status_code == 400
    assert "lyrics" in response.json()["detail"]
    assert list(tmp_path.iterdir()) == []


def test_delete_unknown_job_is_idempotent():
    response = TestClient(main.app).delete("/v1/jobs/not-a-real-job")
    assert response.status_code == 204
