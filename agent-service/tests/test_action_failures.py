"""Real DB-GPT action-loop regression; all callbacks are synthetic/local."""

from uuid import uuid4

import httpx
import pytest

from knowledge_agent.runtime import AgentFailure, CallbackBridge, run_agent
from test_agent import CALLBACK_TOKEN, JavaCallbacks, react


@pytest.mark.asyncio
@pytest.mark.parametrize("empty_sources", [False, True])
async def test_three_searches_then_legitimate_refusal_completes(empty_sources):
    refused = {"refused": True, "statements": [], "suggestions": []}
    callbacks = JavaCallbacks([
        *[react("knowledge_search", {"query": "synthetic query"}) for _ in range(3)],
        react("terminate", {"result": refused}),
    ])

    async def handle(request):
        response = await callbacks(request)
        if empty_sources and request.url.path.endswith("/search"):
            return httpx.Response(200, json={"sources": []})
        return response

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        assert await run_agent("synthetic question", bridge) == refused
    assert bridge.model_calls == 4 and bridge.tool_calls == 3
    assert bridge.failure is None and not bridge.read_sources


@pytest.mark.asyncio
@pytest.mark.parametrize("searches", [1, 3])
@pytest.mark.parametrize("ids", [["unknown"], ["source-1", "source-1"]])
async def test_invalid_read_preserves_specific_failure_without_callback(searches, ids):
    callbacks = JavaCallbacks([
        *[react("knowledge_search", {"query": "synthetic query"}) for _ in range(searches)],
        react("knowledge_read", {"source_ids": ids}),
    ])
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        with pytest.raises(AgentFailure, match="^agent_invalid_tool_input$"):
            await run_agent("synthetic question", bridge)
    assert bridge.failure == "agent_invalid_tool_input"
    assert bridge.model_calls == searches + 1 and bridge.tool_calls == searches
    assert all(name != "read" for name, _ in callbacks.calls)


@pytest.mark.asyncio
async def test_invalid_read_callback_cause_survives_upstream_action_wrapper():
    callbacks = JavaCallbacks([
        react("knowledge_search", {"query": "synthetic"}),
        react("knowledge_read", {"source_ids": ["source-1"]}),
    ])

    async def handle(request):
        response = await callbacks(request)
        if request.url.path.endswith("/read"):
            return httpx.Response(200, json={"sources": []})
        return response

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        with pytest.raises(AgentFailure, match="^agent_callback_invalid$"):
            await run_agent("synthetic", bridge)
    assert bridge.failure == "agent_callback_invalid"
    assert bridge.action == "knowledge_read" and bridge.model_calls == 2 and bridge.tool_calls == 2


def test_first_failure_is_safe_and_sticky():
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
    with pytest.raises(AgentFailure, match="^agent_invalid_tool_input$"):
        bridge.fail("agent_invalid_tool_input")
    with pytest.raises(AgentFailure, match="^agent_invalid_tool_input$"):
        bridge.fail("private exception body")
    assert bridge.failure == "agent_invalid_tool_input"


@pytest.mark.parametrize("action,expected", [("knowledge_read", "knowledge_read"),
                                           ("private synthetic action", "none")])
def test_action_diagnostic_never_emits_arbitrary_action(caplog, action, expected):
    from knowledge_agent.service import log_failure
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
    bridge.action = action
    log_failure(str(uuid4()), "agent_tool_failed", bridge)
    assert "action=" + expected in caplog.text
    assert "private synthetic action" not in caplog.text and CALLBACK_TOKEN not in caplog.text
