from __future__ import annotations

import json
import os
import time
from typing import Any, Dict, List, Optional, TypedDict

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

try:
    from langgraph.graph import END, StateGraph
except Exception as exc:  # pragma: no cover
    raise RuntimeError("langgraph is required for agent-runtime") from exc


class McpInvocationSummary(BaseModel):
    capabilityName: str
    externalTicketId: str
    status: str
    recommendedAction: str
    detail: str


class NodeSnapshot(BaseModel):
    nodeKey: str
    nodeName: str
    status: str
    detail: str
    updatedAt: str


class WorkflowStartRequest(BaseModel):
    taskId: str
    workflowInstanceId: str
    scenarioId: str
    assistantId: str
    assistantName: str
    assistantReleaseVersion: str
    resourceAnchors: List[str]
    question: str
    requester: str
    operatorId: str
    assistantConfigJson: str
    graphSpecJson: str
    sessionContextJson: str


class WorkflowResult(BaseModel):
    workflowInstanceId: str
    status: str
    summary: str
    nodes: List[NodeSnapshot]
    escalationRequired: bool
    mcpSummary: McpInvocationSummary


class AgentState(TypedDict):
    request: WorkflowStartRequest
    assistant_config: Dict[str, Any]
    graph_spec: Dict[str, Any]
    session_context: Dict[str, Any]
    retrieval_hits: List[str]
    answer: str
    reasoning: str
    nodes: List[Dict[str, Any]]
    mcp_summary: Dict[str, Any]
    escalation_required: bool


app = FastAPI(title="lynxus-agent-runtime", version="0.1.0")


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def append_node(state: AgentState, key: str, name: str, detail: str, status: str = "COMPLETED") -> None:
    state["nodes"].append(
        {
            "nodeKey": key,
            "nodeName": name,
            "status": status,
            "detail": detail,
            "updatedAt": now_iso(),
        }
    )


