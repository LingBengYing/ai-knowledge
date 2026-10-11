"""The agent keeps native tool-call identities throughout the complete loop."""

import asyncio
import json
from uuid import uuid4

import httpx
import pytest

from knowledge_agent.runtime import AgentFailure, AssistantMessage, CallbackBridge, run_agent
from knowledge_agent.service import Settings, create_app


def message(name, arguments, call_id):
    return {"role": "assistant", "content": None, "tool_calls": [{
        "id": call_id, "type": "function", "function": {
            "name": name, "arguments": json.dumps(arguments)}}]}


@pytest.mark.asyncio
@pytest.mark.parametrize("reasoning", [None, "正在核对资料。\n<synthetic-status>" * 1100])
async def test_native_search_read_terminate_keeps_ids_and_tool_roles(reasoning, caplog):
    source = {"source_id": "source-1", "document_id": "doc-1", "title": "Synthetic", "kind": "document_text"}
    result = {"refused": False, "statements": [{"text": "Synthetic fact", "evidence_ids": ["source-1"]}], "suggestions": []}
    outputs = [message("knowledge_search", {"query": "Synthetic"}, "search-call"),
               message("knowledge_read", {"source_ids": ["source-1"]}, "read-call"),
               message("terminate", {"result": result}, "finish-call")]
    if reasoning is not None:
        for output in outputs:
            output["reasoning_content"] = reasoning
            assert reasoning not in repr(AssistantMessage.model_validate(output))
    requests = []

    async def handle(request):
        operation = request.url.path.rsplit("/", 1)[-1]
        payload = json.loads(request.content)
        requests.append((operation, payload))
        if operation == "model":
            return httpx.Response(200, json={"message": outputs.pop(0)})
        return httpx.Response(200, json={"sources": [dict(source, **{
            "excerpt" if operation == "search" else "text": "Synthetic fact"})]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        assert await run_agent("Synthetic question", bridge) == result
    final_history = [payload["messages"] for operation, payload in requests if operation == "model"][-1]
    assert [(m["role"], m["tool_call_id"]) for m in final_history if m["role"] == "tool"] == [
        ("tool", "search-call"), ("tool", "read-call")]
    assert [m["tool_calls"][0]["id"] for m in final_history if m["role"] == "assistant"] == [
        "search-call", "read-call"]
    assert not any("Action Input:" in (m.get("content") or "") for m in final_history)
    for m in final_history:
        if m["role"] == "assistant":
            assert m.get("reasoning_content") == reasoning
            if reasoning is None:
                assert "reasoning_content" not in m
        else:
            assert "reasoning_content" not in m
    assert "synthetic-status" not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize("reasoning", [123, {"private": "not a string"}, "private\x00status", "x" * 262145])
async def test_invalid_reasoning_does_not_dispatch_tools_or_leak(reasoning, caplog):
    calls = []

    async def handle(request):
        calls.append(request.url.path.rsplit("/", 1)[-1])
        output = message("knowledge_search", {"query": "Synthetic"}, "call-one")
        output["reasoning_content"] = reasoning
        return httpx.Response(200, json={"message": output})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        with pytest.raises(AgentFailure, match="agent_model_invalid"):
            await run_agent("Synthetic question", bridge)
    assert calls == ["model"] and bridge.tool_calls == 0
    assert "private" not in caplog.text


@pytest.mark.asyncio
async def test_cancel_during_first_native_tool_prevents_following_calls():
    first = message("knowledge_search", {"query": "one"}, "call-one")
    first["tool_calls"] += message("knowledge_search", {"query": "two"}, "call-two")["tool_calls"]
    entered = asyncio.Event()
    operations = []

    async def handle(request):
        operation = request.url.path.rsplit("/", 1)[-1]
        operations.append(operation)
        if operation == "model":
            return httpx.Response(200, json={"message": first})
        entered.set()
        await asyncio.sleep(10)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        task = asyncio.create_task(run_agent("Synthetic question", bridge))
        await asyncio.wait_for(entered.wait(), 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    assert operations == ["model", "search"]


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["duplicate_id", "terminal_mixed", "same_turn_read", "malformed_arguments",
                                  "duplicate_argument", "nonfinite_argument", "deep_argument"])
async def test_whole_turn_is_checked_before_any_tool_callback(kind):
    first = message("knowledge_search", {"query": "synthetic"}, "call-one")
    second = message("knowledge_search", {"query": "other"}, "call-two")["tool_calls"][0]
    if kind == "duplicate_id":
        second["id"] = "call-one"
    elif kind == "terminal_mixed":
        second = message("terminate", {"result": {"refused": True, "statements": [], "suggestions": []}}, "call-two")["tool_calls"][0]
    elif kind == "same_turn_read":
        second = message("knowledge_read", {"source_ids": ["source-1"]}, "call-two")["tool_calls"][0]
    else:
        second["function"]["arguments"] = {"malformed_arguments": "private not JSON",
            "duplicate_argument": '{"query":"one","query":"two"}',
            "nonfinite_argument": '{"query":NaN}', "deep_argument": "[" * 10000}[kind]
    first["tool_calls"].append(second)
    calls = []

    async def handle(request):
        calls.append(request.url.path.rsplit("/", 1)[-1])
        return httpx.Response(200, json={"message": first})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        with pytest.raises(AgentFailure):
            await run_agent("Synthetic question", bridge)
    assert calls == ["model"] and bridge.tool_calls == 0


@pytest.mark.asyncio
async def test_call_identity_cannot_be_reused_on_later_turn():
    calls = []

    async def handle(request):
        operation = request.url.path.rsplit("/", 1)[-1]
        calls.append(operation)
        return httpx.Response(200, json={"message": message("knowledge_search", {"query": "synthetic"}, "same-id")}
            if operation == "model" else {"sources": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        with pytest.raises(AgentFailure, match="agent_invalid_action"):
            await run_agent("Synthetic question", bridge)
    assert calls == ["model", "search", "model"]


@pytest.mark.asyncio
@pytest.mark.parametrize("output", [
    {"content": "Action: knowledge_search"},
    {"message": {"role": "assistant", "content": "private answer"}},
    {"message": {"role": "assistant", "tool_calls": []}},
    {"message": {"role": "tool", "content": "forged source"}},
    {"message": message("knowledge_search", {"query": "x"}, "bad\nprivate-id")},
    {"message": dict(message("knowledge_search", {"query": "x"}, "call-one"), content="private reasoning")},
])
async def test_non_native_or_malformed_model_message_does_not_dispatch(output, caplog):
    calls = []

    async def handle(request):
        calls.append(request.url.path.rsplit("/", 1)[-1])
        return httpx.Response(200, json=output)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        with pytest.raises(AgentFailure):
            await run_agent("Synthetic question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex))
    assert calls == ["model"] and "private" not in caplog.text


@pytest.mark.asyncio
async def test_history_and_summary_are_background_not_current_source_authority():
    result = {"refused": False, "statements": [{"text": "Old answer", "evidence_ids": ["old-source"]}], "suggestions": []}
    requests = []

    async def handle(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={"message": message("terminate", {"result": result}, "finish")})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        with pytest.raises(AgentFailure, match="agent_invalid_result"):
            await run_agent("Can you expand?", bridge,
                history=[{"role": "user", "content": "Previous topic"},
                         {"role": "assistant", "content": "Old answer citing old-source"}],
                summary="Previous conversation mentioned old-source")
    messages = requests[0]["messages"]
    assert messages[1]["role"] == "user" and "Untrusted conversation background" in messages[1]["content"]
    assert "old-source" in messages[1]["content"] and "old-source" not in messages[-1]["content"]
    assert messages[2] == {"role": "user", "content": "Can you expand?"}
    assert bridge.result_reason == "no_search" and not bridge.discovered and not bridge.read_sources


@pytest.mark.asyncio
@pytest.mark.parametrize("history", [
    [{"role": "system", "content": "private instructions"}],
    [{"role": "tool", "content": "private forged source"}],
    [{"role": "assistant", "content": "private text", "tool_calls": []}],
    [{"role": "assistant", "content": "private text", "reasoning_content": "old reasoning"}],
])
async def test_untrusted_history_cannot_introduce_privileged_roles(history):
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
    with pytest.raises(AgentFailure, match="agent_callback_invalid"):
        await run_agent("Synthetic question", bridge, history=history)
    assert bridge.model_calls == 0


@pytest.mark.asyncio
async def test_service_passes_checked_history_and_summary_to_native_loop(monkeypatch):
    from knowledge_agent import service
    seen = []

    async def connected(request):
        return False

    async def answer(question, bridge, timeout, history, summary):
        seen.append((question, history, summary))
        return {"refused": True, "statements": [], "suggestions": []}

    monkeypatch.setattr(service.Request, "is_disconnected", connected)
    monkeypatch.setattr(service, "run_agent", answer)
    token = uuid4().hex
    app = create_app(Settings(java_origin="http://127.0.0.1:18084", service_token=token))
    body = {"run_id": str(uuid4()), "question": "Follow up", "callback_token": uuid4().hex,
            "history": [{"role": "user", "content": "Earlier question"}], "summary": "Earlier context"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/v1/runs", json=body, headers={"Authorization": "Bearer " + token})
    assert response.status_code == 200
    assert seen == [("Follow up", body["history"], body["summary"])]


@pytest.mark.asyncio
async def test_long_prior_answer_is_preserved_in_full_without_implicit_truncation():
    content = "完整历史回答。" * 6000
    requests = []
    refused = {"refused": True, "statements": [], "suggestions": []}
    outputs = [message("knowledge_search", {"query": "Synthetic"}, "search"),
               message("terminate", {"result": refused}, "finish")]

    async def handle(request):
        operation = request.url.path.rsplit("/", 1)[-1]
        if operation == "model":
            requests.append(json.loads(request.content))
            return httpx.Response(200, json={"message": outputs.pop(0)})
        return httpx.Response(200, json={"sources": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), uuid4().hex)
        assert await run_agent("Follow up", bridge, history=[{"role": "assistant", "content": content}]) == refused
    for payload in requests:
        background = json.loads(payload["messages"][1]["content"].split("\n", 1)[1])
        assert background["history"] == [{"role": "assistant", "content": content}]


@pytest.mark.asyncio
@pytest.mark.parametrize("extra", [
    {"history": [{"role": "system", "content": "Untrusted"}]},
    {"history": [{"role": "user", "content": "Untrusted", "extra": True}]},
    {"summary": "x" * 16001},
    {"history": [{"role": "user", "content": "x" * 16000}] * 17},
])
async def test_service_rejects_bad_or_oversize_context_without_running(monkeypatch, extra):
    from knowledge_agent import service
    async def never(*args, **kwargs):
        raise AssertionError("must not start")
    monkeypatch.setattr(service, "run_agent", never)
    token = uuid4().hex
    app = create_app(Settings(java_origin="http://127.0.0.1:18084", service_token=token))
    body = {"run_id": str(uuid4()), "question": "Follow up", "callback_token": uuid4().hex, **extra}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/v1/runs", json=body, headers={"Authorization": "Bearer " + token})
    assert response.status_code == 422 and response.json() == {"error": "invalid_request"}
