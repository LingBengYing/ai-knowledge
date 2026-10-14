"""Synthetic tool-loop/prompt contracts, not a real-model semantic quality evaluation."""

import json
from uuid import uuid4

import httpx
import pytest

from knowledge_agent.runtime import CallbackBridge, run_agent
from test_agent import CALLBACK_TOKEN, JavaCallbacks, RESULT, SOURCE, tool_message


def checkpoint(messages):
    prefix = "\nResearch checkpoint: "
    found = [message["content"] for message in messages
             if message["role"] == "system" and prefix in message["content"]]
    assert len(found) == 1, "Each model turn must distinguish read evidence from unread candidates"
    return json.loads(found[0].split(prefix, 1)[1])


@pytest.mark.asyncio
async def test_peripheral_read_then_targeted_search_exposes_new_candidates_before_synthesis():
    question = "蓝队有培训证，可以申请观测站吗？"
    venue = {"source_id": "source-1", "document_id": "doc-venue", "title": "场地说明",
             "kind": "document_text"}
    eligibility = {"source_id": "source-2", "document_id": "doc-entry", "title": "申请条件",
                   "kind": "document_text"}
    original = {"source-1": "场地不得存放危险品。", "source-2": "持培训证的小队可以申请观测站。"}
    result = {"refused": False, "statements": [
        {"text": "按申请条件，有培训证的蓝队可以申请观测站。", "evidence_ids": ["source-2"]},
        {"text": "申请后的场地不得存放危险品。", "evidence_ids": ["source-1"]}], "suggestions": []}
    replies = [
        tool_message("knowledge_search", {"query": "蓝队 观测站"}),
        tool_message("knowledge_read", {"source_ids": ["source-1"]}),
        tool_message("knowledge_search", {"query": "观测站 申请资格 培训证"}),
        tool_message("knowledge_read", {"source_ids": ["source-2"]}),
        tool_message("terminate", {"result": result}),
    ]
    expected = [([], []), ([], ["source-1"]), (["source-1"], []),
                (["source-1"], ["source-2"]), (["source-1", "source-2"], [])]
    requests = []
    turn = 0

    async def respond(request):
        nonlocal turn
        operation = request.url.path.rsplit("/", 1)[-1]
        payload = json.loads(request.content)
        requests.append((operation, payload))
        if operation == "model":
            state = checkpoint(payload["messages"])
            assert state == {"read_source_ids": expected[turn][0],
                             "unread_candidate_ids": expected[turn][1]}
            assert [message["content"] for message in payload["messages"]
                    if message["role"] == "user"] == [question]
            response = replies[turn]
            turn += 1
            return httpx.Response(200, json={"message": response})
        if operation == "search":
            row = venue if payload["query"] == "蓝队 观测站" else eligibility
            return httpx.Response(200, json={"sources": [dict(row, excerpt=original[row["source_id"]])]})
        row = venue if payload["source_ids"] == ["source-1"] else eligibility
        return httpx.Response(200, json={"sources": [dict(row, text=original[row["source_id"]])]})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        assert await run_agent(question, bridge) == result
    assert [payload["query"] for operation, payload in requests if operation == "search"] == [
        "蓝队 观测站", "观测站 申请资格 培训证"]
    assert [payload["source_ids"] for operation, payload in requests if operation == "read"] == [
        ["source-1"], ["source-2"]]
    assert turn == 5, "The checkpoint must not add a fixed planner or summary model call"


@pytest.mark.asyncio
async def test_model_turns_receive_core_task_and_answer_composition_contract_without_extra_calls():
    callbacks = JavaCallbacks()
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        assert await run_agent("说明操作步骤，不要只列资料", bridge) == RESULT
    model_requests = [payload for operation, payload in callbacks.calls if operation == "model"]
    assert len(model_requests) == 3
    for payload in model_requests:
        policy = payload["messages"][0]["content"]
        assert "decide, explain, compare, follow a procedure, summarize, or find documents" in policy
        assert "Side-issue evidence is not a supported answer to the core question" in policy
        assert "Do this for a partial answer as well as before refusing" in policy
        assert "Lead with a direct answer to the core question or its precise unresolved gap" in policy
        assert "Organize statements by the user's task, not one statement per source" in policy
        assert "Do not write source IDs or citation markers in text; the server adds citations" in policy
        assert "List documents first only when the user explicitly asks to find or list documents" in policy
        assert "Omit sources you have identified as irrelevant" in policy


@pytest.mark.asyncio
async def test_unrelated_unread_candidate_does_not_force_a_read_or_another_model_call():
    callbacks = JavaCallbacks()
    unrelated = {"source_id": "source-2", "document_id": "doc-2",
                 "title": "UNTRUSTED_TITLE_INSTRUCTION", "kind": "document_text",
                 "excerpt": "另一设备的维护说明。"}

    async def respond(request):
        response = await callbacks(request)
        if request.url.path.endswith("/search"):
            return httpx.Response(200, json={"sources": [dict(SOURCE, excerpt="创建项目"), unrelated]})
        return response

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        assert await run_agent("灯塔操作步骤", bridge) == RESULT
    requests = [payload for operation, payload in callbacks.calls if operation == "model"]
    assert len(requests) == 3
    assert checkpoint(requests[-1]["messages"]) == {
        "read_source_ids": ["source-1"], "unread_candidate_ids": ["source-2"]}
    assert [payload["source_ids"] for operation, payload in callbacks.calls if operation == "read"] == [
        ["source-1"]]
    assert all("UNTRUSTED_TITLE_INSTRUCTION" not in message["content"]
               for payload in requests for message in payload["messages"] if message["role"] == "system")
