"""Whole-page remote recognition: one SiliconFlow PaddleOCR-VL-1.5 call per page, no local model."""

import asyncio
import base64
import struct
import zlib

from .protocol import OcrError
from .provider import MODEL, create_http_client, validate_completion

# Same pixel ceiling Pillow uses for its decompression-bomb error threshold.
MAX_PIXELS = 2 * 89_478_485
PROMPT = "OCR:"


def check_png(png):
    """Walk chunks up to the first IDAT: valid IHDR, bounded size, not animated."""
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise OcrError("invalid_request")
    offset, first = 8, True
    try:
        while True:
            length, kind = struct.unpack("!I4s", png[offset:offset + 8])
            data = png[offset + 8:offset + 8 + length]
            crc = png[offset + 8 + length:offset + 12 + length]
            if len(data) != length or len(crc) != 4:
                raise ValueError()
            if struct.unpack("!I", crc)[0] != zlib.crc32(kind + data):
                raise ValueError()
            if first:
                if kind != b"IHDR" or length != 13:
                    raise ValueError()
                width, height = struct.unpack("!II", data[:8])
                if not width or not height or width * height > MAX_PIXELS:
                    raise ValueError()
                first = False
            elif kind == b"acTL":
                raise ValueError()
            elif kind == b"IDAT":
                return
            offset += 12 + length
    except (ValueError, struct.error):
        raise OcrError("invalid_request") from None


def page_request(png):
    image = "data:image/png;base64," + base64.b64encode(png).decode("ascii")
    return {
        "model": MODEL,
        "stream": False,
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": image}},
            {"type": "text", "text": PROMPT},
        ]}],
    }


class RemotePagePipeline:
    def __init__(self, base_url, api_key, transport=None):
        self.endpoint = base_url.rstrip("/") + "/chat/completions"
        self.base_url = base_url
        self.api_key = api_key
        self.transport = transport

    def __call__(self, png, context):
        context.check()
        check_png(png)
        return asyncio.run(self._recognize(png, context))

    async def _recognize(self, png, context):
        # A fresh client per page: connection pools must not outlive their event loop.
        client = create_http_client(lambda: context, self.base_url, transport=self.transport)
        try:
            response = await client.post(self.endpoint, json=page_request(png),
                                         headers={"Authorization": "Bearer " + self.api_key})
            # GuardedAsyncClient already validated; re-extract the single plain-text choice.
            return validate_completion(response.status_code, response.content)
        except OcrError:
            raise
        except Exception:
            context.check()
            raise OcrError("pipeline_failed") from None
        finally:
            await client.aclose()

    def close(self):
        pass
