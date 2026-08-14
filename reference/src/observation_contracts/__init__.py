"""Reference validator for the Phase 0 observation contracts.

Phase 0 freezes observation, not interpretation: this package validates the structure,
sequencing, and integrity of exported observations without deciding what they mean.
"""

from observation_contracts.errors import ValidationFailure, ValidationIssue
from observation_contracts.versions import ArtifactVersion, ensure_supported_major

__all__ = [
    "ArtifactVersion",
    "ValidationFailure",
    "ValidationIssue",
    "ensure_supported_major",
]
