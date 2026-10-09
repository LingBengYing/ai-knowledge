"""Actual TCP integration: HTTP service -> upstream DB-GPT -> Java callback double."""

import asyncio
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

import httpx
import pytest
import uvicorn

from knowledge_agent.service import Settings, create_app
from tests.test_agent import RESULT, SOURCE, react

SERVICE_TOKEN = uuid4().hex
CALLBACK_TOKEN = uuid4().hex


@pytest.mark.asyncio
async def test_real_http_loopback_and_service_token(caplog):
    calls = []
    outputs = [react("knowledge_search", {"query": "灯塔"}), react("knowledge_read", {"source_ids": ["source-1"]}), react("terminate", {"result": RESULT})]

    class CallbackHandler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            assert self.headers["Authorization"] == "Bearer " + CALLBACK_TOKEN
            payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            action = self.path.rsplit("/", 1)[-1]
            calls.append((action, payload))
            if action == "model":
                response = {"content": outputs.pop(0)}
            elif action == "search":
                response = {"sources": [dict(SOURCE, excerpt="创建项目")]}
            else:
                response = {"sources": [dict(SOURCE, text="先创建项目，再保存。")]}
            body = json.dumps(response, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    callbacks = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
    callbacks_thread = threading.Thread(target=callbacks.serve_forever, daemon=True)
    callbacks_thread.start()
    settings = Settings(java_origin=f"http://127.0.0.1:{callbacks.server_port}", service_token=SERVICE_TOKEN)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(settings), access_log=False, log_level="critical"))
    server_thread = threading.Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
    server_thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started:
            assert time.monotonic() < deadline
            await asyncio.sleep(0.01)
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}", trust_env=False, timeout=30) as client:
            body = {"run_id": str(uuid4()), "question": "整理灯塔步骤", "callback_token": CALLBACK_TOKEN}
            denied = await client.post("/v1/runs", json=body)
            assert denied.status_code == 401 and not calls
            response = await client.post("/v1/runs", json=body, headers={"Authorization": "Bearer " + SERVICE_TOKEN})
            assert response.status_code == 200, response.text
            assert response.json() == RESULT
            duplicate = await client.post("/v1/runs", json=body, headers={"Authorization": "Bearer " + SERVICE_TOKEN})
            assert duplicate.status_code == 409
            assert [name for name, _ in calls] == ["model", "search", "model", "read", "model"]
            outputs.append(react("forbidden_private_tool", {"input": "private tool body"}))
            failed_run = str(uuid4())
            failed = await client.post("/v1/runs", headers={"Authorization": "Bearer " + SERVICE_TOKEN},
                json={"run_id": failed_run, "question": "private synthetic question", "callback_token": CALLBACK_TOKEN})
            assert failed.status_code == 502 and failed.json() == {"error": "agent_invalid_action"}
            assert [name for name, _ in calls[5:]] == ["model"]
            assert failed_run in caplog.text and "code=agent_invalid_action stage=action model_calls=1 tool_calls=0" in caplog.text
            for private in ("forbidden_private_tool", "private tool body", "private synthetic question", SERVICE_TOKEN, CALLBACK_TOKEN):
                assert private not in caplog.text and private not in failed.text
    finally:
        server.should_exit = True
        await asyncio.to_thread(server_thread.join, 5)
        callbacks.shutdown()
        callbacks.server_close()
        callbacks_thread.join(5)
        listener.close()
