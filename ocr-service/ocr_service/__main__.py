"""Run with: python -m ocr_service (configuration only from private environment)."""

import os
import signal
import sys
import threading
from urllib.parse import urlsplit

from .execution import AuditLog
from .pipeline import PaddlePagePipeline
from .protocol import PROFILE
from .provider import MODEL
from .server import OcrServer


def read_config(environ):
    socket_path = environ.get("OCR_SOCKET", "")
    api_key = environ.get("OCR_API_KEY", "")
    base_url = environ.get("OCR_BASE_URL", "https://api.siliconflow.cn/v1").rstrip("/")
    parsed = urlsplit(base_url)
    if (not socket_path.startswith("/") or not api_key.strip()
            or environ.get("OCR_MODEL", MODEL) != MODEL
            or environ.get("OCR_PROFILE", PROFILE) != PROFILE
            or parsed.scheme != "https" or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path != "/v1"):
        raise ValueError("invalid private OCR configuration")
    return socket_path, api_key, base_url


def main():
    os.umask(0o077)
    stopped = threading.Event()
    server, pipeline = None, None
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda number, frame: stopped.set())
    try:
        socket_path, api_key, base_url = read_config(os.environ)
        os.environ.setdefault("OMP_NUM_THREADS", "1")
        os.environ.setdefault("MKL_NUM_THREADS", "1")
        audit = AuditLog(sys.stdout)
        pipeline = PaddlePagePipeline(base_url, api_key)
        server = OcrServer(socket_path, pipeline, audit)
        server.start()
        stopped.wait()
        return 0
    except Exception:
        # No traceback/config/provider diagnostic may leak through systemd logs.
        return 1
    finally:
        if server is not None:
            server.close()
        if pipeline is not None:
            try:
                pipeline.close()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
