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
