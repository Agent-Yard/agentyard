from __future__ import annotations

from importlib.resources import files
from importlib.resources.abc import Traversable
from pathlib import Path


def protocol_schema_dir() -> Traversable:
    packaged_schema_dir = files(__name__).joinpath("json_schema")
    if packaged_schema_dir.is_dir():
        return packaged_schema_dir

    source_schema_dir = Path(__file__).resolve().parents[2] / "json-schema"
    if source_schema_dir.is_dir():
        return source_schema_dir

    return packaged_schema_dir


__all__ = ["protocol_schema_dir"]
