"""Canonical M0.5B storage-location registry loading and semantic validation."""

from __future__ import annotations

import hashlib
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .schema_loader import load_strict_json, storage_location_registry_schema_validator

LOCATION_IDS = (
    "device_export",
    "device_staging",
    "device_transfer_staging",
    "study_store_source",
    "study_store_derived",
    "study_store_report",
    "tombstone",
)
ANDROID_OWNER = "android_app"


@dataclass(frozen=True)
class StorageLocation:
    location_id: str
    owner: str
    relative_root: str
    artifact_classes: tuple[str, ...]
    publishable: bool
    device_purge: bool
    host_rehearsal_purge: bool
    cloud_backup: str
    device_transfer: str
    tombstone_eligible: bool
    post_purge_scan: bool


@dataclass(frozen=True)
class StorageRegistry:
    version: str
    entries: tuple[StorageLocation, ...]
    sha256: str


def _locations(document: Mapping[str, Any]) -> Sequence[Mapping[str, Any]]:
    locations = document.get("locations")
    if not isinstance(locations, list):
        return ()
    return tuple(item for item in locations if isinstance(item, Mapping))


def storage_registry_validation_codes(document: Mapping[str, Any]) -> tuple[str, ...]:
    """Return stable semantic error codes after structural validation."""
    locations = _locations(document)
    ids = tuple(str(item.get("location_id", "")) for item in locations)
    codes: list[str] = []
    has_duplicate_id = any(count > 1 for count in Counter(ids).values())
    if has_duplicate_id:
        codes.append("STORAGE.REGISTRY_DUPLICATE_ID")
    if not has_duplicate_id and tuple(ids) != LOCATION_IDS:
        codes.append("STORAGE.REGISTRY_LOCATION_SET")
    roots = tuple(str(item.get("relative_root", "")) for item in locations)
    if len(set(roots)) != len(roots):
        codes.append("STORAGE.REGISTRY_DUPLICATE_ROOT")
    if any(
        not item.get("relative_root")
        or "/" in str(item["relative_root"])
        or "\\" in str(item["relative_root"])
        for item in locations
        if item.get("owner") == ANDROID_OWNER
    ):
        codes.append("STORAGE.REGISTRY_ANDROID_ROOT")
    if any(
        item.get("cloud_backup") != "excluded" or item.get("device_transfer") != "excluded"
        for item in locations
        if item.get("owner") == ANDROID_OWNER
    ):
        codes.append("STORAGE.REGISTRY_ANDROID_EXCLUSION")
    tombstones = tuple(item for item in locations if item.get("location_id") == "tombstone")
    has_purgeable_tombstone = len(tombstones) == 1 and any(
        bool(tombstones[0].get(field))
        for field in ("device_purge", "host_rehearsal_purge", "tombstone_eligible")
    )
    if has_purgeable_tombstone:
        codes.append("STORAGE.REGISTRY_TOMBSTONE_PURGE")
    if any(
        item.get("device_purge") is True and item.get("post_purge_scan") is not True
        for item in locations
        if item.get("location_id") != "tombstone"
    ):
        codes.append("STORAGE.REGISTRY_POST_PURGE_SCAN")
    return tuple(codes)


def load_storage_registry(path: Path) -> StorageRegistry:
    """Load, structurally validate, semantically validate, and hash a registry file."""
    serialized = path.read_bytes()
    document = load_strict_json(serialized.decode("utf-8"))
    if not isinstance(document, Mapping):
        raise TypeError("STORAGE.REGISTRY_NOT_OBJECT")
    structural = tuple(storage_location_registry_schema_validator().iter_errors(document))
    if structural:
        raise ValueError("STORAGE.REGISTRY_SCHEMA")
    semantic = storage_registry_validation_codes(document)
    if semantic:
        raise ValueError(",".join(semantic))
    entries = tuple(
        StorageLocation(
            location_id=str(item["location_id"]),
            owner=str(item["owner"]),
            relative_root=str(item["relative_root"]),
            artifact_classes=tuple(str(value) for value in item["artifact_classes"]),
            publishable=bool(item["publishable"]),
            device_purge=bool(item["device_purge"]),
            host_rehearsal_purge=bool(item["host_rehearsal_purge"]),
            cloud_backup=str(item["cloud_backup"]),
            device_transfer=str(item["device_transfer"]),
            tombstone_eligible=bool(item["tombstone_eligible"]),
            post_purge_scan=bool(item["post_purge_scan"]),
        )
        for item in _locations(document)
    )
    return StorageRegistry(
        version=str(document["registry_version"]),
        entries=entries,
        sha256=hashlib.sha256(serialized).hexdigest(),
    )
