"""Result failures reveal only allowlisted causes, never model or source text."""
from uuid import uuid4

import httpx
import pytest

from knowledge_agent.runtime import AgentFailure, CallbackBridge, run_agent, validate_result
from knowledge_agent.service import log_failure
from test_agent import CALLBACK_TOKEN, SERVICE_TOKEN, RESULT, SOURCE, JavaCallbacks, tool_message


@pytest.mark.asyncio
@pytest.mark.parametrize("statements", [RESULT["statements"], []])
async def test_refusal_with_valid_content_finishes_after_actual_search_and_read(statements):
    proposal = dict(RESULT, refused=True, statements=statements)
    callbacks = JavaCallbacks([
        tool_message("knowledge_search", {"query": "synthetic topic"}),
        tool_message("knowledge_read", {"source_ids": ["source-1"]}),
        tool_message("terminate", {"result": proposal}),
    ])
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        result = await run_agent("synthetic partial question", bridge)
    assert result == dict(proposal, refused=not bool(statements))
    assert [name for name, _ in callbacks.calls] == ["model", "search", "model", "read", "model"]
    assert bridge.model_calls == 3 and bridge.search_calls == 1 and bridge.tool_calls == 2


@pytest.mark.parametrize("changes,reason", [
    ({"statements": [{"text": "private fact", "evidence_ids": ["unknown-source"]}]}, "unread_evidence"),
    ({"statements": [], "suggestions": [{"title": "gap", "reason": "missing detail", "document_ids": ["unknown-doc"]}]}, "unknown_suggestion_document"),
])
def test_refusal_normalization_never_bypasses_source_or_suggestion_validation(changes, reason):
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
    bridge.search_calls = 1
    bridge.read_sources = {"source-1": SOURCE}
    with pytest.raises(AgentFailure):
        validate_result(dict(RESULT, refused=True, **changes), bridge)
    assert bridge.result_reason == reason


@pytest.mark.parametrize("value,search,read,reason", [
    ("private not json", True, True, "invalid_json"),
    ("[" * 100000, True, True, "invalid_json"),
    (dict(RESULT, extra="private text"), True, True, "invalid_schema"),
    (RESULT, False, True, "no_search"),
    (dict(RESULT, statements=[]), True, True, "missing_statements"),
    (RESULT, True, False, "no_read"),
    (dict(RESULT, statements=[{"text": " ", "evidence_ids": ["source-1"]}]), True, True, "blank_statement"),
    (dict(RESULT, statements=[{"text": "private fact", "evidence_ids": ["private-source"]}]), True, True, "unread_evidence"),
    (dict(RESULT, suggestions=[{"title": "private title", "reason": "private reason", "document_ids": ["private-doc"]}]), True, True, "unknown_suggestion_document"),
])
def test_fixed_result_failure_causes(value, search, read, reason, caplog):
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
    bridge.search_calls = int(search)
    bridge.read_sources = {"source-1": SOURCE} if read else {}
    with pytest.raises(AgentFailure, match="agent_invalid_result"):
        validate_result(value, bridge)
    assert bridge.result_reason == reason
    log_failure(str(uuid4()), "agent_invalid_result", bridge)
    assert "result_reason=" + reason in caplog.text
    assert "private" not in caplog.text and CALLBACK_TOKEN not in caplog.text


def test_untrusted_diagnostic_reason_is_masked(caplog):
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
    bridge.result_reason = "private\nprovider text"
    log_failure(str(uuid4()), "agent_invalid_result", bridge)
    assert "result_reason=none" in caplog.text and "private" not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize("reason,expected", [
    ("invalid_json", "agent_result_invalid_json"),
    ("invalid_schema", "agent_result_invalid_schema"),
    ("no_search", "agent_result_no_search"),
    ("missing_statements", "agent_result_missing_statements"),
    ("no_read", "agent_result_no_read"),
    ("blank_statement", "agent_result_blank_statement"),
    ("unread_evidence", "agent_result_unread_evidence"),
    ("unknown_suggestion_document", "agent_result_unknown_suggestion_document"),
    ("private\nprovider diagnostic", "agent_invalid_result"),
])
async def test_result_http_failure_exposes_only_fixed_diagnostic_code(monkeypatch, reason, expected):
    from knowledge_agent import service
    async def connected(request):
        return False
    async def fail(question, bridge, timeout, **context):
        bridge.stage = "result"
        bridge.result_reason = reason
        raise AgentFailure("agent_invalid_result")
    monkeypatch.setattr(service.Request, "is_disconnected", connected)
    monkeypatch.setattr(service, "run_agent", fail)
    app = service.create_app(service.Settings(java_origin="http://127.0.0.1:18084", service_token=SERVICE_TOKEN))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post("/v1/runs", headers={"Authorization": "Bearer " + SERVICE_TOKEN},
            json={"run_id": str(uuid4()), "question": "private question", "callback_token": CALLBACK_TOKEN})
    assert response.status_code == 502 and response.json() == {"error": expected}
    assert "private" not in response.text and SERVICE_TOKEN not in response.text and CALLBACK_TOKEN not in response.text


