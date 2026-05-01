from __future__ import annotations

import hashlib
import json
from typing import Any

from .data_security.mapper import PrivacyMapper
from .data_security.store import SessionPrivacyMapStore
from .data_security.trace import MappingTrace
from .models import AgentTurnRequest, LlmModelDescriptor
from .openai_compatible import LlmUsageTracker
from .privacy_contracts import (
    PRIVACY_FRAGMENT_CACHE_VERSION,
    PrivacyMappingTelemetry,
    PrivacyModelBinding,
    PrivacyPolicy,
    PrivacyStrategy,
)
from .prompt_bundle import PromptBundle
from .semantic import SemanticMessage


class PrivacyPipeline:
    def __init__(self, policy: PrivacyPolicy, usage_tracker: LlmUsageTracker | None = None) -> None:
        self._policy = policy
        self._store = SessionPrivacyMapStore(policy) if policy.enabled else None
        self._trace = MappingTrace(policy)
        self._mapper = None if self._store is None else PrivacyMapper(policy, self._store, self._trace, usage_tracker)

    @property
    def enabled(self) -> bool:
        return self._policy.enabled

    def sanitize_outbound(self, channel: str, payload: Any) -> Any:
        if self._mapper is None:
            return payload
        return self._mapper.sanitize(payload, channel)

    def sanitize_prompt_bundle(self, bundle: PromptBundle) -> PromptBundle:
        if self._mapper is None:
            return bundle
        return PromptBundle(
            instruction=self.sanitize_fragment(
                "PROMPT_INSTRUCTION",
                bundle.instruction,
                bundle.instruction_privacy_strategy,
                source="prompt.instruction",
            ),
            runtime_messages=[self.sanitize_semantic_message(message) for message in bundle.runtime_messages],
            capabilities=self.sanitize_fragment(
                "PROMPT_CAPABILITIES",
                bundle.capabilities,
                bundle.capabilities_privacy_strategy,
                source="prompt.capabilities",
            ),
            response_contract=self.sanitize_fragment(
                "PROMPT_RESPONSE_CONTRACT",
                bundle.response_contract,
                bundle.response_contract_privacy_strategy,
                source="prompt.response_contract",
            ),
            instruction_privacy_strategy=bundle.instruction_privacy_strategy,
            capabilities_privacy_strategy=bundle.capabilities_privacy_strategy,
            response_contract_privacy_strategy=bundle.response_contract_privacy_strategy,
        )

    def sanitize_semantic_message(self, message: SemanticMessage) -> SemanticMessage:
        return SemanticMessage(
            kind=message.kind,
            content=self.sanitize_fragment(
                "PROMPT_RUNTIME_MESSAGE",
                message.content,
                message.privacy_strategy,
                source=message.privacy_source,
            ),
            tool_calls=message.tool_calls,
            tool_call_id=message.tool_call_id,
            privacy_strategy=message.privacy_strategy,
            privacy_source=message.privacy_source,
        )

    def sanitize_fragment(
        self,
        channel: str,
        payload: Any,
        strategy: PrivacyStrategy,
        *,
        source: str | None = None,
    ) -> Any:
        if self._mapper is None or strategy == PrivacyStrategy.SKIP:
            return payload
        cache_key = self._fragment_cache_key(strategy, source, payload)
        if cache_key is not None and self._store is not None:
            cached = self._store.read_sanitized_fragment(cache_key)
            if cached is not None:
                return cached
        sanitized = self._mapper.sanitize(payload, channel, strategy)
        if cache_key is not None and self._store is not None:
            self._store.write_sanitized_fragment(
                cache_key,
                sanitized,
                self._fragment_cache_metadata(strategy, source or "", payload),
            )
        return sanitized

    def write_sanitized_fragment_cache(
        self,
        payload: Any,
        sanitized: Any,
        strategy: PrivacyStrategy,
        *,
        source: str,
    ) -> None:
        if self._mapper is None or self._store is None or strategy == PrivacyStrategy.SKIP or not source:
            return
        cache_key = self._fragment_cache_key(strategy, source, payload)
        if cache_key is None:
            return
        self._store.write_sanitized_fragment(
            cache_key,
            sanitized,
            self._fragment_cache_metadata(strategy, source, payload),
        )

    def restore_inbound(self, channel: str, payload: Any) -> Any:
        if self._mapper is None:
            return payload
        return self._mapper.restore(payload, channel)

    def telemetry(self) -> PrivacyMappingTelemetry | None:
        return self._trace.telemetry()

    def close(self) -> None:
        if self._store is not None:
            self._store.close()

    def _fragment_cache_key(self, strategy: PrivacyStrategy, source: str | None, payload: Any) -> str | None:
        if not source:
            return None
        metadata = self._fragment_cache_metadata(strategy, source, payload)
        return "fragment:" + hashlib.sha256(
            json.dumps(metadata, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()

    def _fragment_cache_metadata(self, strategy: PrivacyStrategy, source: str, payload: Any) -> dict[str, Any]:
        binding = self._policy.model_binding
        return {
            "policyVersion": PRIVACY_FRAGMENT_CACHE_VERSION,
            "privacyModelResourceVersionId": None if binding is None else binding.resource_version_id,
            "strategy": strategy.value,
            "source": source,
            "contentFingerprint": self._fragment_content_fingerprint(payload),
        }

    def _fragment_content_fingerprint(self, payload: Any) -> str:
        payload_bytes = _cache_payload_bytes(payload)
        if self._store is not None:
            return self._store.fingerprint_fragment_content(payload_bytes)
        return hashlib.sha256(payload_bytes).hexdigest()


def build_privacy_pipeline(
    request: AgentTurnRequest,
    usage_tracker: LlmUsageTracker | None = None,
) -> PrivacyPipeline:
    return PrivacyPipeline(build_privacy_policy(request), usage_tracker)


def build_privacy_policy(request: AgentTurnRequest) -> PrivacyPolicy:
    binding = request.effectivePrivacyModelBinding or request.currentOwner.effectivePrivacyModelBinding
    enabled = bool(request.effectivePrivacyMappingEnabled or request.currentOwner.effectivePrivacyMappingEnabled)
    return PrivacyPolicy(
        session_id=request.sessionId,
        assistant_id=request.assistantId,
        agent_id=request.currentOwner.agentId,
        enabled=enabled and binding is not None,
        model_binding=_privacy_model_binding(binding) if binding is not None else None,
    )


def _privacy_model_binding(binding: LlmModelDescriptor) -> PrivacyModelBinding:
    return PrivacyModelBinding(
        resource_id=binding.resourceId,
        resource_name=binding.resourceName,
        resource_version_id=binding.resourceVersionId,
        provider_type=binding.providerType,
        model_id=binding.modelId,
        base_url=binding.baseUrl,
        api_key_env_var=binding.apiKeyEnvVar,
        private_deployment=binding.privateDeployment,
        enable_thinking=binding.enableThinking,
        reasoning_effort=binding.reasoningEffort,
    )


def _cache_payload_bytes(payload: Any) -> bytes:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
