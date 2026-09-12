"""Batch control behavior; child detectors are exercised by real corpus replay separately."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest


@pytest.fixture
def batch():
    tools = Path(__file__).parents[2] / "tools"
    path = tools / "offline_replay_batch.py"
    assert path.exists(), "reusable replay batch entrypoint is missing"
    sys.path.insert(0, str(tools))
    try:
        spec = importlib.util.spec_from_file_location("offline_replay_batch", path)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        yield module
    finally:
        sys.path.remove(str(tools))


def manifest(tmp_path: Path, jobs: list[dict] | None = None) -> Path:
    value = {
        "version": 1,
        "model": str(tmp_path / "model.task"),
        "jobs": jobs
        if jobs is not None
        else [
            {
                "id": "squat-on-lunge",
                "engine": "squat",
                "video": str(tmp_path / "clip.mp4"),
                "cache": str(tmp_path / "cache"),
                "config": str(tmp_path / "config.json"),
            }
        ],
    }
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(value))
    return path


def test_missing_labels_remain_unscored_and_invocation_is_hash_bound(batch, tmp_path, monkeypatch):
    calls = []

    def child(command, **kwargs):
        calls.append(command)
        assert kwargs.get("shell", False) is False
        output = Path(command[command.index("--output") + 1])
        output.write_text(json.dumps({"status": "UNLABELLED", "predicted": 6, "frame_count": 900}))
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(batch.subprocess, "run", child)
    source = manifest(tmp_path)
    result = batch.run_batch(source, tmp_path / "out")
    assert result["status"] == "COMPLETE"
    assert result["manifest_sha256"] == batch.sha256(source)
    row = result["jobs"][0]
    assert row["result"]["status"] == "UNLABELLED"
    assert "recall" not in row["result"]
    assert "--labels" not in calls[0]
    assert "--run-kotlin" in calls[0]
    assert row["report_sha256"] == batch.sha256(tmp_path / "out/squat-on-lunge.json")


@pytest.mark.parametrize(
    "change", ["engine", "traversal", "duplicate", "relative", "empty", "reserved"]
)
def test_invalid_jobs_fail_before_creating_outputs(batch, tmp_path, change):
    source = manifest(tmp_path)
    value = json.loads(source.read_text())
    job = value["jobs"][0]
    if change == "engine":
        job["engine"] = "arbitrary-shell"
    elif change == "traversal":
        job["id"] = "../escape"
    elif change == "duplicate":
        value["jobs"].append(dict(job))
    elif change == "relative":
        job["video"] = "relative.mp4"
    elif change == "reserved":
        job["id"] = "summary"
    else:
        value["jobs"] = []
    source.write_text(json.dumps(value))
    with pytest.raises(ValueError):
        batch.run_batch(source, tmp_path / "out")
    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("failure", ["exit", "missing", "timeout", "empty"])
def test_failed_child_never_looks_complete_and_stops_batch(batch, tmp_path, monkeypatch, failure):
    calls = []

    def child(command, **kwargs):
        calls.append(command)
        if failure == "timeout":
            raise subprocess.TimeoutExpired(command, 1)
        if failure == "empty":
            Path(command[command.index("--output") + 1]).write_text("{}")
        return subprocess.CompletedProcess(command, 9 if failure == "exit" else 0)

    monkeypatch.setattr(batch.subprocess, "run", child)
    result = batch.run_batch(manifest(tmp_path), tmp_path / "out")
    assert result["status"] == "FAILED"
    assert len(calls) == 1
    assert json.loads((tmp_path / "out/summary.json").read_text())["status"] == "FAILED"


def test_existing_output_is_never_overwritten(batch, tmp_path):
    output = tmp_path / "out"
    output.mkdir()
    sentinel = output / "summary.json"
    sentinel.write_text("original")
    with pytest.raises(FileExistsError):
        batch.run_batch(manifest(tmp_path), output)
    assert sentinel.read_text() == "original"
