"""Stable, machine-readable validation issues shared by every validator module.

Issue codes are drawn from ``contracts/tooling/validator_error_codes.v1.json``. Released
codes are never renamed or reused for a different condition; see
``contracts/schema-release-process.md``.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ValidationIssue:
    """One validation failure described in stable, machine-readable form.

    ``pointer``, ``line``, ``expected``, and ``actual`` are optional context. ``None`` means
    the value was not set and it is omitted from :meth:`to_dict`.
    """

    code: str
    artifact_path: str
    artifact_kind: str
    message: str
    pointer: str | None = None
    line: int | None = None
    expected: Any = None
    actual: Any = None

    def to_dict(self) -> dict[str, Any]:
        """Return the stable JSON representation with unset optional fields omitted."""
        payload: dict[str, Any] = {
            "code": self.code,
            "artifact_path": self.artifact_path,
            "artifact_kind": self.artifact_kind,
            "message": self.message,
        }
        if self.pointer is not None:
            payload["pointer"] = self.pointer
        if self.line is not None:
            payload["line"] = self.line
        if self.expected is not None:
            payload["expected"] = self.expected
        if self.actual is not None:
            payload["actual"] = self.actual
        return payload


class ValidationFailure(Exception):
    """Raised when validation produced one or more issues."""

    def __init__(self, issues: Sequence[ValidationIssue]) -> None:
        self.issues: tuple[ValidationIssue, ...] = tuple(issues)
        super().__init__(f"validation failed with {len(self.issues)} issue(s)")
