"""Real httpx objects with MockTransport: no network, SDK or weights required."""

import asyncio
import json
import time
import unittest
import uuid

import httpx

from ocr_service.execution import PageContext
from ocr_service.protocol import MAX_RESPONSE_BYTES, OcrError
from ocr_service.provider import MODEL, create_http_client

ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
SUCCESS = {"choices": [{"finish_reason": "stop", "message": {
    "role": "assistant", "content": "完整合成正文"}}]}


class HttpxWireTest(unittest.IsolatedAsyncioTestCase):
    def make(self, handler, seconds=5):
        self.events = []
        self.context = PageContext(str(uuid.uuid4()), time.monotonic() + seconds, self.events.append)
        self.client = create_http_client(lambda: self.context,
                                         "https://api.siliconflow.cn/v1",
                                         transport=httpx.MockTransport(handler))
        self.addAsyncCleanup(self.client.aclose)

    async def post(self):
        return await self.client.post(ENDPOINT, json={
            "model": MODEL, "messages": [{"role": "user", "content": "synthetic-only"}],
            "max_tokens": 4096, "max_completion_tokens": 8192, "stream": False,
        }, timeout=600, headers={"Authorization": "Bearer synthetic-only"})

    async def test_wire_removes_sdk_caps_and_overrides_600_second_timeout(self):
        seen = []

        def handler(request):
            seen.append(request)
            return httpx.Response(200, json=SUCCESS)

        self.make(handler)
        response = await self.post()
        self.assertEqual(response.json(), SUCCESS)
        body = json.loads(seen[0].content)
        self.assertNotIn("max_tokens", body)
        self.assertNotIn("max_completion_tokens", body)
        self.assertEqual(body["messages"][0]["content"], "synthetic-only")
        self.assertEqual(body["model"], MODEL)
        self.assertTrue(all(0 < value <= 5 for value in seen[0].extensions["timeout"].values()))
        self.assertEqual([item["event"] for item in self.events], ["start", "finish"])
        self.assertNotIn("synthetic-only", json.dumps(self.events))

    async def test_connect_failure_is_counted_once_and_latches(self):
        seen = []

        def handler(request):
            seen.append(request)
            raise httpx.ConnectError("PRIVATE DIAGNOSTIC", request=request)

        self.make(handler)
        for _ in range(2):
            with self.assertRaises(OcrError):
                await self.post()
        self.assertEqual(len(seen), 1)
        self.assertEqual(len(self.events), 2)
        self.assertEqual(self.events[-1]["status"], "upstream_unavailable")
        self.assertNotIn("PRIVATE", json.dumps(self.events))

    async def test_nonstop_body_is_not_returned_and_no_next_region_dispatch(self):
        seen = []

        def handler(request):
            seen.append(request)
            return httpx.Response(200, json={"choices": [{"finish_reason": "length",
                                                         "message": {"content": "partial"}}]})

        self.make(handler)
        for _ in range(2):
            with self.assertRaises(OcrError) as failure:
                await self.post()
            self.assertEqual(failure.exception.code, "invalid_model_response")
        self.assertEqual(len(seen), 1)
        self.assertEqual(len(self.events), 2)

    async def test_oversize_chunked_body_is_closed_without_partial_success(self):
        class Body(httpx.AsyncByteStream):
            def __init__(self):
                self.closed = False

            async def __aiter__(self):
                for _ in range(MAX_RESPONSE_BYTES // 65536 + 1):
                    yield b"x" * 65536

            async def aclose(self):
                self.closed = True

        stream = Body()
        self.make(lambda request: httpx.Response(200, stream=stream))
        with self.assertRaises(OcrError) as failure:
            await self.post()
        self.assertEqual(failure.exception.code, "response_too_large")
        self.assertTrue(stream.closed)

    async def test_disconnect_cancels_inflight_http_without_second_region(self):
        started = asyncio.Event()

        async def handler(request):
            started.set()
            await asyncio.Event().wait()

        self.make(handler)
        running = asyncio.create_task(self.post())
        await started.wait()
        self.context.cancel("cancelled")
        with self.assertRaises(OcrError):
            await asyncio.wait_for(running, 1)
        with self.assertRaises(OcrError):
            await self.post()
        self.assertEqual(len(self.events), 2)
        self.assertEqual(self.events[-1]["status"], "cancelled")


if __name__ == "__main__":
    unittest.main()
