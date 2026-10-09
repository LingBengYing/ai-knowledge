import asyncio
import base64
import json
import os
import socket
import struct
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path

from ocr_service.protocol import (
    MAX_REQUEST_BYTES,
    PROFILE,
    OcrError,
    decode_request,
    encode_response,
    read_frame,
)
from ocr_service.execution import PageContext
from ocr_service.server import OcrServer


def request(**overrides):
    value = {
        "version": 1,
        "request_id": str(uuid.uuid4()),
        "profile": PROFILE,
        "deadline_epoch_ms": int(time.time() * 1000) + 5000,
        "mime": "image/png",
        "image_base64": base64.b64encode(b"\x89PNG\r\n\x1a\nsynthetic").decode(),
    }
    value.update(overrides)
    return value


def frame(value):
    payload = json.dumps(value).encode()
    return struct.pack("!I", len(payload)) + payload


class ContractTest(unittest.TestCase):
    def test_strict_request_and_png_signature(self):
        value = request()
        parsed = decode_request(json.dumps(value).encode())
        self.assertEqual(parsed.request_id, value["request_id"])
        self.assertTrue(parsed.image.startswith(b"\x89PNG"))
        for replacement in (
            {"version": True}, {"deadline_epoch_ms": True},
            {"deadline_epoch_ms": int(time.time() * 1000) + 301000},
            {"profile": "other"}, {"mime": "application/pdf"},
            {"image_base64": "not base64"}, {"url": "https://example.invalid"},
            {"request_id": "not-a-uuid"},
        ):
            with self.subTest(replacement=replacement), self.assertRaises(OcrError):
                decode_request(json.dumps(request(**replacement)).encode())

    def test_duplicate_fields_rejected(self):
        with self.assertRaises(OcrError):
            decode_request(b'{"version":1,"version":1}')

    def test_huge_frame_rejected_before_allocating_body(self):
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        left.sendall(struct.pack("!I", MAX_REQUEST_BYTES + 1))
        with self.assertRaises(OcrError) as failure:
            read_frame(right)
        self.assertEqual(failure.exception.code, "request_too_large")

    def test_response_bound_and_codepoints(self):
        identifier = str(uuid.uuid4())
        encoded = encode_response(identifier, text="中文\n正文")
        self.assertEqual(json.loads(encoded[4:])["text"], "中文\n正文")
        with self.assertRaises(OcrError):
            encode_response(identifier, text="x" * 1000001)
        with self.assertRaises(OcrError):
            encode_response(identifier, text="\x00" * 900000)


class ExecutionTest(unittest.IsolatedAsyncioTestCase):
    async def test_finish_audit_failure_halts_next_region(self):
        events, called = [], []

        def audit(event):
            events.append(event)
            if event["event"] == "finish":
                raise OSError("log unavailable")

        context = PageContext(str(uuid.uuid4()), time.monotonic() + 5, audit)

        async def operation():
            called.append(True)
            return "response"

        for _ in range(2):
            with self.assertRaises(OcrError) as failure:
                await context.http_call(operation)
            self.assertEqual(failure.exception.code, "pipeline_failed")
        self.assertEqual(called, [True])
        self.assertEqual(len(events), 2)

    async def test_broken_audit_prevents_dispatch(self):
        called = []

        def failed_audit(event):
            raise OSError("log unavailable")

        context = PageContext(str(uuid.uuid4()), time.monotonic() + 5, failed_audit)

        async def operation():
            called.append(True)

        with self.assertRaises(OcrError):
            await context.http_call(operation)
        await asyncio.sleep(0)
        self.assertEqual(called, [])

    async def test_failure_halts_page_and_counts_connect_failure(self):
        events = []
        context = PageContext(str(uuid.uuid4()), time.monotonic() + 5, events.append)
        called = []

        async def failure():
            called.append(True)
            raise ConnectionError("MUST NOT APPEAR IN LOG")

        with self.assertRaises(OcrError):
            await context.http_call(failure)
        with self.assertRaises(OcrError):
            await context.http_call(failure)
        self.assertEqual(len(called), 1)
        self.assertEqual([item["event"] for item in events], ["start", "finish"])
        self.assertEqual(events[-1]["status"], "upstream_unavailable")
        self.assertNotIn("MUST NOT", json.dumps(events))

    async def test_cancellation_interrupts_inflight_and_prevents_next_call(self):
        events, started = [], asyncio.Event()
        context = PageContext(str(uuid.uuid4()), time.monotonic() + 5, events.append)

        async def blocked():
            started.set()
            await asyncio.Event().wait()

        active = asyncio.create_task(context.http_call(blocked))
        await started.wait()
        context.cancel("cancelled")
        with self.assertRaises(OcrError) as failure:
            await asyncio.wait_for(active, 1)
        self.assertEqual(failure.exception.code, "cancelled")
        with self.assertRaises(OcrError):
            await context.http_call(blocked)
        self.assertEqual(len(events), 2)

    async def test_global_deadline_not_sdk_timeout(self):
        events = []
        context = PageContext(str(uuid.uuid4()), time.monotonic() + 0.02, events.append)

        async def blocked():
            await asyncio.Event().wait()

        with self.assertRaises(OcrError) as failure:
            await context.http_call(blocked)
        self.assertEqual(failure.exception.code, "deadline_exceeded")
        self.assertEqual(events[-1]["status"], "deadline_exceeded")


class SocketTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = str(Path(self.directory.name) / "ocr.sock")

    def start(self, pipeline):
        self.server = OcrServer(self.path, pipeline, audit=lambda value: None)
        self.server.start()
        self.addCleanup(self.server.close)

    def connect(self):
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(2)
        connection.connect(self.path)
        self.addCleanup(connection.close)
        return connection

    def test_page_text_and_private_socket(self):
        self.start(lambda image, context: "# 合成标题\n合成正文")
        self.assertEqual(Path(self.path).stat().st_mode & 0o777, 0o600)
        connection, value = self.connect(), request()
        connection.sendall(frame(value))
        result = json.loads(read_frame(connection))
        self.assertEqual(result, {
            "version": 1, "request_id": value["request_id"],
            "profile": PROFILE, "text": "# 合成标题\n合成正文",
        })

    def test_insecure_parent_is_rejected_without_changing_its_permissions(self):
        os.chmod(self.directory.name, 0o755)
        server = OcrServer(self.path, lambda image, context: "text", lambda value: None)
        with self.assertRaises(ValueError):
            server.start()
        self.assertEqual(Path(self.directory.name).stat().st_mode & 0o777, 0o755)

    def test_page_deadline_replies_even_while_local_pipeline_has_not_returned(self):
        started, release = threading.Event(), threading.Event()
        captured = []

        def blocked(image, context):
            captured.append(context)
            started.set()
            release.wait(2)
            context.check()
            return "late partial"

        self.start(blocked)
        self.addCleanup(release.set)
        connection = self.connect()
        connection.sendall(frame(request(deadline_epoch_ms=int(time.time() * 1000) + 100)))
        self.assertTrue(started.wait(1))
        response = json.loads(read_frame(connection))
        self.assertEqual(response["error_code"], "deadline_exceeded")
        self.assertNotIn("text", response)
        self.assertTrue(captured[0].cancelled.is_set())

    def test_busy_is_rejected_and_disconnect_cancels_active_page(self):
        started, finished = threading.Event(), threading.Event()
        seen = []

        def pipeline(image, context):
            seen.append(context)
            started.set()
            context.cancelled.wait(2)
            finished.set()
            context.check()
            return "not returned"

        self.start(pipeline)
        first = self.connect()
        first.sendall(frame(request()))
        self.assertTrue(started.wait(1))
        second, value = self.connect(), request()
        second.sendall(frame(value))
        result = json.loads(read_frame(second))
        self.assertEqual(result["error_code"], "busy")
        self.assertEqual(result["request_id"], value["request_id"])
        self.assertEqual(len(seen), 1)
        first.close()
        self.assertTrue(finished.wait(1))
        self.assertEqual(seen[0].failure, "cancelled")

    def test_pipeline_failure_never_returns_partial_or_exception(self):
        def fail(image, context):
            raise RuntimeError("secret provider text")

        self.start(fail)
        connection = self.connect()
        connection.sendall(frame(request()))
        result = json.loads(read_frame(connection))
        self.assertEqual(result["error_code"], "pipeline_failed")
        self.assertNotIn("text", result)
        self.assertNotIn("secret", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
