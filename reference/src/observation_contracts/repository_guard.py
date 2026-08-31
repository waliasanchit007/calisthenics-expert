"""Prevent prohibited study data and unsafe fixture declarations entering Git."""

from __future__ import annotations

import hashlib
import json
import os
import posixpath
import re
import subprocess
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Literal

from observation_contracts.errors import ValidationIssue
from observation_contracts.schema_loader import load_strict_json

GuardMode = Literal["tracked", "staged"]

_MAX_SIGNATURE_BYTES = 64 * 1024
_HASH_CHUNK_BYTES = 64 * 1024
_CONTENT_SCAN_TAIL_BYTES = 128
_MEDIA_SUFFIXES = {
    ".aac",
    ".aiff",
    ".avi",
    ".caf",
    ".flac",
    ".m4a",
    ".m4v",
    ".mkv",
    ".mov",
    ".mp3",
    ".mp4",
    ".ogg",
    ".opus",
    ".wav",
    ".webm",
}
_PRIVATE_KEY_SUFFIXES = {".der", ".key", ".pem", ".p12", ".pfx"}
# Model binaries are fetched into an ignored cache and pinned by SHA-256 in a tracked provenance
# manifest, per design section 13. `.gitignore` alone is advisory: it is scoped to one directory
# and `git add -f` bypasses it entirely. The guard fails closed wherever the blob is placed.
_MODEL_BINARY_SUFFIXES = {".onnx", ".task", ".tflite"}
_DOCUMENT_SUFFIXES = {".adoc", ".md", ".rst", ".txt"}
_POSE_SUFFIXES = {".csv", ".json", ".jsonl", ".ndjson", ".tsv"}
_KNOWN_SECRET_NAMES = {
    "auth.json",
    "credentials.json",
    "credentials.yaml",
    "credentials.yml",
    "secrets.json",
    "secrets.yaml",
    "secrets.yml",
    "service-account.json",
    "token.json",
}
_NORMALIZED_IDENTITY_KEY_DIRECTORIES = {
    "identitykey",
    "identitykeys",
    "identitieskey",
    "identitieskeys",
}
_PRIVATE_KEY_RE = re.compile(
    rb"-----BEGIN (?:[A-Z0-9 ]*PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY)-----"
)
_SECRET_CONTENT_RE = re.compile(
    rb"(?i)(?:\"(?:private_key|client_secret|access_token|refresh_token|api_key|password)\"\s*:|"
    rb"(?:AWS_SECRET_ACCESS_KEY|PRIVATE_KEY|CLIENT_SECRET|ACCESS_TOKEN|API_KEY)\s*=)"
)
_SECRET_JSON_KEY_AT_END_RE = re.compile(
    rb'(?i)"(?:private_key|client_secret|access_token|refresh_token|api_key|password)"\s*$'
)
_SECRET_ASSIGNMENT_KEY_AT_END_RE = re.compile(
    rb"(?i)(?:AWS_SECRET_ACCESS_KEY|PRIVATE_KEY|CLIENT_SECRET|ACCESS_TOKEN|API_KEY)\s*$"
)
_POSE_CONTENT_RE = re.compile(
    rb'"(?:normalized_image_landmarks|world_landmarks|pose_present|capture_timestamp_ns)"\s*:'
)
_POSE_JSON_KEY_AT_END_RE = re.compile(
    rb'"(?:normalized_image_landmarks|world_landmarks|pose_present|capture_timestamp_ns)"\s*$'
)
_SEMVER_RE = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_ERROR_CODE_RE = re.compile(r"^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\.[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$")
_ERROR_CODE_REGISTRY_PATH = "contracts/tooling/validator_error_codes.v1.json"
_ANDROID_STORAGE_FACTORY_PATH = (
    "apps/diagnostic-harness/shared/src/androidMain/kotlin/"
    "com/calisthenics/diagnostic/export/AndroidStorageRoots.kt"
)
_ANDROID_PRODUCTION_SOURCE_RE = re.compile(
    r"^apps/diagnostic-harness/(?:androidApp/src/main|shared/src/androidMain)/.+\.kt$"
)
_ANDROID_STORAGE_BYPASS_RE = re.compile(
    rb"(?:\b(?:context|applicationContext)\.filesDir\b|\.getDir\s*\(|"
    rb"\bStorageRoots\s*\(|diagnostic-harness)"
)
_ANDROID_PURGE_SOURCE_RE = re.compile(
    r"^apps/diagnostic-harness/(?:androidApp/src/main|shared/src/androidMain)/.+/purge/.+\.kt$"
)
_ANDROID_UNSAFE_PURGE_DELETE_RE = re.compile(
    rb"(?:\bdeleteRecursively\s*\(|\bFOLLOW_LINKS\b|\bFiles\.walkFileTree\s*\()"
)
_M05_SESSION_ID_GENERATOR_PATH = (
    "apps/diagnostic-harness/androidApp/src/main/kotlin/"
    "com/calisthenics/diagnostic/composition/M05SessionIdGenerator.kt"
)
_INSECURE_SESSION_ID_ENTROPY_RE = re.compile(
    rb"(?:\bjava\.util\.Random\b|\bRandom\s*\(|\bSystem\.(?:nanoTime|currentTimeMillis)\b|\bAtomic(?:Integer|Long)\b)"
)
_POSITIVE_SESSION_ARTIFACT_NAMES = frozenset(
    {
        "manifest.json",
        "frames.ndjson",
        "annotation_independent_a.json",
        "annotation_independent_b.json",
        "annotation_adjudicated.json",
    }
)


