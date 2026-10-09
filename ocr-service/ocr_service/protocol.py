"""Versioned, bounded Unix-socket protocol. Documents are data, never commands."""

import base64
import binascii
import json
import socket
import struct
import time
import uuid
from dataclasses import dataclass

PROFILE = "paddleocr-vl-1.5-pipeline-v1"
MAX_REQUEST_BYTES = 15 * 1024 * 1024
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_TEXT_CODEPOINTS = 1_000_000
MAX_DEADLINE_SECONDS = 300
ERROR_CODES = frozenset({
    "invalid_request", "request_too_large", "deadline_exceeded", "cancelled",
    "busy", "upstream_unavailable", "invalid_model_response", "pipeline_failed",
    "response_too_large",
})
_FIELDS = frozenset({
    "version", "request_id", "profile", "deadline_epoch_ms", "mime", "image_base64",
})


class OcrError(Exception):
    """Only this fixed code may cross the service boundary; no upstream text."""

    def __init__(self, code, request_id=None):
        if code not in ERROR_CODES:
            raise ValueError("unknown safe error code")
        super().__init__(code)
        self.code = code
        self.request_id = request_id


@dataclass(frozen=True)
class PageRequest:
    request_id: str
    deadline_monotonic: float
    image: bytes


def canonical_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        return False


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def decode_request(payload):
    if len(payload) > MAX_REQUEST_BYTES:
        raise OcrError("request_too_large")
    identifier = None
    try:
        value = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object)
        if not isinstance(value, dict):
            raise ValueError()
        if canonical_uuid(value.get("request_id")):
            identifier = value["request_id"]
        if identifier is None or set(value) != _FIELDS:
            raise ValueError()
        if type(value["version"]) is not int or value["version"] != 1:
            raise ValueError()
        if value["profile"] != PROFILE or value["mime"] != "image/png":
            raise ValueError()
        if type(value["deadline_epoch_ms"]) is not int:
            raise ValueError()
        remaining = value["deadline_epoch_ms"] / 1000 - time.time()
        if remaining > MAX_DEADLINE_SECONDS:
            raise ValueError()
        if remaining <= 0:
            raise OcrError("deadline_exceeded", identifier)
        if not isinstance(value["image_base64"], str):
            raise ValueError()
        image = base64.b64decode(value["image_base64"], validate=True)
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError()
        return PageRequest(identifier, time.monotonic() + remaining, image)
    except OcrError:
        raise
    except (ValueError, TypeError, UnicodeError, binascii.Error, OverflowError):
        raise OcrError("invalid_request", identifier) from None


def encode_response(request_id, *, text=None, error_code=None):
    if not canonical_uuid(request_id):
        raise OcrError("invalid_request")
    value = {"version": 1, "request_id": request_id}
    if error_code is not None:
        if error_code not in ERROR_CODES or text is not None:
            raise OcrError("invalid_request", request_id)
        value["error_code"] = error_code
    else:
        if not isinstance(text, str) or len(text) > MAX_TEXT_CODEPOINTS:
            raise OcrError("response_too_large", request_id)
        value.update(profile=PROFILE, text=text)
    try:
        payload = json.dumps(value, ensure_ascii=False, allow_nan=False,
                             separators=(",", ":")).encode("utf-8")
    except (ValueError, UnicodeError):
        raise OcrError("invalid_model_response", request_id) from None
    if len(payload) > MAX_RESPONSE_BYTES:
        raise OcrError("response_too_large", request_id)
    return struct.pack("!I", len(payload)) + payload


def read_frame(connection, timeout=5.0):
    end = time.monotonic() + timeout

    def exact(length):
        parts = bytearray()
        while len(parts) < length:
            remaining = end - time.monotonic()
            if remaining <= 0:
                raise OcrError("invalid_request")
            connection.settimeout(remaining)
            try:
                chunk = connection.recv(min(length - len(parts), 65536))
            except (socket.timeout, OSError):
                raise OcrError("invalid_request") from None
            if not chunk:
                raise OcrError("invalid_request")
            parts.extend(chunk)
        return bytes(parts)

    length = struct.unpack("!I", exact(4))[0]
    if length > MAX_REQUEST_BYTES:
        raise OcrError("request_too_large")
    if not length:
        raise OcrError("invalid_request")
    return exact(length)
