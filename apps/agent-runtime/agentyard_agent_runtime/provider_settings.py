from __future__ import annotations

import os

from .models import AgentTurnRequest
from .openai_compatible import OpenAiCompatibleSettings

OPENAI_COMPATIBLE_PROVIDER_TYPES = {"OPENAI", "OPENAI_COMPATIBLE"}


def resolve_provider_settings(request: AgentTurnRequest) -> OpenAiCompatibleSettings | None:
    model = request.currentOwner.model
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
    base_url = (os.getenv("AGENTYARD_OPENAI_COMPATIBLE_BASE_URL") or "").strip()
    model_id = (os.getenv("AGENTYARD_OPENAI_COMPATIBLE_MODEL_ID") or "").strip()
    if not base_url or not model_id:
        return None
    api_key_env = (os.getenv("AGENTYARD_OPENAI_COMPATIBLE_API_KEY_ENV_VAR") or "OPENAI_COMPATIBLE_API_KEY").strip()
    api_key = (os.getenv(api_key_env) or "").strip()
    if not api_key:
        return None
    return OpenAiCompatibleSettings(
        base_url=base_url,
        model_id=model_id,
        api_key=api_key,
        provider_type="OPENAI_COMPATIBLE",
        temperature=0,
        max_tokens=0,
        organization=(os.getenv("AGENTYARD_OPENAI_COMPATIBLE_ORGANIZATION") or "").strip(),
        project=(os.getenv("AGENTYARD_OPENAI_COMPATIBLE_PROJECT") or "").strip(),
    )