@pytest.mark.asyncio
@pytest.mark.parametrize("ids,reason", [
    (["source-1", "source-1"], "duplicate_source"),
    (["doc-1"], "document_id_instead_of_source_id"),
    (["private-unknown"], "undiscovered_source"),
])
async def test_invalid_read_has_safe_cause_and_no_callback(ids, reason, caplog):
    calls = []
    async def never(request):
        calls.append(request)
        raise AssertionError("must not dispatch")
    async with httpx.AsyncClient(transport=httpx.MockTransport(never)) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        bridge.discovered = {"source-1": SOURCE}
        with pytest.raises(AgentFailure, match="agent_invalid_tool_input"):
            await bridge.read(ids)
    assert bridge.tool_reason == reason and not calls
    log_failure(str(uuid4()), "agent_invalid_tool_input", bridge)
    assert "tool_reason=" + reason in caplog.text and "private" not in caplog.text


def test_untrusted_tool_reason_is_masked(caplog):
    bridge = CallbackBridge(None, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
    bridge.tool_reason = "private\nprovider text"
    log_failure(str(uuid4()), "agent_invalid_tool_input", bridge)
    assert "tool_reason=none" in caplog.text and "private" not in caplog.text


@pytest.mark.asyncio
async def test_native_eight_step_search_read_terminate(capsys):
    outputs = []
    for index in range(3):
        outputs += [tool_message("knowledge_search", {"query": "synthetic " + str(index)}),
                    tool_message("knowledge_read", {"source_ids": ["source-1"]})]
    outputs += [tool_message("knowledge_search", {"query": "synthetic final"}),
                tool_message("terminate", {"result": RESULT})]
    callbacks = JavaCallbacks(outputs)
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        assert await run_agent("synthetic question", bridge) == RESULT
    assert bridge.model_calls == 8 and bridge.search_calls == 4 and bridge.tool_calls == 7
    assert [name for name, _ in callbacks.calls].count("read") == 3
    assert "private synthetic deliberation" not in capsys.readouterr().out


@pytest.mark.asyncio
async def test_each_model_step_receives_distinct_authoritative_identifier_sets():
    import json
    callbacks = JavaCallbacks()
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        bridge = CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN)
        assert await run_agent("synthetic question", bridge) == RESULT
    models = [payload["messages"] for name, payload in callbacks.calls if name == "model"]
    inventories = []
    for messages in models:
        policy = messages[-1]
        assert policy["role"] == "system"
        assert "Never use a document_id in knowledge_read.source_ids" in policy["content"]
        inventory = json.loads(policy["content"].split("\nIdentifier inventory: ", 1)[1])
        assert "text" not in inventory and "title" not in inventory
        inventories.append(inventory)
    assert inventories == [
        {"knowledge_read_source_ids": [], "terminate_evidence_ids": [], "suggestion_document_ids": []},
        {"knowledge_read_source_ids": ["source-1"], "terminate_evidence_ids": [], "suggestion_document_ids": []},
        {"knowledge_read_source_ids": ["source-1"], "terminate_evidence_ids": ["source-1"], "suggestion_document_ids": ["doc-1"]},
    ]


@pytest.mark.asyncio
async def test_research_policy_reformulates_and_answers_only_supported_scope():
    callbacks = JavaCallbacks()
    async with httpx.AsyncClient(transport=httpx.MockTransport(callbacks), trust_env=False) as client:
        await run_agent("synthetic question", CallbackBridge(client, "http://127.0.0.1:18084", str(uuid4()), CALLBACK_TOKEN))
    for name, payload in callbacks.calls:
        if name != "model":
            continue
        policy = "\n".join(m["content"] for m in payload["messages"] if m["role"] == "system")
        assert "reformulate into the relevant general category" in policy
        assert "Answer the supported portion with explicit limitations" in policy
        assert "Do not generalize a rule for one enterprise form" in policy
        assert "Only claims supported by read originals" in policy
