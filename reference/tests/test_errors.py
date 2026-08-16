import json
from pathlib import Path

from observation_contracts.errors import ValidationIssue


def test_issue_json_omits_unset_locations() -> None:
    issue = ValidationIssue(
        code="SCHEMA.INVALID",
        artifact_path="sample.json",
        artifact_kind="session_manifest",
        message="required property missing",
    )
    assert issue.to_dict() == {
        "code": "SCHEMA.INVALID",
        "artifact_path": "sample.json",
        "artifact_kind": "session_manifest",
        "message": "required property missing",
    }


def test_media_role_code_is_in_the_released_registry() -> None:
    registry_path = (
        Path(__file__).resolve().parents[2]
        / "contracts"
        / "tooling"
        / "validator_error_codes.v1.json"
    )
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    assert "SESSION.MEDIA_ROLE" in registry["codes"]
