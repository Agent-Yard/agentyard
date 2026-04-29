"""Contract testing helpers for SDK consumers and extension implementations."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True, slots=True)
class ExtensionProtocolFixtures:
    repo_root: Path

    @property
    def protocol_root(self) -> Path:
        return self.repo_root / "packages/extension-protocol"

    @property
    def schema_dir(self) -> Path:
        return self.protocol_root / "json-schema"

    @property
    def service_manifest_schema_path(self) -> Path:
        return self.schema_dir / "service-manifest.schema.json"

    @property
    def extension_error_example_path(self) -> Path:
        return self.protocol_root / "examples/extension-error.remote-auth-failed.json"

    @property
    def canonical_json_dir(self) -> Path:
        return self.protocol_root / "contract-tests/fixtures/canonical-json"

    @property
    def manifest_valid_dir(self) -> Path:
        return self.protocol_root / "contract-tests/fixtures/manifest-valid"

    @property
    def manifest_invalid_dir(self) -> Path:
        return self.protocol_root / "contract-tests/fixtures/manifest-invalid"

    @property
    def registration_loader_dir(self) -> Path:
        return self.protocol_root / "contract-tests/fixtures/registration-loader"

    def canonical_json_fixtures(self) -> tuple[Path, ...]:
        return tuple(sorted(self.canonical_json_dir.glob("*.json")))

    def valid_manifest_fixtures(self) -> tuple[Path, ...]:
        return tuple(sorted(self.manifest_valid_dir.glob("*.json")))

    def invalid_manifest_fixtures(self) -> tuple[Path, ...]:
        return tuple(sorted(self.manifest_invalid_dir.glob("*.json")))

    def registration_loader_fixtures(self) -> tuple[Path, ...]:
        return tuple(sorted(self.registration_loader_dir.glob("*.json")))


def default_protocol_fixtures(start: Path | str | None = None) -> ExtensionProtocolFixtures:
    return ExtensionProtocolFixtures(repo_root=find_repo_root(start))


def find_repo_root(start: Path | str | None = None) -> Path:
    current = Path.cwd() if start is None else Path(start)
    current = current.resolve()
    if current.is_file():
        current = current.parent

    for candidate in (current, *current.parents):
        if (candidate / "packages/extension-protocol").is_dir():
            return candidate
    raise FileNotFoundError("Could not locate packages/extension-protocol from the supplied path")


def load_json_fixture(path: Path | str) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


__all__ = [
    "ExtensionProtocolFixtures",
    "default_protocol_fixtures",
    "find_repo_root",
    "load_json_fixture",
]
