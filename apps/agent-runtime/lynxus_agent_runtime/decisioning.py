from __future__ import annotations

import json
import logging
import os
from typing import Any

from .data_security import build_privacy_pipeline
from .data_security.policy import PrivacyStrategy
from .openai_compatible import LlmUsageTracker, OpenAiCompatibleSettings, chat_completion
from .openai_adapter import (
    assistant_tool_call_message,
    parse_openai_tool_calls,
    render_openai_tool_definitions,
    render_openai_runtime_message,
    tool_result_message,
)
from .models import AgentDecision, AgentTurnExecutionOutcome, AgentTurnRequest, AgentTurnResult, SecurityAssessment
from .prompting import PromptBundle, build_prompt_bundle, loaded_skill_runtime_message, render_openai_messages
from .semantic import SemanticMessage, SemanticToolCall, SemanticToolResult
from .tooling import execute_tool_call, load_skills, semantic_tool_definitions

LOGGER = logging.getLogger("lynxus-agent-runtime")

DEFAULT_MAX_TOOL_STEPS = 4
OPENAI_COMPATIBLE_PROVIDER_TYPES = {"OPENAI", "OPENAI_COMPATIBLE"}


def execute_agent_turn(request: AgentTurnRequest) -> tuple[AgentTurnExecutionOutcome, PromptBundle]:
    prompt_bundle = build_prompt_bundle(request)
    usage_tracker = LlmUsageTracker()
    result = _execute_via_openai_compatible(request, prompt_bundle, usage_tracker)
    return result, prompt_bundle


