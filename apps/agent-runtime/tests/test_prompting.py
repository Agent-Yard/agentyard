import json

from agentyard_agent_runtime.models import ActivationRuntime
from agentyard_agent_runtime.prompting import build_system_instruction, build_turn_input_messages, render_openai_streaming_messages, render_message_blocks_for_prompt
from agentyard_agent_runtime.tooling import runtime_tool_registry
from runtime_fixtures import request_payload


def test_prompt_describes_continuing_instance_collaboration_and_human_decision_provenance():
    runtime = ActivationRuntime.model_validate(request_payload())
    prompt = build_system_instruction(runtime)
    assert runtime.participantId in prompt and runtime.sessionId in prompt
    assert "sourceMessageId" in prompt
    assert "Final confirmation belongs to the business initiator" in prompt
    assert "Role descriptions and tool visibility are not authorization" in prompt
    assert "do not blindly repeat" in prompt
    assert "human_handoff" not in runtime_tool_registry(runtime)
    assert "agent_create" in runtime_tool_registry(runtime)


def test_message_sender_task_and_source_survive_context_rendering():
    payload = request_payload()
    payload["trigger"].update(kind="DELEGATION", senderId="agent-coordinator", taskId="task-1")
    payload["trigger"]["body"] = {"text": "分析资料", "references": [{"objectId": "doc-1", "provider": "knowledge"}]}
    rendered = build_turn_input_messages(ActivationRuntime.model_validate(payload))
    assert len(rendered) == 2
    message = json.loads(rendered[1].content.split("\n", 1)[1])
    assert message["id"] == "message-1"
    assert message["taskId"] == "task-1"
    assert message["senderId"] == "agent-coordinator"
    assert rendered[1].privacy_source == "message:message-1"


def test_role_prompt_is_system_configuration_but_external_context_is_source_data():
    runtime = ActivationRuntime.model_validate(request_payload())
    messages = render_openai_streaming_messages(build_system_instruction(runtime), "Follow the configured research method.",
        build_turn_input_messages(runtime))
    assert messages[0]["role"] == "system"
    assert "Follow the configured research method" in messages[0]["content"]
    assert all(m["role"] == "user" for m in messages[1:])
    assert "knowledgeBinding" not in messages[0]["content"]


def test_message_blocks_preserve_rich_output_content():
    text = render_message_blocks_for_prompt([
        {"type": "TEXT", "text": "report"},
        {"type": "RICH_TEXT", "content": "**evidence**"},
        {"type": "IMAGE", "url": "https://example.test/report.png", "alt": "chart"},
        {"type": "CARD", "cardType": "result", "version": "1", "data": {"taskId": "task-1"}},
    ])
    assert all(value in text for value in ["report", "**evidence**", "chart", "task-1"])
