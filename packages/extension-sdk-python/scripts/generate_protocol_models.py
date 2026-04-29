from __future__ import annotations

import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
OPENAPI_PATH = REPO_ROOT / "packages/extension-protocol/openapi/extension-boundary.openapi.json"
OUTPUT_PATH = REPO_ROOT / "packages/extension-sdk-python/build/generated/extension_protocol/models.py"


def main() -> int:
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    return subprocess.call(
        [
            sys.executable,
            "-m",
            "datamodel_code_generator",
            "--input",
            str(OPENAPI_PATH),
            "--input-file-type",
            "openapi",
            "--output",
            str(OUTPUT_PATH),
            "--output-model-type",
            "pydantic_v2.BaseModel",
            "--target-python-version",
            "3.11",
            "--disable-timestamp",
            "--formatters",
            "black",
            "isort",
            "--use-annotated",
            "--use-standard-collections",
            "--use-union-operator",
        ]
    )


if __name__ == "__main__":
    raise SystemExit(main())
