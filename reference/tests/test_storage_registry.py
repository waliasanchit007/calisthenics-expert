from __future__ import annotations

import json
from pathlib import Path
from xml.etree import ElementTree

from observation_contracts.storage_registry import (
    load_storage_registry,
    storage_registry_validation_codes,
)

ROOT = Path(__file__).parents[2]
REGISTRY_PATH = ROOT / "docs" / "protocols" / "storage-location-registry.m05b.v1.json"

EXPECTED_LOCATION_IDS = (
    "device_export",
    "device_staging",
    "device_transfer_staging",
    "study_store_source",
    "study_store_derived",
    "study_store_report",
    "tombstone",
)


def test_committed_registry_is_the_exact_closed_m05b_location_set() -> None:
    registry = load_storage_registry(REGISTRY_PATH)

    assert registry.version == "1.0.0"
    assert tuple(entry.location_id for entry in registry.entries) == EXPECTED_LOCATION_IDS
    assert len(registry.sha256) == 64
    assert registry.sha256 == registry.sha256.lower()


def test_android_locations_are_children_of_one_parent_and_excluded_everywhere() -> None:
    registry = load_storage_registry(REGISTRY_PATH)
    android_entries = tuple(entry for entry in registry.entries if entry.owner == "android_app")

    assert tuple(entry.location_id for entry in android_entries) == (
        "device_export",
        "device_staging",
        "device_transfer_staging",
        "tombstone",
    )
    assert all(entry.relative_root and "/" not in entry.relative_root for entry in android_entries)
    assert all(entry.cloud_backup == "excluded" for entry in android_entries)
    assert all(entry.device_transfer == "excluded" for entry in android_entries)
    assert all(entry.post_purge_scan for entry in android_entries if entry.device_purge)


def test_tombstone_is_never_artifact_purge_eligible() -> None:
    registry = load_storage_registry(REGISTRY_PATH)
    tombstone = next(entry for entry in registry.entries if entry.location_id == "tombstone")

    assert tombstone.device_purge is False
    assert tombstone.host_rehearsal_purge is False
    assert tombstone.tombstone_eligible is False
    assert tombstone.artifact_classes == ("purge_tombstone",)


def test_semantics_reject_duplicate_ids_nested_android_roots_and_purgeable_tombstone() -> None:
    document = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
    duplicate = json.loads(json.dumps(document))
    duplicate["locations"][1]["location_id"] = "device_export"
    nested = json.loads(json.dumps(document))
    nested["locations"][0]["relative_root"] = "nested/exports"
    purgeable_tombstone = json.loads(json.dumps(document))
    purgeable_tombstone["locations"][-1]["device_purge"] = True
    eighth_location = json.loads(json.dumps(document))
    eighth_location["locations"].append(
        {
            **eighth_location["locations"][0],
            "location_id": "device_cache",
            "relative_root": "cache",
        }
    )

    assert storage_registry_validation_codes(duplicate) == ("STORAGE.REGISTRY_DUPLICATE_ID",)
    assert storage_registry_validation_codes(nested) == ("STORAGE.REGISTRY_ANDROID_ROOT",)
    assert storage_registry_validation_codes(purgeable_tombstone) == (
        "STORAGE.REGISTRY_TOMBSTONE_PURGE",
    )
    assert storage_registry_validation_codes(eighth_location) == ("STORAGE.REGISTRY_LOCATION_SET",)


def test_android_manifest_disables_backup_and_references_modern_extraction_rules() -> None:
    manifest = ElementTree.parse(
        ROOT / "apps/diagnostic-harness/androidApp/src/main/AndroidManifest.xml"
    ).getroot()
    application = manifest.find("application")
    assert application is not None
    android = "{http://schemas.android.com/apk/res/android}"

    assert application.attrib[f"{android}allowBackup"] == "false"
    assert application.attrib[f"{android}dataExtractionRules"] == "@xml/data_extraction_rules"


def test_both_android_extraction_domains_exclude_the_complete_parent() -> None:
    root = ElementTree.parse(
        ROOT / "apps/diagnostic-harness/androidApp/src/main/res/xml/data_extraction_rules.xml"
    ).getroot()

    assert root.tag == "data-extraction-rules"
    for domain in ("cloud-backup", "device-transfer"):
        node = root.find(domain)
        assert node is not None
        assert [(exclude.attrib["domain"], exclude.attrib["path"]) for exclude in node] == [
            ("file", "diagnostic-harness/"),
        ]
