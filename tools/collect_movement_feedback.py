"""Collect and analyze bounded, local movement-viewer diagnostic feedback.

The collector is deliberately read-only with respect to Android.  It only discovers the one
authorized target and reads the app-owned private session bundles with ``run-as``.  Host output is
created outside Git worktrees, with restrictive permissions, and is never uploaded or deleted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import selectors
import subprocess
import sys
import tempfile
import time
from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any, TypeGuard

PACKAGE = "com.calisthenics.diagnostic.android"
REMOTE_ROOT = "no_backup/movement-feedback"
MAX_SESSION_BYTES = 64 * 1024 * 1024
MAX_HOST_BYTES = 256 * 1024 * 1024
DEFAULT_SECONDS = 3600.0
DEFAULT_POLL_SECONDS = 2.0
UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
FRAME_REF_RE = re.compile(r"^frames/([0-9]+)\.jpg$")
SAFE_SERIAL_RE = re.compile(r"^[A-Za-z0-9._:-]+$")
END_EVENTS = {"ERROR", "READY", "CONFIRM", "RESET", "STOP"}


class FeedbackError(RuntimeError):
    """A malformed bundle, unsafe path, device mismatch, or collector failure."""


class AdbDisconnected(FeedbackError):
    """A read could not complete; callers must retain ongoing status and retry."""


class JsonLines:
    def __init__(
        self,
        rows: list[dict[str, Any]],
        incomplete_lines: int = 0,
        invalid_lines: int = 0,
    ) -> None:
        self.rows = rows
        self.incomplete_lines = incomplete_lines
        self.invalid_lines = invalid_lines


def _json_bytes(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def read_complete_jsonl(source: Path | bytes) -> JsonLines:
    """Read complete newline-terminated object rows and ignore one torn final line."""
    data = source.read_bytes() if isinstance(source, Path) else source
    rows: list[dict[str, Any]] = []
    incomplete = 0
    invalid = 0
    chunks = data.splitlines(keepends=True)
    for index, chunk in enumerate(chunks):
        complete = chunk.endswith((b"\n", b"\r"))
        raw = chunk.rstrip(b"\r\n")
        if not raw:
            continue
        if not complete and index == len(chunks) - 1:
            incomplete += 1
            continue
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            invalid += 1
            continue
        if isinstance(value, dict):
            rows.append(value)
        else:
            invalid += 1
    return JsonLines(rows, incomplete, invalid)


def _is_uuid(value: object) -> bool:
    return isinstance(value, str) and UUID_RE.fullmatch(value) is not None


def _is_nonnegative_int(value: object) -> TypeGuard[int]:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def deduplicate_trace(
    rows: Iterable[dict[str, Any]], session: str
) -> list[dict[str, Any]]:
    """Keep the first valid row for each untrusted-input ``(session, seq)`` identity."""
    seen: set[tuple[str, int]] = set()
    unique: list[dict[str, Any]] = []
    for row in rows:
        row_session = row.get("session")
        seq = row.get("seq")
        if row_session != session or not _is_nonnegative_int(seq):
            continue
        key = (row_session, seq)
        if key not in seen:
            seen.add(key)
            unique.append(row)
    return unique


def _read_json(path: Path) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _safe_local_frame(bundle: Path, image: object, capture_ns: object) -> Path | None:
    if not _is_nonnegative_int(capture_ns) or not isinstance(image, str):
        return None
    match = FRAME_REF_RE.fullmatch(image)
    if match is None or match.group(1) != str(capture_ns):
        return None
    candidate = bundle / image
    try:
        relative_parts = Path(image).parts
        current = bundle
        for part in relative_parts:
            current = current / part
            if current.is_symlink():
                return None
        if candidate.resolve() != bundle.resolve() / image:
            return None
    except OSError:
        return None
    return candidate


def _trace_failure(row: dict[str, Any]) -> str | None:
    for field in ("failure_code", "input_issue"):
        value = row.get(field)
        if isinstance(value, str) and value and value.upper() not in {"NONE", "NULL"}:
            return value
    return None


def _trace_summary(
    rows: list[dict[str, Any]], parsed: JsonLines, duplicate_count: int
) -> dict[str, Any]:
    seqs = sorted({row["seq"] for row in rows if _is_nonnegative_int(row.get("seq"))})
    missing: list[int] = []
    if seqs:
        seq_set = set(seqs)
        missing = [seq for seq in range(seqs[0], seqs[-1] + 1) if seq not in seq_set]

    def total(field: str) -> int:
        return max(
            (
                value
                for value in (row.get(field, 0) for row in rows)
                if _is_nonnegative_int(value)
            ),
            default=0,
        )

    legacy = any("capture_ns" not in row and "capture_ms" in row for row in rows)
    exact = any(
        row.get("measurements_mode") == "exact"
        or row.get("measurement_mode") == "exact"
        or (
            isinstance(row.get("v"), int)
            and row["v"] >= 2
            and row.get("angle_quantum_deg") == 0
        )
        for row in rows
    )
    if not exact and any(
        "angle_quantum_deg" in row or "height_quantum" in row for row in rows
    ):
        measurement_mode = "quantized_legacy"
    elif exact:
        measurement_mode = "exact"
    else:
        measurement_mode = "unknown"
    movements = [row.get("movement", "PULL_UP") for row in rows]
    movement_valid = bool(movements) and all(
        isinstance(value, str)
        and value in ("PULL_UP", "PUSH_UP")
        and value == movements[0]
        for value in movements
    )
    return {
        "rows": len(rows),
        "duplicates": duplicate_count,
        "incomplete_last_lines": parsed.incomplete_lines,
        "invalid_lines": parsed.invalid_lines,
        "missing_sequences": missing,
        "pipeline_skipped": total("pipeline_skipped"),
        "writer_dropped": total("writer_dropped"),
        "bundle_dropped": total("bundle_dropped"),
        "rate_suppressed": total("rate_suppressed"),
        "capture_ns_rows": sum(
            _is_nonnegative_int(row.get("capture_ns")) for row in rows
        ),
        "legacy_capture_ms": legacy,
        "measurements_mode": measurement_mode,
        "movement": movements[0] if movement_valid else None,
        "movement_identity_valid": movement_valid,
        "diagnostic_log_valid": bool(rows) and movement_valid,
    }


def _fatal_failure(row: dict[str, Any], previous_stage: object) -> str | None:
    """Return only a real ERROR transition; input_issue is diagnostic, not fatal."""
    value = row.get("failure_code")
    if (
        isinstance(value, str)
        and value
        and value.upper() not in {"NONE", "NULL"}
        and (row.get("stage") == "ERROR" or row.get("event") == "ERROR")
        and previous_stage != "ERROR"
    ):
        return value
    return None


def _attempts(rows: list[dict[str, Any]], synthetic: bool) -> list[dict[str, Any]]:
    if synthetic:
        return []
    attempts: list[dict[str, Any]] = []
    active: dict[str, Any] | None = None
    previous_stage: object = None

    def close(finish: str) -> None:
        nonlocal active
        if active is None:
            return
        if active["first_failure"] is not None:
            active["status"] = "ERROR"
            active["finish"] = "ERROR"
        elif active["candidate_count"] > 0 and finish in {"STOP", "CONFIRM", "READY"}:
            active["status"] = "COUNT"
            active["finish"] = "COUNT"
        else:
            active["status"] = finish
            active["finish"] = finish
        attempts.append(active)
        active = None

    for row in rows:
        event = row.get("event")
        if event == "CALIBRATE":
            if active is not None:
                close("RESET")
            active = {
                "start_seq": row["seq"],
                "finish": "ONGOING",
                "status": "ONGOING",
                "first_failure": None,
                "candidate_count": 0,
            }
        if active is None:
            previous_stage = row.get("stage")
            continue
        failure = _fatal_failure(row, previous_stage)
        if failure is not None and active["first_failure"] is None:
            input_issue = row.get("input_issue")
            active["first_failure"] = {
                "seq": row["seq"],
                "failure_code": failure,
                "reason": failure,
                "input_issue": input_issue if isinstance(input_issue, str) else None,
            }
        candidate = row.get("candidate_cycles")
        if isinstance(candidate, int) and not isinstance(candidate, bool):
            active["candidate_count"] = max(active["candidate_count"], candidate)
        if event in END_EVENTS:
            if event in {"ERROR", "RESET", "STOP"}:
                close(str(event))
            else:
                # READY/CONFIRM can be followed by candidate samples or STOP.  Keep the
                # control event pending so a later terminal event is not hidden.
                active["pending_finish"] = str(event)
        previous_stage = row.get("stage")
    if active is not None:
        if active["first_failure"] is not None:
            active["status"] = "ERROR"
            active["finish"] = "ERROR"
        elif active.get("pending_finish") is not None:
            finish = str(active["pending_finish"])
            if active["candidate_count"] > 0:
                active["status"] = "COUNT"
                active["finish"] = "COUNT"
            else:
                active["status"] = finish
                active["finish"] = finish
        attempts.append(active)
    return attempts


def _frame_summary(
    bundle: Path, rows: list[dict[str, Any]], trace_rows: list[dict[str, Any]]
) -> dict[str, Any]:
    trace_bindings: dict[tuple[str, int], str | None] = {}
    capture_keys: set[int] = set()
    for trace in trace_rows:
        seq = trace.get("seq")
        measured = trace.get("capture_ns")
        observed = trace.get("observation_capture_ns", measured)
        if _is_nonnegative_int(observed) and _is_nonnegative_int(seq):
            trace_bindings[(str(trace.get("session")), observed)] = trace.get(
                "input_rgb_sha256"
            )
            capture_keys.add(observed)
    if not trace_bindings:
        return {
            "rows": 0,
            "available": 0,
            "unavailable": 0,
            "unavailable_reasons": [],
            "association": "unavailable_legacy_timestamps",
        }
    seen: set[tuple[str, int, int]] = set()
    available = 0
    unavailable = 0
    reasons: list[str] = []
    for row in rows:
        capture_ns = row.get("capture_ns")
        row_session = row.get("session", "")
        seq = row.get("seq")
        if not _is_nonnegative_int(capture_ns):
            continue
        if "session" in row and isinstance(row_session, str):
            binding = trace_bindings.get((row_session, capture_ns))
            if (row_session, capture_ns) not in trace_bindings:
                reason = "capture_ns_not_in_trace"
            else:
                reason = None
                if binding is None:
                    reason = "input_rgb_sha256_unavailable"
                elif row.get("input_rgb_sha256") != binding:
                    reason = "input_rgb_sha256_mismatch"
        else:
            binding = None
            reason = None if capture_ns in capture_keys else "capture_ns_not_in_trace"
        key = (
            str(row_session),
            int(seq) if _is_nonnegative_int(seq) else -1,
            capture_ns,
        )
        if key in seen:
            continue
        seen.add(key)
        path = _safe_local_frame(bundle, row.get("image"), capture_ns)
        if reason is None:
            if path is None:
                reason = "unsafe_image_path"
            elif not path.is_file():
                reason = "image_missing"
        expected = row.get("jpeg_sha256")
        if reason is None and (
            not isinstance(expected, str) or SHA256_RE.fullmatch(expected) is None
        ):
            reason = "jpeg_sha256_invalid"
        if reason is None and path is not None:
            try:
                actual = hashlib.sha256(path.read_bytes()).hexdigest()
            except OSError:
                reason = "image_missing"
            else:
                if actual != expected:
                    reason = "jpeg_sha256_mismatch"
        if reason is None:
            available += 1
        else:
            unavailable += 1
            if reason not in reasons:
                reasons.append(reason)
    return {
        "rows": len(seen),
        "available": available,
        "unavailable": unavailable,
        "unavailable_reasons": reasons,
        "association": "capture_ns_exact",
    }


def _analyze(
    bundle: Path,
    session: str,
    trace_rows: list[dict[str, Any]],
    parsed_trace: JsonLines,
    frame_rows: list[dict[str, Any]],
    metadata: dict[str, Any] | None,
    end: dict[str, Any] | None,
) -> dict[str, Any]:
    unique = deduplicate_trace(trace_rows, session)
    synthetic = any(row.get("event") == "START_SYNTHETIC" for row in unique) or (
        isinstance(metadata, dict)
        and str(metadata.get("input", "")).upper() == "SYNTHETIC"
    )
    attempts = _attempts(unique, synthetic)
    end_error = (
        _validate_end(end, session, len(unique), len(frame_rows))
        if end is not None
        else None
    )
    complete = end is not None and end_error is None
    end_status = end.get("status") if isinstance(end, dict) else None
    status = (
        str(end_status)
        if isinstance(end_status, str) and complete
        else ("COMPLETE" if complete else "ONGOING")
    )
    if (
        complete
        and status == "COMPLETE"
        and attempts
        and attempts[-1]["status"] != "ONGOING"
    ):
        status = str(attempts[-1]["status"])
    trace = _trace_summary(unique, parsed_trace, max(0, len(trace_rows) - len(unique)))
    trace["trace_logger_dropped"] = (
        end.get("trace_logger_dropped", 0) if isinstance(end, dict) else 0
    )
    if isinstance(end, dict) and _is_nonnegative_int(end.get("dropped")):
        trace["bundle_dropped"] = max(trace["bundle_dropped"], end["dropped"])
    partial = bool(
        trace["missing_sequences"]
        or trace["writer_dropped"]
        or trace["bundle_dropped"]
        or trace["trace_logger_dropped"]
        or (
            isinstance(end, dict)
            and _is_nonnegative_int(end.get("dropped"))
            and end["dropped"] > 0
        )
    )
    summary: dict[str, Any] = {
        "session": session,
        "status": status,
        "complete": complete,
        "disconnect_complete": False,
        "active": not complete,
        "partial": partial,
        "end_error": end_error,
        "end": end,
        "synthetic": synthetic,
        "diagnostic_log_valid": trace["diagnostic_log_valid"],
        "trace": trace,
        "frames": _frame_summary(bundle, frame_rows, unique),
        "attempts": attempts,
    }
    return summary


def _validate_end(
    end: dict[str, Any] | None, session: str, trace_rows: int, frame_rows: int
) -> str | None:
    if not isinstance(end, dict):
        return "end_not_object"
    reasons = {
        "user_stop",
        "background",
        "duration",
        "session_cap",
        "total_cap",
        "io_error",
        "camera_error",
        "disabled",
    }
    if end.get("reason") not in reasons:
        return "end_reason_invalid"
    if end.get("durability") != "best_effort_local_debug":
        return "end_durability_invalid"
    fields = (
        "frames_offered",
        "frames_written",
        "traces_offered",
        "traces_written",
        "dropped",
        "trace_logger_dropped",
        "bytes_written",
    )
    if any(not _is_nonnegative_int(end.get(field)) for field in fields):
        return "end_accounting_invalid"
    if (
        end["frames_written"] > end["frames_offered"]
        or end["traces_written"] > end["traces_offered"]
    ):
        return "end_accounting_invalid"
    if end["traces_written"] != trace_rows or end["frames_written"] != frame_rows:
        return "end_accounting_mismatch"
    for field in ("first_capture_ns", "last_capture_ns"):
        if end.get(field) is not None and not _is_nonnegative_int(end.get(field)):
            return "end_capture_invalid"
    if (
        end.get("first_capture_ns") is not None
        and end.get("last_capture_ns") is not None
        and end["last_capture_ns"] < end["first_capture_ns"]
    ):
        return "end_capture_invalid"
    if end.get("session") not in (None, session):
        return "end_session_mismatch"
    return None


def analyze_session(bundle: Path) -> dict[str, Any]:
    """Analyze one local UUID bundle without treating missing frames as a bad trace."""
    if not bundle.is_dir() or not _is_uuid(bundle.name):
        raise FeedbackError("session bundle must be a UUID directory")
    trace_path = bundle / "trace.jsonl"
    parsed = read_complete_jsonl(trace_path) if trace_path.is_file() else JsonLines([])
    session = bundle.name
    trace_rows = [row for row in parsed.rows if row.get("session") == session]
    frame_path = bundle / "frames.jsonl"
    frame_rows = read_complete_jsonl(frame_path).rows if frame_path.is_file() else []
    return _analyze(
        bundle,
        session,
        trace_rows,
        parsed,
        frame_rows,
        _read_json(bundle / "session.json"),
        _read_json(bundle / "end.json"),
    )


def analyze_local(path: Path) -> dict[str, Any]:
    """Analyze a UUID bundle, a collector root, or an old standalone trace JSONL file."""
    path = Path(path)
    if path.is_file():
        parsed = read_complete_jsonl(path)
        grouped: dict[str, list[dict[str, Any]]] = {}
        for row in parsed.rows:
            session = row.get("session")
            if isinstance(session, str) and _is_uuid(session):
                grouped.setdefault(session, []).append(row)
        if len(grouped) <= 1:
            session = next(iter(grouped), "unknown")
            return _analyze(
                path.parent, session, grouped.get(session, []), parsed, [], None, None
            )
        return {
            "sessions": [
                _analyze(path.parent, session, rows, parsed, [], None, None)
                for session, rows in sorted(grouped.items())
            ]
        }
    if path.is_dir() and _is_uuid(path.name):
        return analyze_session(path)
    if not path.is_dir():
        raise FeedbackError(f"analysis path does not exist: {path}")
    sessions = [
        child for child in path.iterdir() if child.is_dir() and _is_uuid(child.name)
    ]
    return {
        "sessions": [
            analyze_session(child) for child in sorted(sessions, key=lambda p: p.name)
        ]
    }


def _existing_git_tree(path: Path) -> bool:
    absolute = Path(os.path.abspath(path))
    existing = absolute
    while not existing.exists() and existing != existing.parent:
        existing = existing.parent
    current = existing
    while True:
        if (current / ".git").exists():
            return True
        if current == current.parent:
            break
        current = current.parent
    try:
        result = subprocess.run(
            ["git", "-C", str(existing), "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=False,
            timeout=2,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0 and bool(result.stdout.strip())


def ensure_safe_output_dir(output: Path) -> Path:
    """Create a private output directory only when it is outside every existing Git tree."""
    path = Path(os.path.abspath(output))
    if _existing_git_tree(path):
        raise FeedbackError("output directory must be outside every Git tree")
    current = path
    missing: list[Path] = []
    while not current.exists():
        missing.append(current)
        current = current.parent
    for component in [current, *current.parents]:
        if component == Path(component.anchor):
            break
        if component.is_symlink():
            raise FeedbackError("output path contains a symlink")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or not path.is_dir():
        raise FeedbackError("output path is not a directory")
    os.chmod(path, 0o700)
    return path


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".partial", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        temporary.unlink(missing_ok=True)


def _append_rows(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    rows = list(rows)
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
    fd = os.open(path, flags, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "ab") as handle:
        for row in rows:
            handle.write(_json_bytes(row) + b"\n")
        handle.flush()
        os.fsync(handle.fileno())


def _bounded_process(
    command: Sequence[str], timeout: float, max_stdout: int
) -> tuple[int, bytes, bytes]:
    """Run one read with a hard stdout bound and a per-command deadline."""
    try:
        process = subprocess.Popen(
            list(command), stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False
        )
    except OSError as error:
        raise AdbDisconnected("adb command could not start") from error
    assert process.stdout is not None and process.stderr is not None
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, "stdout")
    selector.register(process.stderr, selectors.EVENT_READ, "stderr")
    stdout = bytearray()
    stderr = bytearray()
    deadline = time.monotonic() + timeout
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                process.kill()
                process.wait()
                raise AdbDisconnected("adb command timed out")
            for key, _ in selector.select(remaining):
                chunk = os.read(key.fd, 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                if key.data == "stdout":
                    if len(stdout) + len(chunk) > max_stdout:
                        process.kill()
                        process.wait()
                        raise FeedbackError("adb read exceeded its bound")
                    stdout.extend(chunk)
                elif len(stderr) < 8192:
                    stderr.extend(chunk[: 8192 - len(stderr)])
        returncode = process.wait(timeout=max(0.1, deadline - time.monotonic()))
    except subprocess.TimeoutExpired as error:
        process.kill()
        process.wait()
        raise AdbDisconnected("adb command timed out") from error
    finally:
        selector.close()
    return returncode, bytes(stdout), bytes(stderr)


class AdbClient:
    def __init__(self, adb: str, serial: str, timeout: float = 10.0) -> None:
        self.adb = adb
        self.serial = serial
        self.timeout = timeout

    def _run(self, args: Sequence[str], *, disconnectable: bool = True) -> bytes:
        command = [self.adb, "-s", self.serial, *args]
        try:
            returncode, stdout, stderr = _bounded_process(
                command, self.timeout, MAX_SESSION_BYTES + 1
            )
        except AdbDisconnected as error:
            if disconnectable:
                raise AdbDisconnected(
                    f"adb command did not complete: {' '.join(args)}"
                ) from error
            raise FeedbackError(
                f"adb command did not complete: {' '.join(args)}"
            ) from error
        if returncode != 0:
            detail = stderr.decode("utf-8", errors="replace").strip()
            if disconnectable:
                raise AdbDisconnected(
                    f"adb read failed: {detail or 'device disconnected'}"
                )
            raise FeedbackError(f"adb command failed: {detail or 'unknown error'}")
        return stdout

    def run_as(self, *args: str) -> bytes:
        return self._run(["exec-out", "run-as", PACKAGE, *args])

    def list_sessions(self) -> list[str]:
        output = self.run_as("ls", "-1", REMOTE_ROOT)
        sessions: list[str] = []
        for line in output.decode("utf-8", errors="strict").splitlines():
            if not line:
                continue
            if _is_uuid(line):
                detail = self.run_as("ls", "-ld", f"{REMOTE_ROOT}/{line}")
                if not detail.lstrip().startswith(b"d"):
                    raise FeedbackError("session child is not a directory")
                sessions.append(line)
        return sessions


class FeedbackCollector:
    def __init__(self, client: AdbClient, output: Path) -> None:
        self.client = client
        self.output = ensure_safe_output_dir(output)

    def _host_bytes(self) -> int:
        total = 0
        for path in self.output.rglob("*"):
            if path.is_file() and not path.is_symlink():
                total += path.stat().st_size
        return total

    def _session_bytes(self, local: Path) -> int:
        return sum(
            path.stat().st_size
            for path in local.rglob("*")
            if path.is_file() and not path.is_symlink()
        )

    def _reserve(
        self, local: Path, target: Path, size: int, *, append: bool = False
    ) -> None:
        previous = (
            target.stat().st_size if target.is_file() and not target.is_symlink() else 0
        )
        growth = size if append else max(0, size - previous)
        if self._session_bytes(local) + growth > MAX_SESSION_BYTES:
            raise FeedbackError(
                f"session {local.name} exceeds {MAX_SESSION_BYTES} bytes"
            )
        if self._host_bytes() + growth > MAX_HOST_BYTES:
            raise FeedbackError(f"host output exceeds {MAX_HOST_BYTES} bytes")

    def snapshot_session(self, session: str) -> None:
        if not _is_uuid(session):
            raise FeedbackError("remote session name is not a UUID")
        local = self.output / session
        local.mkdir(mode=0o700, exist_ok=True)
        os.chmod(local, 0o700)
        remote_prefix = f"{REMOTE_ROOT}/{session}"

        def read(leaf: str, *, optional: bool = False) -> bytes | None:
            try:
                payload = self.client.run_as("cat", f"{remote_prefix}/{leaf}")
            except AdbDisconnected:
                if optional:
                    return None
                raise
            return payload

        metadata_payload = read("session.json", optional=True)
        if metadata_payload:
            try:
                metadata = json.loads(metadata_payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise FeedbackError("session.json is not complete JSON") from error
            if not isinstance(metadata, dict):
                raise FeedbackError("session.json is not an object")
            metadata_bytes = _json_bytes(metadata)
            self._reserve(local, local / "session.json", len(metadata_bytes))
            _atomic_write(local / "session.json", metadata_bytes)

        trace_payload = read("trace.jsonl", optional=True) or b""
        parsed_trace = read_complete_jsonl(trace_payload)
        existing_trace = (
            read_complete_jsonl(local / "trace.jsonl")
            if (local / "trace.jsonl").is_file()
            else JsonLines([])
        )
        existing_keys = {
            (row.get("session"), row.get("seq"))
            for row in existing_trace.rows
            if row.get("session") == session and _is_nonnegative_int(row.get("seq"))
        }
        new_trace = []
        for row in parsed_trace.rows:
            key = (row.get("session"), row.get("seq"))
            if (
                row.get("session") == session
                and _is_nonnegative_int(row.get("seq"))
                and key not in existing_keys
            ):
                existing_keys.add(key)
                new_trace.append(row)
        trace_bytes = sum(len(_json_bytes(row)) + 1 for row in new_trace)
        self._reserve(local, local / "trace.jsonl", trace_bytes, append=True)
        _append_rows(local / "trace.jsonl", new_trace)

        frame_payload = read("frames.jsonl", optional=True) or b""
        parsed_frames = read_complete_jsonl(frame_payload)
        local_frame_path = local / "frames.jsonl"
        existing_frames = (
            read_complete_jsonl(local_frame_path)
            if local_frame_path.is_file()
            else JsonLines([])
        )
        existing_index: dict[tuple[str, int, int], dict[str, Any]] = {}
        complete_images: set[tuple[str, int, int]] = set()
        for row in existing_frames.rows:
            capture = row.get("capture_ns")
            if not _is_nonnegative_int(capture):
                continue
            row_session_value = row.get("session", session)
            row_session = (
                row_session_value if isinstance(row_session_value, str) else session
            )
            row_seq_value = row.get("seq", -1)
            row_seq = int(row_seq_value) if _is_nonnegative_int(row_seq_value) else -1
            frame_key = (row_session, row_seq, capture)
            existing_index[frame_key] = row
            local_image = _safe_local_frame(local, row.get("image"), capture)
            expected = row.get("jpeg_sha256")
            if (
                local_image is not None
                and local_image.is_file()
                and isinstance(expected, str)
                and SHA256_RE.fullmatch(expected)
                and hashlib.sha256(local_image.read_bytes()).hexdigest() == expected
            ):
                complete_images.add(frame_key)
        new_frames: list[dict[str, Any]] = []
        for row in parsed_frames.rows:
            capture_ns = row.get("capture_ns")
            if not _is_nonnegative_int(capture_ns):
                continue
            row_session_value = row.get("session", session)
            row_session = (
                row_session_value if isinstance(row_session_value, str) else session
            )
            row_seq_value = row.get("seq", -1)
            row_seq = int(row_seq_value) if _is_nonnegative_int(row_seq_value) else -1
            frame_key = (row_session, row_seq, capture_ns)
            if frame_key in complete_images:
                continue
            image = row.get("image")
            remote_image = _safe_remote_frame(session, image, capture_ns)
            if remote_image is not None:
                try:
                    image_payload = self.client.run_as("cat", remote_image)
                except AdbDisconnected:
                    image_payload = None
                if image_payload is not None:
                    expected = row.get("jpeg_sha256")
                    local_image = _safe_local_frame(local, image, capture_ns)
                    if (
                        local_image is not None
                        and isinstance(expected, str)
                        and SHA256_RE.fullmatch(expected)
                        and hashlib.sha256(image_payload).hexdigest() == expected
                    ):
                        self._reserve(local, local_image, len(image_payload))
                        _atomic_write(local_image, image_payload)
                        complete_images.add(frame_key)
            if frame_key not in existing_index:
                existing_index[frame_key] = row
                new_frames.append(row)
        frame_bytes = sum(len(_json_bytes(row)) + 1 for row in new_frames)
        self._reserve(local, local_frame_path, frame_bytes, append=True)
        _append_rows(local_frame_path, new_frames)

        end_payload = read("end.json", optional=True)
        if end_payload:
            try:
                end = json.loads(end_payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                end = None
            if isinstance(end, dict):
                end_bytes = _json_bytes(end)
                self._reserve(local, local / "end.json", len(end_bytes))
                _atomic_write(local / "end.json", end_bytes)
        summary = analyze_session(local)
        summary_bytes = _json_bytes(summary)
        report_bytes = (render_report(summary) + "\n").encode("utf-8")
        self._reserve(local, local / "report.json", len(summary_bytes))
        _atomic_write(local / "report.json", summary_bytes)
        self._reserve(local, local / "report.md", len(report_bytes))
        _atomic_write(local / "report.md", report_bytes)

    def collect_once(self) -> None:
        for session in self.client.list_sessions():
            try:
                self.snapshot_session(session)
            except AdbDisconnected:
                # A later poll will retry. No end marker is synthesized after a disconnect.
                continue

    def run(self, *, watch: bool, seconds: float, poll: float) -> dict[str, Any]:
        if not watch:
            self.collect_once()
            return analyze_local(self.output)
        deadline = time.monotonic() + seconds
        while True:
            try:
                self.collect_once()
            except AdbDisconnected:
                pass
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(poll, remaining))
        return analyze_local(self.output)


def _safe_remote_frame(session: str, image: object, capture_ns: int) -> str | None:
    if not _is_uuid(session) or not isinstance(image, str):
        return None
    match = FRAME_REF_RE.fullmatch(image)
    if match is None or match.group(1) != str(capture_ns):
        return None
    return f"{REMOTE_ROOT}/{session}/{image}"


def discover_device(
    adb: str, requested_serial: str | None = None, timeout: float = 10.0
) -> str:
    try:
        result = subprocess.run(
            [adb, "devices", "-l"],
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise FeedbackError("adb device discovery failed") from error
    if result.returncode != 0:
        raise FeedbackError("adb devices failed")
    candidates: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2 or parts[1] != "device":
            continue
        serial = parts[0]
        if SAFE_SERIAL_RE.fullmatch(serial) is None or serial.startswith("emulator-"):
            continue
        props: dict[str, str] = {}
        for prop in ("ro.product.manufacturer", "ro.product.model", "ro.kernel.qemu"):
            try:
                value = subprocess.run(
                    [adb, "-s", serial, "shell", "getprop", prop],
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=timeout,
                )
            except (OSError, subprocess.TimeoutExpired) as error:
                raise FeedbackError(f"getprop failed for {serial}") from error
            if value.returncode != 0:
                continue
            props[prop] = value.stdout.strip()
        if (
            props.get("ro.product.manufacturer", "").lower() == "samsung"
            and props.get("ro.product.model", "").upper().startswith("SM-S938")
            and props.get("ro.kernel.qemu", "0") != "1"
        ):
            candidates.append(serial)
    if len(candidates) != 1:
        raise FeedbackError(
            f"require exactly one authorized physical Samsung SM-S938 device, found {len(candidates)}"
        )
    if requested_serial is not None and requested_serial != candidates[0]:
        raise FeedbackError("--serial is not the discovered authorized device")
    return candidates[0]


def render_report(summary: dict[str, Any]) -> str:
    lines = [
        f"Session {summary.get('session', 'unknown')}: {summary.get('status', 'UNKNOWN')}"
    ]
    trace = summary.get("trace", {})
    lines.append(
        "  trace: rows={rows} duplicates={duplicates} gaps={gaps} drops={drops} suppressed={suppressed}".format(
            rows=trace.get("rows", 0),
            duplicates=trace.get("duplicates", 0),
            gaps=trace.get("missing_sequences", []),
            drops=trace.get("writer_dropped", 0),
            suppressed=trace.get("rate_suppressed", 0),
        )
    )
    frames = summary.get("frames", {})
    lines.append(
        f"  frames: available={frames.get('available', 0)} unavailable={frames.get('unavailable', 0)}"
    )
    for attempt in summary.get("attempts", []):
        line = f"  attempt seq {attempt['start_seq']}: {attempt['finish']} candidate_cycles={attempt['candidate_count']}"
        if attempt.get("first_failure"):
            failure = attempt["first_failure"]
            line += f" first_failure={failure['failure_code']}"
            if failure.get("input_issue"):
                line += f" input_issue={failure['input_issue']}"
        lines.append(line)
    return "\n".join(lines)


def _print_result(result: dict[str, Any]) -> None:
    print(json.dumps(result, indent=2, sort_keys=True))
    sessions_value = result.get("sessions")
    sessions = (
        [summary for summary in sessions_value if isinstance(summary, dict)]
        if isinstance(sessions_value, list)
        else [result]
    )
    for summary in sessions:
        print(render_report(summary))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--seconds", type=float, default=DEFAULT_SECONDS)
    parser.add_argument("--poll", type=float, default=DEFAULT_POLL_SECONDS)
    parser.add_argument("--analyze-local", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.analyze_local is not None:
            if (
                any(value is not None for value in (args.output, args.serial))
                or args.watch
            ):
                parser.error(
                    "--analyze-local cannot be combined with collection options"
                )
            _print_result(analyze_local(args.analyze_local))
            return 0
        if args.output is None:
            parser.error("--output is required unless --analyze-local is used")
        if args.seconds < 0 or args.poll <= 0:
            parser.error("--seconds must be non-negative and --poll must be positive")
        serial = discover_device(args.adb, args.serial)
        result = FeedbackCollector(AdbClient(args.adb, serial), args.output).run(
            watch=args.watch, seconds=args.seconds, poll=args.poll
        )
        _print_result(result)
        return 0
    except FeedbackError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
