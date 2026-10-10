"""Loopback HTTP boundary for the DB-GPT adapter."""

import asyncio
import hmac
import ipaddress
import json
import logging
import os
from collections import deque
from dataclasses import dataclass
from uuid import UUID
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import Field, StrictStr, ValidationError

from .runtime import ACTIONS, AgentFailure, CallbackBridge, STAGES, StrictModel, run_agent

LOG = logging.getLogger("knowledge_agent.service")


def log_failure(run_id: str, code: str, bridge: CallbackBridge | None):
    """Fixed diagnostic fields only: never log request, model, tool, or exception objects."""
    stage = bridge.stage if bridge is not None and bridge.stage in STAGES else "starting"
    action = bridge.action if bridge is not None and bridge.action in ACTIONS else "none"
    LOG.warning("knowledge_agent_failed run_id=%s code=%s stage=%s model_calls=%d tool_calls=%d action=%s",
                run_id, AgentFailure(code).code, stage,
                bridge.model_calls if bridge is not None else 0,
                bridge.tool_calls if bridge is not None else 0, action)


@dataclass(frozen=True)
class Settings:
    java_origin: str
    service_token: str
    timeout: float = 180

    def __post_init__(self):
        url = urlsplit(self.java_origin)
        try:
            loopback = ipaddress.ip_address(url.hostname or "").is_loopback
        except ValueError:
            loopback = False
        if (url.scheme != "http" or not loopback or not url.port or url.username or
                url.password or url.path not in ("", "/") or url.query or url.fragment):
            raise ValueError("invalid_java_origin")
        if len(self.service_token) < 32 or len(self.service_token) > 256 or any(c.isspace() for c in self.service_token):
            raise ValueError("invalid_service_token")
        if not 1 <= self.timeout <= 300:
            raise ValueError("invalid_timeout")

    @classmethod
    def from_env(cls):
        return cls(java_origin=os.environ.get("KNOWLEDGE_JAVA_ORIGIN", "http://127.0.0.1:18084"),
                   service_token=os.environ.get("AGENT_SERVICE_TOKEN", ""),
                   timeout=float(os.environ.get("AGENT_RUN_TIMEOUT_SECONDS", "180")))


class RunInput(StrictModel):
    run_id: StrictStr = Field(min_length=36, max_length=36)
    question: StrictStr = Field(min_length=1, max_length=16000)
    callback_token: StrictStr = Field(min_length=32, max_length=256)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    active = set()
    seen = set()
    completed = deque()

    @app.get("/health")
    async def health():
        return {"status": "ok", "engine": "dbgpt-0.8.2", "protocol": 1}

    @app.post("/v1/runs")
    async def runs(request: Request):
        expected = "Bearer " + settings.service_token
        if not hmac.compare_digest(request.headers.get("authorization", ""), expected):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > 80000:
                return JSONResponse({"error": "invalid_request"}, status_code=413)
        try:
            value = RunInput.model_validate(json.loads(data))
            if str(UUID(value.run_id)) != value.run_id or not value.question.strip() or any(c.isspace() for c in value.callback_token):
                raise ValueError()
        except (ValueError, UnicodeError, ValidationError):
            return JSONResponse({"error": "invalid_request"}, status_code=422)
        if value.run_id in active or value.run_id in seen:
            return JSONResponse({"error": "duplicate_run"}, status_code=409)
        if len(active) >= 4:
            return JSONResponse({"error": "agent_busy"}, status_code=429)
        active.add(value.run_id)
        task = None
        monitor = None
        bridge = None

        async def watch_disconnect():
            while not await request.is_disconnected():
                await asyncio.sleep(0.1)

        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(settings.timeout), trust_env=False, follow_redirects=False) as client:
                bridge = CallbackBridge(client, settings.java_origin, value.run_id, value.callback_token)
                task = asyncio.create_task(run_agent(value.question, bridge, settings.timeout))
                monitor = asyncio.create_task(watch_disconnect())
                done, _ = await asyncio.wait((task, monitor), return_when=asyncio.FIRST_COMPLETED)
                if task not in done:
                    task.cancel()
                    log_failure(value.run_id, "agent_cancelled", bridge)
                    return JSONResponse({"error": "agent_cancelled"}, status_code=409)
                return await task
        except AgentFailure as error:
            log_failure(value.run_id, error.code, bridge)
            return JSONResponse({"error": error.code}, status_code=502)
        except Exception:
            log_failure(value.run_id, "agent_execution_failed", bridge)
            return JSONResponse({"error": "agent_execution_failed"}, status_code=502)
        finally:
            pending = [t for t in (task, monitor) if t is not None]
            for pending_task in pending:
                if not pending_task.done():
                    pending_task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            active.discard(value.run_id)
            completed.append(value.run_id)
            seen.add(value.run_id)
            if len(completed) > 1024:
                seen.discard(completed.popleft())

    return app