def _execute_via_openai_compatible(
    request: AgentTurnRequest, prompt_bundle: PromptBundle, usage_tracker: LlmUsageTracker
) -> AgentTurnExecutionOutcome:
    settings = _resolve_provider_settings(request)
    if settings is None:
        raise RuntimeError("no supported model provider configured for current owner")
    privacy_pipeline = build_privacy_pipeline(request, usage_tracker)

    try:
        sanitized_bundle = privacy_pipeline.sanitize_prompt_bundle(prompt_bundle)
        messages = render_openai_messages(sanitized_bundle)
        tools = render_openai_tool_definitions(semantic_tool_definitions(request))
        loaded_skill_ids: set[str] = set()
        max_steps = _max_tool_steps()
        tool_call_count = 0
        skill_read_count = 0
        for step in range(max_steps + 1):
            usage_tracker.set_tool_loop_step(step)
            payload: dict[str, Any] = {
                "model": settings.model_id,
                "temperature": settings.temperature,
                "messages": messages,
                "tools": tools,
            }
            if settings.max_tokens > 0:
                payload["max_tokens"] = settings.max_tokens
            response_json = chat_completion(
                settings,
                payload,
                timeout_seconds=20.0,
                usage_tracker=usage_tracker,
                source_type="SESSION_OWNER_MODEL",
                tool_loop_step=step,
            )
            message = response_json["choices"][0]["message"]
            tool_calls = message.get("tool_calls") or []
            if tool_calls:
                content, semantic_tool_calls = parse_openai_tool_calls(message)
                messages.append(render_openai_runtime_message(assistant_tool_call_message(content, semantic_tool_calls)))
                for tool_call in semantic_tool_calls:
                    tool_call_count += 1
                    restored_arguments = privacy_pipeline.restore_inbound("MODEL_TOOL_ARGUMENT", tool_call.arguments)
                    restored_tool_call = SemanticToolCall(
                        call_id=tool_call.call_id,
                        tool_name=tool_call.tool_name,
                        arguments=restored_arguments,
                    )
                    raw_tool_result = _execute_model_tool_call(request, restored_tool_call)
                    tool_result = (
                        raw_tool_result
                        if tool_call.tool_name in {"knowledge_search", "knowledge_read"}
                        else privacy_pipeline.sanitize_fragment(
                            "TOOL_RESULT",
                            raw_tool_result,
                            PrivacyStrategy.RULES_THEN_PRIVATE_LLM,
                            source=_tool_result_privacy_source(tool_call.tool_name),
                        )
                    )
                    messages.append(render_openai_runtime_message(tool_result_message(SemanticToolResult(tool_call.call_id, tool_result))))
                continue

            parsed = _extract_json_object(message.get("content"))
            if not isinstance(parsed, dict):
                raise ValueError("model response is not a JSON object")
            if _is_skill_read_request(parsed):
                requested_ids = _parse_skill_reads(parsed, request, loaded_skill_ids)
                loaded_skill_ids.update(requested_ids)
                skill_read_count += len(requested_ids)
                messages.append(
                    render_openai_runtime_message(
                        SemanticMessage(
                            kind="assistant_turn",
                            content=json.dumps({"skillReads": requested_ids}, ensure_ascii=False),
                        )
                    )
                )
                loaded_skills = privacy_pipeline.sanitize_outbound("SKILL_PAYLOAD", load_skills(request, requested_ids))
                messages.append(render_openai_runtime_message(loaded_skill_runtime_message(loaded_skills)))
                continue
            parsed = privacy_pipeline.restore_inbound("MODEL_FINAL_RESPONSE", parsed)
            blocked_result = _blocked_security_result(parsed, request, privacy_pipeline)
            if blocked_result is not None:
                outcome = AgentTurnExecutionOutcome(
                    success=True,
                    result=blocked_result,
                    llmUsage=usage_tracker.entries(),
                )
                LOGGER.info(
                    "agent turn blocked by model security assessment",
                    extra={
                        "sessionId": request.sessionId,
                        "assistantId": request.assistantId,
                        "ownerAgentId": request.currentOwner.agentId,
                        "securityCategories": blocked_result.securityAssessment.categories
                        if blocked_result.securityAssessment is not None
                        else [],
                        "llmUsageCount": len(outcome.llmUsage),
                    },
                )
                privacy_pipeline.close()
                return outcome
            decision_payload = parsed.get("decision")
            shared_state = parsed.get("sharedState")
            security_assessment = parsed.get("securityAssessment")
            result = AgentTurnResult(
                decision=AgentDecision.model_validate(decision_payload or {}),
                sharedState=shared_state if isinstance(shared_state, dict) else dict(request.sharedState),
                mappingTelemetry=privacy_pipeline.telemetry(),
                securityAssessment=security_assessment if isinstance(security_assessment, dict) else None,
            )
            outcome = AgentTurnExecutionOutcome(
                success=True,
                result=result,
                llmUsage=usage_tracker.entries(),
            )
            LOGGER.info(
                "agent turn executed via openai-compatible provider",
                extra={
                    "sessionId": request.sessionId,
                    "assistantId": request.assistantId,
                    "ownerAgentId": request.currentOwner.agentId,
                    "action": result.decision.action,
                    "toolLoopSteps": step,
                    "toolCallCount": tool_call_count,
                    "skillReadCount": skill_read_count,
                    "llmUsageCount": len(outcome.llmUsage),
                    "privacyMappingEnabled": privacy_pipeline.enabled,
                },
            )
            privacy_pipeline.close()
            return outcome
        raise ValueError("model did not return a final decision within loop step budget")
    except Exception as error:  # noqa: BLE001
        failure_reason = str(error) or "agent turn execution failed"
        LOGGER.warning(
            "openai-compatible execution failed",
            extra={
                "sessionId": request.sessionId,
                "assistantId": request.assistantId,
                "ownerAgentId": request.currentOwner.agentId,
                "error": failure_reason,
                "llmUsageCount": len(usage_tracker.entries()),
                "privacyMappingEnabled": privacy_pipeline.enabled,
            },
        )
        privacy_pipeline.close()
        return AgentTurnExecutionOutcome(
            success=False,
            failureReason=failure_reason,
            llmUsage=usage_tracker.entries(),
        )


