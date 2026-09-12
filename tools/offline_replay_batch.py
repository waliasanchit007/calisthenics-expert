"""Serial, private corpus replay using existing Kotlin-backed movement entrypoints.

COMPLETE means every invocation produced a report, not that any movement is reliable.
Missing labels remain UNLABELLED. A fresh output directory is required for every run.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

from offline_movement_feedback import private_output, sha256, write_json

ENGINES = {
    "squat": "offline_movement_feedback.py",
    "lunge": "offline_lunge_feedback.py",
    "jumping_jack": "offline_jumping_jack_feedback.py",
    "plank": "offline_plank_feedback.py",
}


def load_manifest(path: Path) -> tuple[dict[str, Any], str]:
    raw = path.read_bytes()
    value = json.loads(raw)
    if (
        not isinstance(value, dict)
        or type(value.get("version")) is not int
        or value["version"] != 1
    ):
        raise ValueError("batch manifest version must be 1")
    jobs = value.get("jobs")
    if not isinstance(jobs, list) or not jobs:
        raise ValueError("batch requires nonempty jobs")
    identifiers = set()
    for job in jobs:
        if not isinstance(job, dict):
            raise ValueError("each job must be an object")  # noqa: TRY004 - uniform external-record error
        identifier = job.get("id")
        if (
            not isinstance(identifier, str)
            or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", identifier) is None
        ):
            raise ValueError("invalid job id")
        if identifier in ("summary", "request") or identifier.startswith("checkpoint-"):
            raise ValueError("job id conflicts with batch metadata")
        if (
            identifier in identifiers
            or not isinstance(job.get("engine"), str)
            or job["engine"] not in ENGINES
        ):
            raise ValueError("duplicate job id or unsupported engine")
        identifiers.add(identifier)
        for key in ("video", "cache", "config", "labels", "corpus_manifest"):
            if key in ("labels", "corpus_manifest") and key not in job:
                continue
            if not isinstance(job.get(key), str) or not Path(job[key]).is_absolute():
                raise ValueError(f"{key} must be an absolute path")
    if (
        not isinstance(value.get("model"), str)
        or not Path(value["model"]).is_absolute()
    ):
        raise ValueError("model must be an absolute path")
    return value, hashlib.sha256(raw).hexdigest()


def run_batch(manifest: Path, output: Path) -> dict[str, Any]:
    value, digest = load_manifest(manifest)
    output = private_output(output)
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    summary: dict[str, Any] = {
        "version": 1,
        "status": "RUNNING",
        "manifest_sha256": digest,
        "manifest": value,
        "jobs": [],
        "interpretation": "Invocation completion is not movement accuracy; preserve each report's label status.",
    }
    # This snapshot makes the exact requested cases recoverable even on process interruption.
    write_json(output / "request.json", summary)
    for job in value["jobs"]:
        report = output / f"{job['id']}.json"
        command = [
            sys.executable,
            str(Path(__file__).with_name(ENGINES[job["engine"]])),
            "--model",
            value["model"],
            "--run-kotlin",
            "--output",
            str(report),
        ]
        for key in ("video", "cache", "config", "labels", "corpus_manifest"):
            if key in job:
                command.extend([f"--{key.replace('_', '-')}", job[key]])
        row: dict[str, Any] = {
            "id": job["id"],
            "engine": job["engine"],
            "command": command,
        }
        summary["jobs"].append(row)
        try:
            with (output / f"{job['id']}.log").open("x") as log:
                completed = subprocess.run(
                    command,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    timeout=360,
                    check=False,
                )
            row["exit_code"] = completed.returncode
            if completed.returncode != 0:
                raise ValueError(f"replay exited {completed.returncode}")
            result = json.loads(report.read_text())
            if (
                not isinstance(result, dict)
                or not isinstance(result.get("status"), str)
                or type(result.get("frame_count")) is not int
                or result["frame_count"] <= 0
            ):
                raise ValueError("replay report lacks status or observed frames")
            row.update(report_sha256=sha256(report), result=result, status="COMPLETE")
        except (OSError, ValueError, subprocess.TimeoutExpired) as error:
            row.update(status="FAILED", error=str(error))
            summary["status"] = "FAILED"
            write_json(output / "summary.json", summary)
            return summary
        write_json(output / f"checkpoint-{len(summary['jobs'])}.json", summary)
    summary["status"] = "COMPLETE"
    write_json(output / "summary.json", summary)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = run_batch(args.manifest, args.output_dir)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps({"status": result["status"], "jobs": len(result["jobs"])}))
    return 0 if result["status"] == "COMPLETE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
