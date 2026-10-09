import json
import struct
import time
import unittest
import uuid
import zlib

import httpx

from ocr_service.execution import PageContext
from ocr_service.pipeline import PROMPT, RemotePagePipeline, check_png
from ocr_service.protocol import OcrError
from ocr_service.provider import MODEL

BASE = "https://api.siliconflow.cn/v1"
SUCCESS = {"choices": [{"finish_reason": "stop", "message": {
    "role": "assistant", "content": "完整合成正文"}}]}


def chunk(kind, data):
    return struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data))


def png(width=2, height=2, extra=b""):
    header = struct.pack("!IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + extra
            + chunk(b"IDAT", zlib.compress(b"\x00" * 7 * height)) + chunk(b"IEND", b""))


class RemotePipelineTest(unittest.TestCase):
    def run_page(self, handler, image=None):
        self.events, self.seen = [], []

        def record(request):
            self.seen.append(request)
            return handler(request)

        context = PageContext(str(uuid.uuid4()), time.monotonic() + 5, self.events.append)
        pipeline = RemotePagePipeline(BASE, "synthetic-key", transport=httpx.MockTransport(record))
        return pipeline(image or png(), context)

    def test_one_whole_page_request_to_siliconflow_model(self):
        text = self.run_page(lambda request: httpx.Response(200, json=SUCCESS))
        self.assertEqual(text, "完整合成正文")
        self.assertEqual(len(self.seen), 1)
        request = self.seen[0]
        self.assertEqual(str(request.url), BASE + "/chat/completions")
        self.assertEqual(request.headers["authorization"], "Bearer synthetic-key")
        body = json.loads(request.content)
        self.assertEqual(body["model"], MODEL)
        self.assertEqual(body["model"], "PaddlePaddle/PaddleOCR-VL-1.5")
        self.assertIs(body["stream"], False)
        self.assertNotIn("max_tokens", body)
        content = body["messages"][0]["content"]
        self.assertTrue(content[0]["image_url"]["url"].startswith("data:image/png;base64,"))
        self.assertEqual(content[1]["text"], PROMPT)
        self.assertEqual([item["event"] for item in self.events], ["start", "finish"])
        self.assertNotIn("synthetic-key", json.dumps(self.events))

    def test_upstream_failure_and_truncation_are_safe_codes_without_retry(self):
        for response, code in (
            (httpx.Response(503, text="private upstream diagnostic"), "upstream_unavailable"),
            (httpx.Response(200, json={"choices": [{"finish_reason": "length", "message": {
                "content": "partial"}}]}), "invalid_model_response"),
        ):
            with self.subTest(code=code), self.assertRaises(OcrError) as failure:
                self.run_page(lambda request, value=response: value)
            self.assertEqual(failure.exception.code, code)
            self.assertEqual(len(self.seen), 1)
            self.assertNotIn("private", str(failure.exception))

    def test_invalid_png_is_rejected_before_any_http(self):
        for image in (b"\x89PNG\r\n\x1a\nsynthetic", png(width=0), png(width=20000, height=20000),
                      png(extra=chunk(b"acTL", b"\x00" * 8))):
            with self.subTest(size=len(image)), self.assertRaises(OcrError) as failure:
                self.run_page(lambda request: httpx.Response(200, json=SUCCESS), image)
            self.assertEqual(failure.exception.code, "invalid_request")
            self.assertEqual(self.seen, [])
        corrupt = bytearray(png())
        corrupt[20] ^= 1
        with self.assertRaises(OcrError):
            check_png(bytes(corrupt))


if __name__ == "__main__":
    unittest.main()