@dataclass(frozen=True)
class _FixtureGroup:
    kind: Literal["positive", "negative", "preprocessing"]
    root: str


class _RepositoryContent:
    """Read either the worktree or the exact contents represented by the index."""

    def __init__(self, root: Path, mode: GuardMode) -> None:
        self.root = root
        self.mode = mode

    def read_prefix(self, relative_path: str) -> bytes | None:
        if self.mode == "tracked":
            path = self.root / Path(*relative_path.split("/"))
            try:
                with path.open("rb") as stream:
                    return stream.read(_MAX_SIGNATURE_BYTES)
            except OSError:
                return None

        process = subprocess.Popen(
            ["git", "-C", str(self.root), "cat-file", "blob", f":{relative_path}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        assert process.stdout is not None
        try:
            prefix = process.stdout.read(_MAX_SIGNATURE_BYTES)
        finally:
            process.kill()
            process.wait()
        return prefix if process.returncode == 0 or prefix else None

    def iter_bytes(self, relative_path: str) -> Iterator[bytes]:
        if self.mode == "tracked":
            path = self.root / Path(*relative_path.split("/"))
            with path.open("rb") as stream:
                while chunk := stream.read(_HASH_CHUNK_BYTES):
                    yield chunk
            return

        process = subprocess.Popen(
            ["git", "-C", str(self.root), "cat-file", "blob", f":{relative_path}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        assert process.stdout is not None
        try:
            while chunk := process.stdout.read(_HASH_CHUNK_BYTES):
                yield chunk
        finally:
            process.stdout.close()
            process.wait()
        if process.returncode != 0:
            raise OSError(f"cannot read indexed path {relative_path}")


def guard_repository(root: Path, mode: GuardMode = "tracked") -> list[ValidationIssue]:
    """Return deterministic issues for prohibited tracked or staged repository data."""
    if mode not in {"tracked", "staged"}:
        raise ValueError(f"unsupported repository guard mode: {mode}")

    repository_root = root.resolve()
    selected_paths = _git_paths(repository_root, mode)
    inventory_paths = _git_paths(repository_root, "tracked") if mode == "staged" else selected_paths
    source = _RepositoryContent(repository_root, mode)
    issues: list[ValidationIssue] = []
    safe_paths: list[str] = []

    for relative_path in selected_paths:
        path_issue = _path_issue(repository_root, relative_path, source, mode)
        if path_issue is not None:
            issues.append(path_issue)
        else:
            safe_paths.append(relative_path)

    fixture_admissions, fixture_issues = _fixture_admissions(
        repository_root, inventory_paths, source, _load_error_code_registry(source)
    )
    issues.extend(fixture_issues)

    for relative_path in safe_paths:
        storage_factory_issue = _android_storage_factory_issue(source, relative_path)
        if storage_factory_issue is not None:
            issues.append(storage_factory_issue)
            continue
        unsafe_purge_issue = _android_purge_delete_issue(source, relative_path)
        if unsafe_purge_issue is not None:
            issues.append(unsafe_purge_issue)
            continue
        session_id_entropy_issue = _m05_session_id_entropy_issue(source, relative_path)
        if session_id_entropy_issue is not None:
            issues.append(session_id_entropy_issue)
            continue
        group = _fixture_group(relative_path)
        if group is not None:
            admission = fixture_admissions.get(group.root, False)
            if admission is False:
                continue
            if isinstance(admission, frozenset) and relative_path not in admission:
                issues.append(
                    _declaration_issue(
                        relative_path, "path is not declared by the positive fixture"
                    )
                )
                continue
            if _has_prohibited_suffix(relative_path):
                issues.append(_path_issue_for(relative_path, "prohibited media or key suffix"))
            continue

        prohibited = _stream_content_issue(source, relative_path)
        if prohibited is not None:
            issues.append(prohibited)

    return sorted(issues, key=lambda issue: (issue.artifact_path, issue.code, issue.message))


def _android_storage_factory_issue(
    source: _RepositoryContent, relative_path: str
) -> ValidationIssue | None:
    if (
        relative_path == _ANDROID_STORAGE_FACTORY_PATH
        or _ANDROID_PRODUCTION_SOURCE_RE.fullmatch(relative_path) is None
    ):
        return None
    prefix = source.read_prefix(relative_path)
    if prefix is None or _ANDROID_STORAGE_BYPASS_RE.search(prefix) is None:
        return None
    return _content_issue_for(
        relative_path,
        "Android app-private roots must be resolved through AndroidStorageRoots",
    )


def _android_purge_delete_issue(
    source: _RepositoryContent, relative_path: str
) -> ValidationIssue | None:
    if _ANDROID_PURGE_SOURCE_RE.fullmatch(relative_path) is None:
        return None
    prefix = source.read_prefix(relative_path)
    if prefix is None or _ANDROID_UNSAFE_PURGE_DELETE_RE.search(prefix) is None:
        return None
    return _content_issue_for(
        relative_path,
        "Android purge code must use no-follow explicit bottom-up deletion",
    )


def _m05_session_id_entropy_issue(
    source: _RepositoryContent, relative_path: str
) -> ValidationIssue | None:
    if relative_path != _M05_SESSION_ID_GENERATOR_PATH:
        return None
    prefix = source.read_prefix(relative_path)
    if prefix is None or _INSECURE_SESSION_ID_ENTROPY_RE.search(prefix) is None:
        return None
    return _content_issue_for(
        relative_path,
        "M0.5 session IDs must use SecureRandom-derived 48-bit suffixes",
    )


def _git_paths(root: Path, mode: GuardMode) -> list[str]:
    if mode == "tracked":
        command = ["git", "-C", str(root), "ls-files", "-z"]
    else:
        command = [
            "git",
            "-C",
            str(root),
            "diff",
            "--cached",
            "--name-only",
            "--diff-filter=ACMR",
            "-z",
        ]
    result = subprocess.run(command, check=True, capture_output=True)
    return sorted(os.fsdecode(path) for path in result.stdout.split(b"\0") if path)


def _path_issue(
    root: Path, relative_path: str, source: _RepositoryContent, mode: GuardMode
) -> ValidationIssue | None:
    if not _is_lexically_safe(relative_path):
        return _path_issue_for(relative_path, "path is absolute or traverses its repository root")

    candidate = root / Path(*relative_path.split("/"))
    if mode == "tracked" or candidate.is_symlink():
        resolved = candidate.resolve(strict=False)
        if not _is_within(root, resolved):
            return _path_issue_for(relative_path, "symlink resolves outside the repository")

    if _has_prohibited_suffix(relative_path):
        return _path_issue_for(relative_path, "prohibited media or key suffix")
    if _has_prohibited_filename(relative_path):
        return _path_issue_for(
            relative_path, "prohibited credential, consent, or identity filename"
        )

    if mode == "staged" and _staged_symlink_escapes(root, relative_path, source):
        return _path_issue_for(relative_path, "indexed symlink resolves outside the repository")
    return None


def _is_within(root: Path, candidate: Path) -> bool:
    return candidate == root or root in candidate.parents


def _is_lexically_safe(relative_path: str) -> bool:
    path = PurePosixPath(relative_path)
    return not path.is_absolute() and ".." not in path.parts and "" not in path.parts


def _staged_symlink_escapes(root: Path, relative_path: str, source: _RepositoryContent) -> bool:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--stage", "-z", "--", relative_path],
        check=True,
        capture_output=True,
    )
    for entry in result.stdout.split(b"\0"):
        if not entry:
            continue
        header, _tab, _path = entry.partition(b"\t")
        if not header.startswith(b"120000 "):
            return False
        target_bytes = source.read_prefix(relative_path)
        if target_bytes is None:
            return False
        target = os.fsdecode(target_bytes.split(b"\0", 1)[0])
        resolved = (root / Path(*relative_path.split("/")).parent / target).resolve(strict=False)
        return not _is_within(root, resolved)
    return False


def _has_prohibited_suffix(relative_path: str) -> bool:
    return (
        Path(relative_path).suffix.lower()
        in _MEDIA_SUFFIXES | _PRIVATE_KEY_SUFFIXES | _MODEL_BINARY_SUFFIXES
    )


def _has_prohibited_filename(relative_path: str) -> bool:
    path = PurePosixPath(relative_path)
    if any(
        re.sub(r"[^a-z0-9]", "", component.lower()) in _NORMALIZED_IDENTITY_KEY_DIRECTORIES
        for component in path.parts[:-1]
    ):
        return True

    name = path.name.lower()
    if name == ".env" or name.startswith(".env.") or name in _KNOWN_SECRET_NAMES:
        return True

    tokens = set(filter(None, re.split(r"[^a-z0-9]+", path.stem.lower())))
    if path.suffix.lower() in _DOCUMENT_SUFFIXES:
        return False
    if tokens & {"consent", "participant", "identity", "identities", "pseudonym"}:
        return True
    return "private" in tokens and "key" in tokens


def _content_issue(relative_path: str, content: bytes) -> ValidationIssue | None:
    if _PRIVATE_KEY_RE.search(content) or _SECRET_CONTENT_RE.search(content):
        return _content_issue_for(relative_path, "private-key or credential signature")

    return _pose_content_issue(relative_path, _POSE_CONTENT_RE.search(content) is not None)


def _pose_content_issue(
    relative_path: str, looks_like_pose_content: bool
) -> ValidationIssue | None:
    path = PurePosixPath(relative_path)
    lower_path = relative_path.lower()
    is_schema = lower_path.startswith("contracts/schema/") or path.name.endswith(".schema.json")
    path_tokens = set(filter(None, re.split(r"[^a-z0-9]+", path.stem.lower())))
    looks_like_pose_path = bool(path_tokens & {"pose", "poses", "landmark", "landmarks"})
    if (
        path.suffix.lower() in _POSE_SUFFIXES
        and not is_schema
        and (looks_like_pose_path or looks_like_pose_content)
    ):
        return _path_issue_for(
            relative_path, "raw pose or landmark dump outside synthetic fixtures"
        )
    return None


def _stream_content_issue(source: _RepositoryContent, relative_path: str) -> ValidationIssue | None:
    tail = b""
    pending_secret_delimiter: bytes | None = None
    pending_pose_key = False
    try:
        for chunk in source.iter_bytes(relative_path):
            window = tail + chunk
            prohibited = _content_issue(relative_path, window)
            if prohibited is not None:
                return prohibited

            remainder = chunk.lstrip()
            if pending_secret_delimiter is not None:
                if remainder.startswith(pending_secret_delimiter):
                    return _content_issue_for(relative_path, "private-key or credential signature")
                if remainder:
                    pending_secret_delimiter = None
            if pending_pose_key:
                if remainder.startswith(b":"):
                    prohibited = _pose_content_issue(relative_path, True)
                    if prohibited is not None:
                        return prohibited
                if remainder:
                    pending_pose_key = False

            if _SECRET_JSON_KEY_AT_END_RE.search(window):
                pending_secret_delimiter = b":"
            elif _SECRET_ASSIGNMENT_KEY_AT_END_RE.search(window):
                pending_secret_delimiter = b"="
            if _POSE_JSON_KEY_AT_END_RE.search(window):
                pending_pose_key = True
            tail = window[-_CONTENT_SCAN_TAIL_BYTES:]
    except OSError:
        return None
    return None


def _fixture_group(relative_path: str) -> _FixtureGroup | None:
    parts = PurePosixPath(relative_path).parts
    if len(parts) < 3 or parts[:2] != ("fixtures", "synthetic"):
        return None
    if parts[2] == "valid":
        if len(parts) >= 5:
            return _FixtureGroup("positive", "/".join(parts[:4]))
        return _FixtureGroup("positive", "fixtures/synthetic/valid")
    if parts[2] == "preprocessing":
        if len(parts) == 4:
            return _FixtureGroup("preprocessing", "fixtures/synthetic/preprocessing")
        return _FixtureGroup("preprocessing", "/".join(parts[:4]))
    if parts[2] == "negative":
        if len(parts) < 4:
            return _FixtureGroup("negative", "fixtures/synthetic/negative")
        return _FixtureGroup("negative", "/".join(parts[:4]))
    return _FixtureGroup("positive", "fixtures/synthetic")


def _fixture_admissions(
    root: Path,
    inventory_paths: Iterable[str],
    source: _RepositoryContent,
    error_codes: frozenset[str] | None,
) -> tuple[dict[str, bool | frozenset[str]], list[ValidationIssue]]:
    paths = sorted(inventory_paths)
    groups: dict[str, _FixtureGroup] = {}
    descriptors: dict[str, list[str]] = {}
    for relative_path in paths:
        group = _fixture_group(relative_path)
        if group is None:
            continue
        groups[group.root] = group
        if _is_descriptor(relative_path, group.kind):
            descriptors.setdefault(group.root, []).append(relative_path)

    admissions: dict[str, bool | frozenset[str]] = {}
    issues: list[ValidationIssue] = []
    for group_root in sorted(groups):
        group = groups[group_root]
        candidates = descriptors.get(group_root, [])
        if not candidates:
            admissions[group_root] = False
            first_path = next(path for path in paths if _fixture_group(path) == group)
            issues.append(
                _declaration_issue(first_path, "approved fixture class has no declaration")
            )
            continue

        valid = False
        allowed_paths: set[str] = set()
        for descriptor in candidates:
            error, descriptor_paths = _validate_fixture_declaration(
                root, group, descriptor, paths, source, error_codes
            )
            if error is None:
                valid = True
                allowed_paths.update(descriptor_paths)
            else:
                issues.append(_declaration_issue(descriptor, error))
        admissions[group_root] = (
            frozenset(allowed_paths) if group.kind == "positive" and valid else valid
        )
    return admissions, issues


def _is_descriptor(relative_path: str, kind: str) -> bool:
    name = PurePosixPath(relative_path).name.lower()
    if kind == "positive":
        return name == "fixture_metadata.json"
    if kind == "negative":
        return name == "expected_failure.json"
    return name in {"preprocessing_vector.json", "preprocessing_vector.v1.json", "vector.json"}


def _validate_fixture_declaration(
    root: Path,
    group: _FixtureGroup,
    descriptor_path: str,
    inventory_paths: list[str],
    source: _RepositoryContent,
    error_codes: frozenset[str] | None,
) -> tuple[str | None, frozenset[str]]:
    raw = source.read_prefix(descriptor_path)
    if raw is None:
        return "declaration cannot be read", frozenset()
    try:
        document = load_strict_json(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return "declaration is not valid UTF-8 JSON", frozenset()
    if not isinstance(document, Mapping):
        return "declaration must be a JSON object", frozenset()
    if group.kind == "positive":
        return _validate_positive_fixture(
            document, descriptor_path, group.root, inventory_paths, source
        )
    if group.kind == "negative":
        return (
            _validate_negative_fixture(document, group.root, inventory_paths, error_codes),
            frozenset(),
        )
    return (
        _validate_preprocessing_vector(
            document, descriptor_path, group.root, inventory_paths, source
        ),
        frozenset(),
    )


def _validate_positive_fixture(
    document: Mapping[str, Any],
    descriptor_path: str,
    group_root: str,
    inventory_paths: list[str],
    source: _RepositoryContent,
) -> tuple[str | None, frozenset[str]]:
    required = {
        "fixture_metadata_schema_version",
        "fixture_id",
        "synthetic",
        "preprocessing_contract_version",
        "frame_width",
        "frame_height",
        "pixel_format",
        "color_space",
        "color_range",
        "channel_order",
        "orientation",
        "frames",
    }
    missing = sorted(required - document.keys())
    if missing:
        return f"fixture metadata is missing required fields: {', '.join(missing)}", frozenset()
    if document["synthetic"] is not True:
        return "fixture metadata must declare synthetic=true", frozenset()
    if not _is_semver(document["fixture_metadata_schema_version"]):
        return "fixture metadata schema version is malformed", frozenset()
    if not _is_uuid(document["fixture_id"]):
        return "fixture id is malformed", frozenset()
    if not _is_semver(document["preprocessing_contract_version"]):
        return "preprocessing contract version is malformed", frozenset()
    if document["pixel_format"] != "rgb24" or document["color_space"] != "srgb":
        return "fixture metadata does not declare the canonical pixel format", frozenset()
    if document["color_range"] != "full" or document["channel_order"] != "rgb":
        return "fixture metadata does not declare the canonical color representation", frozenset()
    if document["orientation"] != "upright":
        return "fixture metadata must declare upright orientation", frozenset()
    frames = document["frames"]
    if not isinstance(frames, list) or not frames:
        return "fixture metadata must contain at least one frame", frozenset()
    allowed_paths = {descriptor_path}
    for frame in frames:
        if not isinstance(frame, Mapping):
            return "fixture frame descriptor must be an object", frozenset()
        error = _validate_hashed_reference(
            frame,
            descriptor_path,
            "path",
            "byte_length",
            "sha256",
            group_root,
            inventory_paths,
            source,
        )
        if error is not None:
            return error, frozenset()
        reference = _resolve_reference(descriptor_path, frame.get("path"))
        if reference is not None:
            allowed_paths.add(reference)

    allowed_paths.update(
        relative_path
        for relative_path in inventory_paths
        if _is_positive_session_artifact(relative_path, group_root)
    )
    manifest_path = f"{group_root}/manifest.json"
    if manifest_path in inventory_paths:
        allowed_paths.update(
            _validated_manifest_references(manifest_path, group_root, inventory_paths, source)
        )
    return None, frozenset(allowed_paths)


def _is_positive_session_artifact(relative_path: str, group_root: str) -> bool:
    path = PurePosixPath(relative_path)
    return path.parent.as_posix() == group_root and path.name in _POSITIVE_SESSION_ARTIFACT_NAMES


def _validated_manifest_references(
    manifest_path: str,
    group_root: str,
    inventory_paths: list[str],
    source: _RepositoryContent,
) -> frozenset[str]:
    raw = source.read_prefix(manifest_path)
    if raw is None:
        return frozenset()
    try:
        document = load_strict_json(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return frozenset()
    if not isinstance(document, Mapping):
        return frozenset()
    references: set[str] = set()
    media_references = document.get("media_references")
    media_values = media_references if isinstance(media_references, list) else []
    artifact_values = [
        artifact
        for key in ("raw_pose_stream", "synchronization_evidence")
        if isinstance(artifact := document.get(key), Mapping)
    ]
    for media_reference in [*media_values, *artifact_values]:
        if not isinstance(media_reference, Mapping):
            continue
        reference = _resolve_reference(manifest_path, media_reference.get("path_ref"))
        if (
            reference is not None
            and reference in inventory_paths
            and _reference_inside(reference, group_root)
        ):
            references.add(reference)
    return frozenset(references)


def _validate_negative_fixture(
    document: Mapping[str, Any],
    group_root: str,
    inventory_paths: list[str],
    error_codes: frozenset[str] | None,
) -> str | None:
    required = {"tooling_schema_version", "command", "expected_error_code", "artifact_path"}
    missing = sorted(required - document.keys())
    if missing:
        return f"expected-failure declaration is missing required fields: {', '.join(missing)}"
    if not _is_semver(document["tooling_schema_version"]):
        return "tooling schema version is malformed"
    if document["command"] not in {"validate-session", "verify-fixture", "guard-repository"}:
        return "expected-failure command is unsupported"
    if not isinstance(document["expected_error_code"], str) or not _ERROR_CODE_RE.fullmatch(
        document["expected_error_code"]
    ):
        return "expected error code is malformed"
    if error_codes is None:
        return "released error-code registry cannot be read"
    if document["expected_error_code"] not in error_codes:
        return "expected error code is not present in the released registry"
    artifact_path = document["artifact_path"]
    if not isinstance(artifact_path, str) or not _reference_inside(artifact_path, group_root):
        return "expected artifact path escapes its negative fixture case"
    if artifact_path not in inventory_paths:
        return "expected artifact path is not present in the repository index"
    return None


def _validate_preprocessing_vector(
    document: Mapping[str, Any],
    descriptor_path: str,
    group_root: str,
    inventory_paths: list[str],
    source: _RepositoryContent,
) -> str | None:
    if "cases" in document:
        return _validate_yuv420_vector(document)
    required = {
        "tooling_schema_version",
        "synthetic",
        "input_width",
        "input_height",
        "output_width",
        "output_height",
        "input_path",
        "input_byte_length",
        "input_sha256",
        "expected_output_path",
        "expected_output_byte_length",
        "expected_output_sha256",
    }
    missing = sorted(required - document.keys())
    if missing:
        return f"preprocessing vector is missing required fields: {', '.join(missing)}"
    if document["synthetic"] is not True:
        return "preprocessing vector must declare synthetic=true"
    if not _is_semver(document["tooling_schema_version"]):
        return "tooling schema version is malformed"
    for field in ("input_width", "input_height", "output_width", "output_height"):
        if not _positive_integer(document[field]):
            return f"{field} must be a positive integer"
    return _validate_hashed_reference(
        document,
        descriptor_path,
        "input_path",
        "input_byte_length",
        "input_sha256",
        group_root,
        inventory_paths,
        source,
        output_fields=(
            "expected_output_path",
            "expected_output_byte_length",
            "expected_output_sha256",
        ),
    )


def _validate_yuv420_vector(document: Mapping[str, Any]) -> str | None:
    required = {"tooling_schema_version", "synthetic", "vector_id", "conversion", "cases"}
    missing = sorted(required - document.keys())
    if missing:
        return f"YUV420 vector is missing required fields: {', '.join(missing)}"
    if document["synthetic"] is not True or not _is_semver(document["tooling_schema_version"]):
        return "YUV420 vector declaration is malformed"
    conversion = document["conversion"]
    if conversion != {
        "color_standard": "BT601",
        "color_range": "FULL",
        "fixed_point_scale": 1000000,
        "rounding": "half_up",
        "clamp": "0..255",
    }:
        return "YUV420 vector must declare the admitted BT601/FULL fixed-point conversion"
    cases = document["cases"]
    if not isinstance(cases, list) or not cases:
        return "YUV420 vector must contain at least one case"
    for case in cases:
        if not isinstance(case, Mapping):
            return "YUV420 vector case must be an object"
        required_case = {
            "case_id",
            "width",
            "height",
            "y",
            "u",
            "v",
            "color_standard",
            "color_range",
            "rotation",
            "mirrored",
            "expected_rgb24",
            "expected_byte_length",
            "expected_sha256",
        }
        missing_case = sorted(required_case - case.keys())
        if missing_case:
            return f"YUV420 vector case is missing required fields: {', '.join(missing_case)}"
        if not _positive_integer(case["width"]) or not _positive_integer(case["height"]):
            return "YUV420 vector case dimensions must be positive integers"
        if case["color_standard"] != "BT601" or case["color_range"] != "FULL":
            return "YUV420 vector case must declare BT601/FULL"
        expected = case["expected_rgb24"]
        if not isinstance(expected, list) or any(
            not isinstance(value, int) or not 0 <= value <= 255 for value in expected
        ):
            return "YUV420 expected RGB bytes must be integers in 0..255"
        if (
            case["expected_byte_length"] != len(expected)
            or not isinstance(case["expected_sha256"], str)
            or not _SHA256_RE.fullmatch(case["expected_sha256"])
        ):
            return "YUV420 expected RGB length or SHA-256 is malformed"
        if hashlib.sha256(bytes(expected)).hexdigest() != case["expected_sha256"]:
            return "YUV420 expected RGB SHA-256 does not match literal bytes"
        for plane_name in ("y", "u", "v"):
            plane = case[plane_name]
            if not isinstance(plane, Mapping) or not isinstance(plane.get("bytes"), list):
                return f"YUV420 {plane_name} plane bytes are malformed"
            plane_bytes = plane["bytes"]
            if any(not isinstance(value, int) or not 0 <= value <= 255 for value in plane_bytes):
                return f"YUV420 {plane_name} plane bytes must be integers in 0..255"
            if (
                not _positive_integer(plane.get("byte_length"))
                or not isinstance(plane.get("sha256"), str)
                or not _SHA256_RE.fullmatch(plane["sha256"])
                or plane["byte_length"] != len(plane_bytes)
            ):
                return f"YUV420 {plane_name} plane length or SHA-256 is malformed"
            if hashlib.sha256(bytes(plane_bytes)).hexdigest() != plane["sha256"]:
                return f"YUV420 {plane_name} plane SHA-256 does not match literal bytes"
            if not _positive_integer(plane.get("row_stride")) or not _positive_integer(
                plane.get("pixel_stride")
            ):
                return f"YUV420 {plane_name} strides must be positive integers"
    return None


def _validate_hashed_reference(
    document: Mapping[str, Any],
    descriptor_path: str,
    path_field: str,
    length_field: str,
    hash_field: str,
    group_root: str,
    inventory_paths: list[str],
    source: _RepositoryContent,
    output_fields: tuple[str, str, str] | None = None,
) -> str | None:
    path_value = document.get(path_field)
    length_value = document.get(length_field)
    hash_value = document.get(hash_field)
    reference = _resolve_reference(descriptor_path, path_value)
    if reference is None or not _reference_inside(reference, group_root):
        return f"{path_field} escapes the approved fixture root"
    if reference not in inventory_paths:
        return f"{path_field} does not name an indexed file"
    if not _positive_integer(length_value):
        return f"{length_field} must be a positive integer"
    if not isinstance(hash_value, str) or not _SHA256_RE.fullmatch(hash_value):
        return f"{hash_field} must be a lowercase SHA-256"
    actual_length, actual_hash = _digest(source, reference)
    if actual_length != length_value or actual_hash != hash_value:
        return f"{path_field} does not match its declared length and SHA-256"

    if output_fields is None:
        return None
    output_path_field, output_length_field, output_hash_field = output_fields
    output_reference = _resolve_reference(descriptor_path, document.get(output_path_field))
    if output_reference is None or not _reference_inside(output_reference, group_root):
        return f"{output_path_field} escapes the approved fixture root"
    if output_reference not in inventory_paths:
        return f"{output_path_field} does not name an indexed file"
    output_length = document.get(output_length_field)
    output_hash = document.get(output_hash_field)
    if not _positive_integer(output_length):
        return f"{output_length_field} must be a positive integer"
    if not isinstance(output_hash, str) or not _SHA256_RE.fullmatch(output_hash):
        return f"{output_hash_field} must be a lowercase SHA-256"
    actual_output_length, actual_output_hash = _digest(source, output_reference)
    if actual_output_length != output_length or actual_output_hash != output_hash:
        return f"{output_path_field} does not match its declared length and SHA-256"
    return None


def _resolve_reference(descriptor_path: str, reference: Any) -> str | None:
    if not isinstance(reference, str) or not reference or "\0" in reference:
        return None
    parent = PurePosixPath(descriptor_path).parent.as_posix()
    joined = posixpath.normpath(posixpath.join(parent, reference))
    if joined in {".", ".."} or joined.startswith(("../", "/")):
        return None
    return joined


def _reference_inside(reference: str, root: str) -> bool:
    return reference == root or reference.startswith(root.rstrip("/") + "/")


def _digest(source: _RepositoryContent, relative_path: str) -> tuple[int, str]:
    digest = hashlib.sha256()
    length = 0
    for chunk in source.iter_bytes(relative_path):
        digest.update(chunk)
        length += len(chunk)
    return length, digest.hexdigest()


def _positive_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _load_error_code_registry(source: _RepositoryContent) -> frozenset[str] | None:
    raw = source.read_prefix(_ERROR_CODE_REGISTRY_PATH)
    if raw is None:
        return None
    try:
        document = load_strict_json(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(document, Mapping):
        return None
    codes = document.get("codes")
    if not isinstance(codes, list) or not all(isinstance(code, str) for code in codes):
        return None
    return frozenset(codes)


def _is_semver(value: Any) -> bool:
    return isinstance(value, str) and _SEMVER_RE.fullmatch(value) is not None


def _is_uuid(value: Any) -> bool:
    return isinstance(value, str) and _UUID_RE.fullmatch(value) is not None


def _path_issue_for(relative_path: str, detail: str) -> ValidationIssue:
    return ValidationIssue(
        code="REPOSITORY.PROHIBITED_PATH",
        artifact_path=relative_path,
        artifact_kind="repository",
        message=detail,
    )


def _content_issue_for(relative_path: str, detail: str) -> ValidationIssue:
    return ValidationIssue(
        code="REPOSITORY.PROHIBITED_CONTENT",
        artifact_path=relative_path,
        artifact_kind="repository",
        message=detail,
    )


def _declaration_issue(relative_path: str, detail: str) -> ValidationIssue:
    return ValidationIssue(
        code="REPOSITORY.INVALID_FIXTURE_DECLARATION",
        artifact_path=relative_path,
        artifact_kind="repository",
        message=detail,
    )