def _resolve_provider_settings(request: AgentTurnRequest) -> OpenAiCompatibleSettings | None:
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
                organization=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION") or "").strip(),
                project=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_PROJECT") or "").strip(),
            )
    base_url = (os.getenv("LYNXUS_OPENAI_COMPATIBLE_BASE_URL") or "").strip()
    model_id = (os.getenv("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID") or "").strip()
    if not base_url or not model_id:
        return None
    api_key_env = (os.getenv("LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR") or "OPENAI_COMPATIBLE_API_KEY").strip()
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
        organization=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION") or "").strip(),
        project=(os.getenv("LYNXUS_OPENAI_COMPATIBLE_PROJECT") or "").strip(),
    )


def _execute_model_tool_call(request: AgentTurnRequest, tool_call: SemanticToolCall) -> dict[str, Any]:
    return execute_tool_call(request, tool_call.tool_name, tool_call.arguments)


def _tool_result_privacy_source(tool_name: str) -> str:
    return f"tool_result:{tool_name}"


def _blocked_security_result(
    parsed: dict[str, Any],
    request: AgentTurnRequest,
    privacy_pipeline: Any,
) -> AgentTurnResult | None:
    assessment = _parse_security_assessment(parsed.get("securityAssessment"))
    if assessment is None or not _is_block_assessment(assessment):
        return None
    return _security_block_result(request, assessment, privacy_pipeline)


def _security_block_result(
    request: AgentTurnRequest,
    assessment: SecurityAssessment,
    privacy_pipeline: Any,
) -> AgentTurnResult:
    return AgentTurnResult(
        decision=AgentDecision.model_validate({"action": "NO_REPLY"}),
        sharedState=dict(request.sharedState),
        mappingTelemetry=privacy_pipeline.telemetry(),
        securityAssessment=assessment,
    )


def _parse_security_assessment(raw_value: Any) -> SecurityAssessment | None:
    if not isinstance(raw_value, dict):
        return None
    return SecurityAssessment.model_validate(raw_value)


def _is_block_assessment(assessment: SecurityAssessment) -> bool:
    return assessment.action.upper() == "BLOCK"


def _is_skill_read_request(parsed: dict[str, Any]) -> bool:
    return "skillReads" in parsed and "decision" not in parsed and "sharedState" not in parsed


def _parse_skill_reads(parsed: dict[str, Any], request: AgentTurnRequest, loaded_skill_ids: set[str]) -> list[str]:
    raw_value = parsed.get("skillReads")
    if not isinstance(raw_value, list):
        raise ValueError("skillReads must be an array")
    allowed_ids = {skill.resourceVersionId for skill in request.currentOwner.skills}
    requested_ids: list[str] = []
    for item in raw_value:
        resource_version_id = str(item or "").strip()
        if not resource_version_id:
            raise ValueError("skillReads items must be non-empty strings")
        if resource_version_id not in allowed_ids:
            raise ValueError(f"unknown skillResourceVersionId={resource_version_id}")
        if resource_version_id not in loaded_skill_ids and resource_version_id not in requested_ids:
            requested_ids.append(resource_version_id)
    if not requested_ids:
        raise ValueError("skillReads must request at least one new skill")
    return requested_ids


def _max_tool_steps() -> int:
    raw_value = (os.getenv("LYNXUS_AGENT_RUNTIME_MAX_TOOL_STEPS") or "").strip()
    if not raw_value:
        return DEFAULT_MAX_TOOL_STEPS
    try:
        return max(1, min(int(raw_value), 8))
    except ValueError:
        return DEFAULT_MAX_TOOL_STEPS


def _extract_json_object(content: Any) -> Any:
    if isinstance(content, list):
        text = "".join(
            part.get("text", "")
            for part in content
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        )
    else:
        text = str(content or "")
    text = text.strip()
    if not text:
        raise ValueError("empty model response")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end < start:
            raise
        return json.loads(text[start : end + 1])
