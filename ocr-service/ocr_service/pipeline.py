"""Full v1.5 pipeline: local CPU layout/ordering, remote region recognition."""

import contextlib
import importlib.metadata
import io
import logging
import os
import warnings

from .protocol import OcrError
from .provider import MODEL, create_http_client


def configure_pipeline(config, base_url, api_key, http_client):
    # SDK config copying is shallow on the inspected 3.4.0 path. Do not dump YAML:
    # this dictionary deliberately contains a secret and the actual client object.
    config["batch_size"] = 1
    config["markdown_ignore_labels"] = []
    config["use_doc_preprocessor"] = False
    config["use_layout_detection"] = True
    config["SubModules"]["LayoutDetection"]["batch_size"] = 1
    recognition = config["SubModules"]["VLRecognition"]
    recognition["batch_size"] = 1
    recognition["genai_config"] = {
        "backend": "vllm-server", "server_url": base_url, "max_concurrency": 1,
        "client_kwargs": {
            "model_name": MODEL, "api_key": api_key,
            "max_retries": 0, "http_client": http_client,
        },
    }
    return config


@contextlib.contextmanager
def quiet_sdk():
    # Third-party exceptions/progress can include original text or endpoint data.
    # Our dedicated AuditLog retains its stream and is unaffected by redirects.
    logging.disable(logging.CRITICAL)
    with open(os.devnull, "w") as sink:
        with contextlib.redirect_stdout(sink), contextlib.redirect_stderr(sink):
            yield


class PaddlePagePipeline:
    def __init__(self, base_url, api_key):
        self._context = None
        self.http_client = create_http_client(lambda: self._context, base_url)
        with quiet_sdk():
            if any(importlib.metadata.version(name) != "3.4.0"
                   for name in ("paddleocr", "paddlex")):
                raise RuntimeError("unsupported pipeline version")
            from paddleocr import PaddleOCRVL
            from paddlex.inference import load_pipeline_config

            config = configure_pipeline(load_pipeline_config("PaddleOCR-VL-1.5"),
                                        base_url, api_key, self.http_client)
            self.pipeline = PaddleOCRVL(
                pipeline_version="v1.5", paddlex_config=config,
                device="cpu", cpu_threads=1, use_queues=False,
                use_doc_orientation_classify=False, use_doc_unwarping=False,
            )

    def __call__(self, png, context):
        context.check()
        self._context = context
        try:
            with quiet_sdk():
                import numpy as np
                from PIL import Image

                with warnings.catch_warnings():
                    warnings.simplefilter("error", Image.DecompressionBombWarning)
                    try:
                        with Image.open(io.BytesIO(png)) as image:
                            if image.format != "PNG" or getattr(image, "n_frames", 1) != 1:
                                raise OcrError("invalid_request")
                            image.load()
                            page = np.ascontiguousarray(np.asarray(image.convert("RGB"))[:, :, ::-1])
                    except (OSError, ValueError, Image.DecompressionBombWarning,
                            Image.DecompressionBombError):
                        raise OcrError("invalid_request") from None
                context.check()
                results = list(self.pipeline.predict(page))
                context.check()
                if len(results) != 1:
                    raise OcrError("pipeline_failed")
                text = results[0].markdown["markdown_texts"]
                if not isinstance(text, str):
                    raise OcrError("pipeline_failed")
                return text
        except OcrError:
            raise
        except Exception:
            # The SDK wraps HTTP errors; preserve the already-latched safe reason.
            context.check()
            raise OcrError("pipeline_failed") from None
        finally:
            self._context = None

    def close(self):
        with quiet_sdk():
            self.pipeline.close()
