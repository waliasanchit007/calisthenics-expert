"""Fetch the pinned MediaPipe model asset into the ignored local cache.

Design section 13: the binary is never tracked. It is fetched from the immutable URL recorded in
the tracked provenance manifest and pinned by SHA-256, verified here at fetch time and again at
runtime before a session may start. Those two checks defend different failure points — a corrupted
cache and corrupted packaging — so neither replaces the other.

Transport is untrusted by construction. This environment terminates TLS with its own certificate
chain, so integrity comes from the pinned SHA-256 and the verify-before-move discipline below,
never from the transport. Do not "fix" a certificate complaint by weakening verification.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

MANIFEST = Path("apps/diagnostic-harness/model-assets/model_provenance.json")


class ModelAssetError(RuntimeError):
    """Raised whenever the cached or fetched asset cannot be trusted."""


def _digest(path: Path) -> tuple[str, int]:
    sha = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            sha.update(chunk)
            size += len(chunk)
    return sha.hexdigest(), size


def fetch_model_asset(root: Path, source_url: str | None = None) -> Path:
    """Ensure the pinned asset is present and verified, returning its path.

    `source_url` exists so tests can serve a local file; production always resolves the URL from
    the manifest, which is the single source of that value.
    """
    manifest_path = root / MANIFEST
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    url = source_url or manifest["source_url"]
    expected_sha = manifest["sha256"]
    expected_bytes = int(manifest["byte_count"])
    destination = manifest_path.parent / manifest["asset_name"]

    if destination.is_file():
        actual_sha, actual_bytes = _digest(destination)
        if actual_sha == expected_sha and actual_bytes == expected_bytes:
            return destination
        # Quarantine before refetching. A known-corrupt asset must not remain readable: absence is
        # an honest state that fails the build, corruption masquerading as valid is not.
        print(
            f"cached asset failed verification, quarantining: {destination.name}",
            file=sys.stderr,
        )
        destination.unlink()

    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(dir=destination.parent, suffix=".partial")
    os.close(handle)
    temporary: Path | None = Path(temporary_name)
    try:
        curl = shutil.which("curl")
        if curl is None:
            raise ModelAssetError("curl is required to fetch the pinned model asset")
        completed = subprocess.run(
            [
                curl,
                "--fail",
                "--location",
                # `--location` must never leave HTTPS, even though the SHA pin bounds the damage.
                "--proto-redir",
                "=https",
                "--silent",
                "--show-error",
                "--output",
                str(temporary),
                url,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if completed.returncode != 0:
            raise ModelAssetError(
                f"model asset fetch failed: {completed.stderr.strip()}"
            )

        actual_sha, actual_bytes = _digest(temporary)
        if actual_bytes != expected_bytes:
            raise ModelAssetError(
                f"model asset byte_count mismatch: pinned {expected_bytes}, fetched {actual_bytes}"
            )
        if actual_sha != expected_sha:
            raise ModelAssetError(
                f"model asset sha256 mismatch: pinned {expected_sha}, fetched {actual_sha}"
            )
        temporary.replace(destination)
        temporary = None
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()
    return destination


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    try:
        path = fetch_model_asset(root)
    except ModelAssetError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(f"model asset present and verified: {path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
