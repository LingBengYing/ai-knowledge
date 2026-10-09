import json
from uuid import uuid4

import httpx
import pytest

from knowledge_agent.service import Settings, create_app
from knowledge_agent.runtime import AgentFailure, CallbackBridge, run_agent

SOURCE = {"source_id": "source-1", "document_id": "doc-1", "title": "灯塔指南", "kind": "document_text"}
RESULT = {"refused": False, "statements": [{"text": "先创建项目，再保存。", "evidence_ids": ["source-1"]}], "suggestions": [{"title": "整理操作指南", "reason": "合并零散步骤", "document_ids": ["doc-1"]}]}
SERVICE_TOKEN = uuid4().hex
CALLBACK_TOKEN = uuid4().hex
INVALID_CALLBACK_TOKEN = uuid4().hex[:12]


def react(action, payload):
    return "Thought: private synthetic deliberation\nAction: " + action + "\nAction Input: " + json.dumps(payload, ensure_ascii=False)


class JavaCallbacks:
    def __init__(self, outputs=None):
        self.outputs = list(outputs or [react("knowledge_search", {"query": "灯塔"}), react("knowledge_read", {"source_ids": ["source-1"]}), react("terminate", {"result": RESULT})])
        self.calls = []

    async def __call__(self, request):
        assert request.headers["authorization"] == "Bearer " + CALLBACK_TOKEN
        assert request.url.host == "127.0.0.1"
        self.calls.append((request.url.path.rsplit("/", 1)[-1], json.loads(request.content)))
        operation = self.calls[-1][0]
        if operation == "model":
            return httpx.Response(200, json={"content": self.outputs.pop(0)})
        if operation == "search":
            return httpx.Response(200, json={"sources": [dict(SOURCE, excerpt="创建项目") ]})
        return httpx.Response(200, json={"sources": [dict(SOURCE, text="先创建项目，再保存。") ]})


@pytest.mark.asyncio
async def test_real_upstream_agent_search_read_and_terminate(capsys):
    callbacks = JavaCallbacks()
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        result = await run_agent("整理灯塔使用步骤", bridge)
    assert result == RESULT
    assert [call[0] for call in callbacks.calls] == ["model", "search", "model", "read", "model"]
    assert "Observation:" in str(callbacks.calls[2][1])
    assert "private synthetic deliberation" not in capsys.readouterr().out


@pytest.mark.asyncio
@pytest.mark.parametrize("bad_result", [
    dict(RESULT, statements=[{"text": "invented", "evidence_ids": ["source-unknown"]}]),
    dict(RESULT, statements=[{"text": "missing", "evidence_ids": []}]),
    dict(RESULT, suggestions=[{"title": "bad", "reason": "bad", "document_ids": ["doc-unknown"]}]),
])
async def test_unknown_or_empty_evidence_fails_closed(bad_result):
    outputs = [react("knowledge_search", {"query": "灯塔"}), react("knowledge_read", {"source_ids": ["source-1"]}), react("terminate", {"result": bad_result})]
    async with httpx.AsyncClient(transport=httpx.MockTransport(JavaCallbacks(outputs))) as client:
        with pytest.raises(AgentFailure, match="agent_invalid_result"):
            await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))


@pytest.mark.asyncio
async def test_cannot_answer_without_reading():
    callbacks = JavaCallbacks([react("knowledge_search", {"query": "灯塔"}), react("terminate", {"result": RESULT})])
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks)) as client:
        with pytest.raises(AgentFailure, match="agent_invalid_result"):
            await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))


def test_origin_cannot_be_chosen_by_request_or_redirected():
    with pytest.raises(ValueError):
        Settings(java_origin="https://example.com", service_token=SERVICE_TOKEN)


@pytest.mark.asyncio
async def test_service_auth_and_validation_never_echo_secrets():
    app = create_app(Settings(java_origin="http://127.0.0.1:18084", service_token=SERVICE_TOKEN))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        health = await client.get("/health")
        assert health.json()["engine"] == "dbgpt-0.8.2"
        unauthorized = await client.post("/v1/runs", json={"question": "secret question"})
        assert unauthorized.status_code == 401
        invalid = await client.post("/v1/runs", headers={"Authorization": "Bearer " + SERVICE_TOKEN}, json={"question": "secret question", "callback_token": INVALID_CALLBACK_TOKEN})
        assert invalid.status_code == 422
        assert INVALID_CALLBACK_TOKEN not in invalid.text and "secret question" not in invalid.text


@pytest.mark.asyncio
async def test_callback_failure_stops_without_retry():
    calls = []
    async def fail(request):
        calls.append(request.url.path)
        return httpx.Response(401, json={"detail": "private server error"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(fail)) as client:
        with pytest.raises(AgentFailure, match="agent_callback_failed"):
            await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_unknown_tool_never_executes_or_retries():
    callbacks = JavaCallbacks([react("run_sql", {"sql": "SELECT 1"})])
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks)) as client:
        with pytest.raises(AgentFailure, match="agent_execution_failed"):
            await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))
    assert [name for name, _ in callbacks.calls] == ["model"]


@pytest.mark.asyncio
async def test_upstream_loop_is_reused_and_no_snapshot_is_written(monkeypatch, tmp_path):
    from dbgpt.agent import ConversableAgent
    from dbgpt.agent.resource import ToolPack
    from knowledge_agent.runtime import KnowledgeReActAgent
    assert KnowledgeReActAgent.generate_reply is ConversableAgent.generate_reply
    monkeypatch.setenv("DBGPT_HOME", str(tmp_path))
    observed = []
    upstream_build = KnowledgeReActAgent.build
    async def inspect_build(self, **kwargs):
        result = await upstream_build(self, **kwargs)
        observed.extend(t.name for pack in ToolPack.from_resource(self.resource) for t in pack.sub_resources)
        return result
    monkeypatch.setattr(KnowledgeReActAgent, "build", inspect_build)
    async with httpx.AsyncClient(transport=httpx.MockTransport(JavaCallbacks())) as client:
        await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))
    assert set(observed) == {"knowledge_search", "knowledge_read", "terminate"}
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_step_budget_and_timeout():
    callbacks = JavaCallbacks([react("knowledge_search", {"query": "灯塔"})] * 8)
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks)) as client:
        with pytest.raises(AgentFailure, match="agent_step_limit"):
            await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))
    assert sum(name == "model" for name, _ in callbacks.calls) == 8
    import asyncio
    calls = []
    async def delay(request):
        calls.append(1)
        await asyncio.sleep(10)
    async with httpx.AsyncClient(transport=httpx.MockTransport(delay)) as client:
        with pytest.raises(AgentFailure, match="agent_timeout"):
            await run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN), timeout=0.03)
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_cancelled_run_makes_no_later_callback():
    import asyncio
    calls = []
    entered = asyncio.Event()
    async def delay(request):
        calls.append(1)
        entered.set()
        await asyncio.sleep(10)
    async with httpx.AsyncClient(transport=httpx.MockTransport(delay)) as client:
        task = asyncio.create_task(run_agent("question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)))
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.sleep(0.03)
    assert len(calls) == 1


def test_java_protocol_read_and_suggestion_limits_match():
    from pydantic import ValidationError
    from knowledge_agent.runtime import ReadInput, Suggestion
    assert len(ReadInput(source_ids=[f"source-{i}" for i in range(32)]).source_ids) == 32
    with pytest.raises(ValidationError):
        ReadInput(source_ids=[f"source-{i}" for i in range(33)])
    with pytest.raises(ValidationError):
        Suggestion(title="title", reason="reason", document_ids=[f"doc-{i}" for i in range(33)])
