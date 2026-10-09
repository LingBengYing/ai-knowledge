import unittest

from ocr_service.__main__ import read_config
from ocr_service.provider import MODEL


class ConfigTest(unittest.TestCase):
    def test_explicit_private_environment_only(self):
        result = read_config({"OCR_SOCKET": "/private/run/ocr.sock", "OCR_API_KEY": "REPLACE_ME"})
        self.assertEqual(result[2], "https://api.siliconflow.cn/v1")

    def test_wrong_model_profile_and_credentials_in_url_rejected(self):
        baseline = {"OCR_SOCKET": "/private/run/ocr.sock", "OCR_API_KEY": "REPLACE_ME"}
        for replacement in (
            {"OCR_MODEL": "other"}, {"OCR_PROFILE": "other"}, {"OCR_API_KEY": ""},
            {"OCR_SOCKET": "relative.sock"}, {"OCR_BASE_URL": "http://remote.invalid/v1"},
            {"OCR_BASE_URL": "https://secret@remote.invalid/v1"},
            {"OCR_BASE_URL": "https://remote.invalid/v1?key=secret"},
        ):
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                read_config(dict(baseline, **replacement))
        self.assertEqual(read_config(dict(baseline, OCR_MODEL=MODEL))[1], "REPLACE_ME")


if __name__ == "__main__":
    unittest.main()
