"""Project selected historical movement reports without rerunning any detector."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import sys
from pathlib import Path
from typing import Any, NoReturn

from offline_movement_feedback import private_output, write_json

ENGINES = frozenset({"squat", "lunge", "jumping_jack", "plank", "situp"})
# Each engine names its own missingness counter; the range checks are identical.
UNUSABLE_FIELDS = {"situp": "frames_missing_torso_elevation_geometry"}
UNUSABLE_DEFAULT = "unusable_frames"
CASE_KEYS = frozenset({"id", "engine", "report", "report_sha256", "missing_reason"})
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
REP_FIELDS = [
    "predicted",
    "annotated",
    "matched",
    "missed",
    "extra",
    "precision",
    "recall",
    "missed_event_indices",
    "extra_timestamps_ns",
    "miss_diagnostics",
    "events_ns",
    "frame_count",
    "unusable_frames",
    "frames_missing_torso_elevation_geometry",
    "reason_counts",
]
HOLD_FIELDS = [
    "predicted_holds",
    "annotated_intervals",
    "missed_label_indices",
    "label_matches",
    "hold_diagnostics",
    "extra_hold_count",
    "censored_hold_count",
    "frame_count",
    "unusable_frames",
    "reason_counts",
]


def _reject_constant(value: str) -> NoReturn:
    raise ValueError(f"nonfinite JSON value: {value}")


def _json_bytes(raw: bytes) -> Any:
    return json.loads(raw.decode("utf-8"), parse_constant=_reject_constant)


def _count(report: dict[str, Any], name: str, *, positive: bool = False) -> int:
    value = report.get(name)
    if type(value) is not int or value < (1 if positive else 0):
        raise ValueError(f"report {name} must be an integer with a valid range")
    return value


def _unusable_field(engine: str) -> str:
    return UNUSABLE_FIELDS.get(engine, UNUSABLE_DEFAULT)


def _validate_shape(report: dict[str, Any], engine: str) -> None:
    counter = "predicted_holds" if engine == "plank" else "predicted"
    predicted = _count(report, counter)
    frame_count = _count(report, "frame_count", positive=True)
    unusable_field = _unusable_field(engine)
    unusable = _count(report, unusable_field)
    if unusable > frame_count:
        raise ValueError(f"report {unusable_field} exceeds frame_count")
    if report["status"] != "EXPLORATORY_COMPARISON":
        return
    if engine == "plank":
        for name in ("annotated_intervals", "extra_hold_count", "censored_hold_count"):
            _count(report, name)
        if any(
            not isinstance(report.get(name), list)
            for name in ("missed_label_indices", "label_matches", "hold_diagnostics")
        ):
            raise ValueError("hold report requires list fields")
        return
    annotated, matched = _count(report, "annotated"), _count(report, "matched")
    missed, extra = _count(report, "missed"), _count(report, "extra")
    if matched + missed != annotated or matched + extra != predicted:
        raise ValueError(
            "matched + missed must equal annotated and matched + extra must equal predicted"
        )


def _load_manifest(path: Path) -> tuple[dict[str, Any], str]:
    raw = path.read_bytes()
    value = _json_bytes(raw)
    if (
        not isinstance(value, dict)
        or type(value.get("version")) is not int
        or value["version"] != 1
    ):
        raise ValueError("manifest version must be 1")
    cases = value.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("manifest cases must be nonempty")
    identifiers: set[str] = set()
    for case in cases:
        if not isinstance(case, dict) or set(case) - CASE_KEYS:
            raise ValueError("each case must use the exact manifest fields")
        identifier, engine = case.get("id"), case.get("engine")
        if not isinstance(identifier, str) or not identifier.strip():
            raise ValueError("case id must be nonempty")
        if identifier in identifiers:
            raise ValueError(f"duplicate case id: {identifier}")
        if not isinstance(engine, str) or engine not in ENGINES:
            raise ValueError("unsupported engine")
        identifiers.add(identifier)
        has_report = "report" in case or "report_sha256" in case
        has_missing = "missing_reason" in case
        if has_report and has_missing:
            raise ValueError("report and missing_reason are mutually exclusive")
        if not has_report and not has_missing:
            raise ValueError("case requires report or missing_reason")
        if has_report:
            if (
                not isinstance(case.get("report"), str)
                or not Path(case["report"]).is_absolute()
            ):
                raise ValueError("report must be an absolute path")
            if not isinstance(case.get("report_sha256"), str) or not SHA256.fullmatch(
                case["report_sha256"]
            ):
                raise ValueError("report_sha256 must be a lowercase SHA-256 digest")
        elif (
            not isinstance(case.get("missing_reason"), str)
            or not case["missing_reason"].strip()
        ):
            raise ValueError("missing_reason must be nonempty")
    return value, hashlib.sha256(raw).hexdigest()


def _load_report(case: dict[str, Any]) -> dict[str, Any]:
    path = Path(case["report"])
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise ValueError(f"report is unreadable: {path}") from error
    actual = hashlib.sha256(raw).hexdigest()
    if actual != case["report_sha256"]:
        raise ValueError(f"report hash mismatch: {path}")
    try:
        report = _json_bytes(raw)
    except (UnicodeDecodeError, ValueError) as error:
        raise ValueError(f"malformed report JSON: {path}") from error
    if not isinstance(report, dict) or report.get("status") not in {
        "EXPLORATORY_COMPARISON",
        "UNLABELLED",
    }:
        raise ValueError(f"report status is malformed: {path}")
    _validate_shape(report, case["engine"])
    return report


def _fields(report: dict[str, Any], names: list[str]) -> dict[str, Any]:
    return {
        name: report[name]
        for name in names
        if name in report and report[name] is not None
    }


def _report_row(case: dict[str, Any]) -> dict[str, Any]:
    report = _load_report(case)
    runtime = report.get("runtime")
    source = runtime.get("source") if isinstance(runtime, dict) else None
    development = report.get("development") or (
        source.get("split") if isinstance(source, dict) else None
    )
    names = HOLD_FIELDS if case["engine"] == "plank" else REP_FIELDS
    metrics = _fields(report, names)
    provenance = {"report_path": case["report"], "report_sha256": case["report_sha256"]}
    row: dict[str, Any] = {
        "id": case["id"],
        "engine": case["engine"],
        "status": report["status"],
        "verdict": "UNMEASURED"
        if report["status"] == "UNLABELLED"
        else "COMPARISON_RECORDED",
        "runtime": runtime,
        "development": development,
        "annotation_method": report.get("annotation_method"),
        "report_path": case["report"],
        "report_sha256": case["report_sha256"],
        "provenance": provenance,
        "metrics": metrics,
        "original_report": report,
    }
    row["hold_metrics" if case["engine"] == "plank" else "rep_metrics"] = metrics
    return row


def _missing_row(case: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": case["id"],
        "engine": case["engine"],
        "status": "UNMEASURED",
        "missing_reason": case["missing_reason"],
    }


def build_scorecard(manifest: Path) -> dict[str, Any]:
    """Load exactly the listed reports and return a historical, non-pooled snapshot."""
    value, digest = _load_manifest(manifest)
    rows = [
        _missing_row(case) if "missing_reason" in case else _report_row(case)
        for case in value["cases"]
    ]
    return {
        "version": 1,
        "status": "HISTORICAL_SCORECARD",
        "manifest_sha256": digest,
        "manifest": value,
        "cases": rows,
        "rep_rows": [row for row in rows if row["engine"] != "plank"],
        "hold_rows": [row for row in rows if row["engine"] == "plank"],
        "interpretation": (
            "Historical report projection only; no pooled accuracy, pass/fail, production-admission, "
            "or held-out reassignment claim. UNLABELLED and UNMEASURED remain unmeasured."
        ),
    }


def _cell(value: Any) -> str:
    if value is None:
        text = "UNMEASURED"
    elif isinstance(value, str):
        text = value
    else:
        text = json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
    return html.escape(text, quote=False).replace("|", "&#124;").replace("\n", "<br>")


def _render_table(rows: list[dict[str, Any]], hold: bool) -> list[str]:
    if hold:
        header = "| case | engine | status | runtime | split | predicted_holds | annotated_intervals | missed_labels | extra_holds | coverage | out_of_label_ns | censored | unusable/frame |\n|---|---|---|---|---|---:|---:|---|---:|---|---|---:|---|"
    else:
        header = "| case | engine | status | runtime | split | predicted | annotated | matched | missed | extra | unusable/frame |\n|---|---|---|---|---|---:|---:|---:|---:|---:|---|"
    lines = [header]
    for row in rows:
        metrics = row.get("hold_metrics" if hold else "rep_metrics", {})
        runtime = row.get("runtime")
        runtime_label = runtime.get("runtime") if isinstance(runtime, dict) else runtime
        source = runtime.get("source") if isinstance(runtime, dict) else None
        split = (
            source.get("split") if isinstance(source, dict) else row.get("development")
        )
        frame = metrics.get("frame_count")
        unusable = metrics.get(_unusable_field(row["engine"]))
        signal = (
            f"{unusable}/{frame}"
            if frame is not None and unusable is not None
            else None
        )
        cells = [row["id"], row["engine"], row["status"], runtime_label, split]
        if hold:
            cells += [
                metrics.get("predicted_holds"),
                metrics.get("annotated_intervals"),
                metrics.get("missed_label_indices"),
                metrics.get("extra_hold_count"),
                [
                    match.get("coverage_ratio")
                    for match in metrics.get("label_matches", [])
                ],
                [
                    diagnostic.get("unlabelled_ns")
                    for diagnostic in metrics.get("hold_diagnostics", [])
                ],
                metrics.get("censored_hold_count"),
                signal,
            ]
        else:
            cells += [
                metrics.get(name)
                for name in ("predicted", "annotated", "matched", "missed", "extra")
            ] + [signal]
        lines.append("| " + " | ".join(_cell(cell) for cell in cells) + " |")
    return lines


def _render_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Historical offline movement scorecard",
        "",
        "Historical projection of explicitly selected reports. This is not a fresh replay, accuracy/pass result, production-admission decision, or held-out evaluation.",
        "Detailed annotation_method, provenance, and original report metrics/reasons are in summary.json by case.",
        "",
        f"Manifest SHA-256: `{summary['manifest_sha256']}`",
        "",
        "## Repetition reports",
        "",
        *_render_table(summary["rep_rows"], hold=False),
        "",
        "## Hold reports (not pooled with repetitions)",
        "",
        *_render_table(summary["hold_rows"], hold=True),
        "",
        "## Explicitly unmeasured cases",
        "",
    ]
    for row in summary["cases"]:
        if row["status"] == "UNMEASURED":
            lines.append(
                f"- **{_cell(row['id'])}** ({_cell(row['engine'])}): {_cell(row.get('missing_reason', 'UNLABELLED report'))}"
            )
    lines.extend(["", "## Hold details", ""])
    for row in summary["hold_rows"]:
        for diagnostic in row.get("metrics", {}).get("hold_diagnostics", []):
            lines.append(f"- {_cell(row['id'])}: {_cell(diagnostic)}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        summary = build_scorecard(args.manifest)
        markdown = _render_markdown(summary)
        output = private_output(args.output_dir)
        output.mkdir(parents=True, exist_ok=False, mode=0o700)
        with (output / "scorecard.md").open("x", encoding="utf-8") as file:
            file.write(markdown)
        (output / "scorecard.md").chmod(0o600)
        write_json(output / "summary.json", summary)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps({"status": summary["status"], "cases": len(summary["cases"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
