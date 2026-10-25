from __future__ import annotations

import os

from .models import ActivationRuntime
from .openai_compatible import OpenAiCompatibleSettings

OPENAI_COMPATIBLE_PROVIDER_TYPES = {"OPENAI", "OPENAI_COMPATIBLE"}


def resolve_provider_settings(request: ActivationRuntime) -> OpenAiCompatibleSettings | None:
    model = request.agent.model
    if model is not None and model.providerType.upper() in OPENAI_COMPATIBLE_PROVIDER_TYPES:
        api_key = (os.getenv(model.apiKeyEnvVar) or "").strip()
        if api_key and model.baseUrl.strip() and model.modelId.strip():
            return OpenAiCompatibleSettings(
                base_url=model.baseUrl.strip(),
                model_id=model.modelId.strip(),
                api_key=api_key,
                provider_type=model.providerType.upper(),
                model_resource_id=model.resourceId,
                model_resource_version_id=model.resourceVersionId,
                temperature=model.temperature,
                max_tokens=model.maxTokens,
                enable_thinking=model.enableThinking,
                reasoning_effort=(model.reasoningEffort or "").strip(),
                organization=(os.getenv("AGENTYARD_OPENAI_COMPATIBLE_ORGANIZATION") or "").strip(),
                project=(os.getenv("AGENTYARD_OPENAI_COMPATIBLE_PROJECT") or "").strip(),
            )
    return None
