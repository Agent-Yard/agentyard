from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
OPENAPI_PATH = REPO_ROOT / "packages/extension-protocol/openapi/extension-boundary.openapi.json"
SDK_ROOT = REPO_ROOT / "packages/extension-sdk-python"
GENERATE_SCRIPT = SDK_ROOT / "scripts/generate_protocol_models.py"
GENERATED_MODELS_PATH = SDK_ROOT / "build/generated/extension_protocol/models.py"


def test_datamodel_codegen_writes_protocol_models_under_python_build_directory() -> None:
    subprocess.run(
        [sys.executable, str(GENERATE_SCRIPT)],
        cwd=REPO_ROOT,
        check=True,
        text=True,
        capture_output=True,
    )

    assert GENERATED_MODELS_PATH.resolve().is_relative_to((SDK_ROOT / "build").resolve())

    openapi = json.loads(OPENAPI_PATH.read_text(encoding="utf-8"))
    source_schemas = set(openapi["components"]["schemas"])
    generated_models = import_generated_models()

    for expected_schema in {
        "ServiceManifestEnvelope",
        "ExtensionError",
        "ChannelProviderDescriptor",
        "ChannelOutboundFrame",
        "ChannelOutboundFrameAck",
        "ToolConnectorDescriptor",
    }:
        assert expected_schema in source_schemas
        assert isinstance(getattr(generated_models, expected_schema), type)


def import_generated_models() -> object:
    spec = importlib.util.spec_from_file_location(
        "lynxus_extension_protocol_generated_models_smoke",
        GENERATED_MODELS_PATH,
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module