def get_model_config(state: AgentState, node: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    if node and node.get("modelResource") and node["modelResource"].get("effectiveVersion"):
        config = node["modelResource"]["effectiveVersion"]["configuration"].get("llmModel")
        if config:
            return config
    resource = state["assistant_config"].get("defaultModelResource")
    if resource and resource.get("effectiveVersion"):
        config = resource["effectiveVersion"]["configuration"].get("llmModel")
        if config:
            return config
    raise HTTPException(status_code=500, detail="No active LLM model resource configured")


def get_prompt_config(state: AgentState, node: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    if node and node.get("promptTemplateResource") and node["promptTemplateResource"].get("effectiveVersion"):
        config = node["promptTemplateResource"]["effectiveVersion"]["configuration"].get("promptTemplate")
        if config:
            return config
    resource = state["assistant_config"].get("defaultPromptResource")
    if resource and resource.get("effectiveVersion"):
        config = resource["effectiveVersion"]["configuration"].get("promptTemplate")
        if config:
            return config
    return {
        "systemPrompt": "You are an enterprise agent.",
        "userPromptTemplate": "Question: {{question}}\nKnowledge: {{knowledge_context}}",
        "responseFormat": "markdown",
    }


def knowledge_hits(state: AgentState) -> List[str]:
    question = state["request"].question
    if "密码" in question:
        return [
            "密码重置可通过登录页的“忘记密码”完成。",
            "账号锁定时需要完成邮箱验证。",
            "连续失败会触发账号保护。"
        ]
    if "退款" in question or "投诉" in question:
        return [
            "退款申请需校验订单状态与支付时间。",
            "争议类订单建议进入人工协同流程。",
            "处理完成后同步工单与客户沟通记录。"
        ]
    return [
        "Lynxus 支持业务场景、助手、智能体和资源绑定模型。",
        "复杂问题可升级到人工处理节点。",
        "运行态应保留节点、工具与人工介入轨迹。"
    ]


async def call_llm(model_config: Dict[str, Any], prompt_config: Dict[str, Any], question: str, knowledge_context: List[str]) -> str:
    provider = model_config["providerType"]
    model_id = model_config["modelId"]
    base_url = model_config["baseUrl"].rstrip("/")
    api_key = os.getenv(model_config["apiKeyEnvVar"], "")
    if not api_key:
        raise HTTPException(status_code=500, detail=f"Missing API key env var: {model_config['apiKeyEnvVar']}")

    user_prompt = (
        prompt_config.get("userPromptTemplate", "{{question}}")
        .replace("{{question}}", question)
        .replace("{{knowledge_context}}", "\n".join(knowledge_context))
        .replace("{{conversation_summary}}", "")
    )
    system_prompt = prompt_config.get("systemPrompt", "You are an enterprise agent.")

    async with httpx.AsyncClient(timeout=60) as client:
        if provider in {"OPENAI", "OPENAI_COMPATIBLE"}:
            response = await client.post(
                f"{base_url}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json={
                    "model": model_id,
                    "temperature": model_config.get("temperature", 0.2),
                    "max_tokens": model_config.get("maxTokens", 1200),
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                },
            )
            response.raise_for_status()
            return response.json()["choices"][0]["message"]["content"]

        if provider == "ANTHROPIC":
            response = await client.post(
                f"{base_url}/messages",
                headers={"x-api-key": api_key, "anthropic-version": "2023-06-01"},
                json={
                    "model": model_id,
                    "max_tokens": model_config.get("maxTokens", 1200),
                    "system": system_prompt,
                    "messages": [{"role": "user", "content": user_prompt}],
                },
            )
            response.raise_for_status()
            return response.json()["content"][0]["text"]

        if provider == "GEMINI":
            response = await client.post(
                f"{base_url}/models/{model_id}:generateContent?key={api_key}",
                json={
                    "contents": [{"parts": [{"text": f"{system_prompt}\n\n{user_prompt}"}]}],
                    "generationConfig": {
                        "temperature": model_config.get("temperature", 0.2),
                        "maxOutputTokens": model_config.get("maxTokens", 1200),
                    },
                },
            )
            response.raise_for_status()
            return response.json()["candidates"][0]["content"]["parts"][0]["text"]

    raise HTTPException(status_code=400, detail=f"Unsupported provider: {provider}")


async def route_node(state: AgentState) -> AgentState:
    state["retrieval_hits"] = knowledge_hits(state)
    append_node(state, "knowledge-retrieval", "知识检索", "\n".join(state["retrieval_hits"]))
    return state


async def responder_node(state: AgentState) -> AgentState:
    graph_nodes = state["graph_spec"].get("nodes", [])
    responder = next((node for node in graph_nodes if node.get("role") in {"responder", "policy"}), None)
    model_config = get_model_config(state, responder)
    prompt_config = get_prompt_config(state, responder)
    answer = await call_llm(model_config, prompt_config, state["request"].question, state["retrieval_hits"])
    state["answer"] = answer
    append_node(state, "answer-generation", "回答生成", f"model={model_config['modelId']}\n{answer[:500]}")
    return state


async def mcp_node(state: AgentState) -> AgentState:
    question = state["request"].question
    human_handoff = any(word in question for word in ["投诉", "人工", "升级"])
    ticket_id = f"TICKET-{abs(hash(question + state['request'].requester)) % 100000}"
    state["mcp_summary"] = {
        "capabilityName": "创建协同工单",
        "externalTicketId": ticket_id,
        "status": "ACCEPTED" if human_handoff else "RECORDED",
        "recommendedAction": "HUMAN_HANDOFF" if human_handoff else "AUTO_CLOSE",
        "detail": "已创建人工协同工单，建议人工坐席接管。" if human_handoff else "已记录本次处理结果，无需人工介入。",
    }
    append_node(
        state,
        "mcp-ticketing",
        "MCP 协同调用",
        f"ticket={ticket_id} / action={state['mcp_summary']['recommendedAction']}",
    )
    state["escalation_required"] = human_handoff
    return state


async def finalize_node(state: AgentState) -> AgentState:
    append_node(
        state,
        "escalation-decision",
        "升级判定",
        "等待人工接管" if state["escalation_required"] else "流程结束",
        "WAITING_HUMAN" if state["escalation_required"] else "COMPLETED",
    )
    return state


def compile_graph():
    graph = StateGraph(AgentState)
    graph.add_node("route", route_node)
    graph.add_node("respond", responder_node)
    graph.add_node("mcp", mcp_node)
    graph.add_node("finalize", finalize_node)
    graph.set_entry_point("route")
    graph.add_edge("route", "respond")
    graph.add_edge("respond", "mcp")
    graph.add_edge("mcp", "finalize")
    graph.add_edge("finalize", END)
    return graph.compile()


@app.post("/agent-runs", response_model=WorkflowResult)
async def agent_run(request: WorkflowStartRequest) -> WorkflowResult:
    assistant_config = json.loads(request.assistantConfigJson or "{}")
    graph_spec = json.loads(request.graphSpecJson or "{}")
    session_context = json.loads(request.sessionContextJson or "{}")

    graph = compile_graph()
    state: AgentState = {
        "request": request,
        "assistant_config": assistant_config,
        "graph_spec": graph_spec,
        "session_context": session_context,
        "retrieval_hits": [],
        "answer": "",
        "reasoning": "",
        "nodes": [{
            "nodeKey": "question-received",
            "nodeName": "问题接收",
            "status": "COMPLETED",
            "detail": request.question,
            "updatedAt": now_iso(),
        }],
        "mcp_summary": {},
        "escalation_required": False,
    }
    result_state = await graph.ainvoke(state)
    summary = result_state["answer"]
    if result_state["mcp_summary"]:
        summary = f"{summary}\n\n{result_state['mcp_summary']['detail']}"

    return WorkflowResult(
        workflowInstanceId=request.workflowInstanceId,
        status="WAITING_HUMAN" if result_state["escalation_required"] else "COMPLETED",
        summary=summary,
        nodes=[NodeSnapshot(**node) for node in result_state["nodes"]],
        escalationRequired=result_state["escalation_required"],
        mcpSummary=McpInvocationSummary(**result_state["mcp_summary"]),
    )
