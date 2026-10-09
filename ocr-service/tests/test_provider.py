import json
import unittest

from ocr_service.pipeline import configure_pipeline
from ocr_service.provider import MODEL, prepare_request_body, validate_completion
from ocr_service.protocol import OcrError


def completion(reason="stop", content="完整合成正文"):
    return json.dumps({"choices": [{"finish_reason": reason,
                                   "message": {"role": "assistant", "content": content}}]}).encode()


class ProviderTest(unittest.TestCase):
    def test_wire_caps_removed_without_changing_message(self):
        original = {"model": MODEL, "messages": [{"role": "user", "content": "完整输入"}],
                    "max_tokens": 4096, "max_completion_tokens": 8192}
        expected = {"model": MODEL, "messages": original["messages"]}
        self.assertEqual(json.loads(prepare_request_body(json.dumps(original).encode())), expected)

    def test_only_complete_plain_text_success(self):
        self.assertEqual(validate_completion(200, completion()), "完整合成正文")
        for reason in ("length", "tool_calls", None, "content_filter"):
            with self.subTest(reason=reason), self.assertRaises(OcrError):
                validate_completion(200, completion(reason))
        for content in (None, ["not plain text"], {"value": "text"}):
            with self.subTest(content=content), self.assertRaises(OcrError):
                validate_completion(200, completion(content=content))

    def test_errors_are_safe_codes_only(self):
        for status, body in ((503, b"private upstream diagnostic"), (200, b"broken")):
            with self.assertRaises(OcrError) as result:
                validate_completion(status, body)
            self.assertNotIn("private", str(result.exception))

    def test_pipeline_has_local_layout_and_serial_no_retry_cloud_recognition(self):
        config = {"SubModules": {
            "LayoutDetection": {"batch_size": 8},
            "VLRecognition": {"batch_size": 4096, "genai_config": {}},
        }}
        client = object()
        result = configure_pipeline(config, "https://api.siliconflow.cn/v1", "synthetic-key", client)
        self.assertEqual(result["batch_size"], 1)
        self.assertEqual(result["SubModules"]["LayoutDetection"]["batch_size"], 1)
        vl = result["SubModules"]["VLRecognition"]
        self.assertEqual(vl["batch_size"], 1)
        self.assertEqual(vl["genai_config"]["backend"], "vllm-server")
        self.assertEqual(vl["genai_config"]["max_concurrency"], 1)
        kwargs = vl["genai_config"]["client_kwargs"]
        self.assertEqual(kwargs["model_name"], "PaddlePaddle/PaddleOCR-VL-1.5")
        self.assertEqual(kwargs["max_retries"], 0)
        self.assertIs(kwargs["http_client"], client)
        self.assertEqual(result["markdown_ignore_labels"], [])


if __name__ == "__main__":
    unittest.main()
