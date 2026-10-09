"""Explicit OpenAI HTTP seam; no global patching or SDK/site-package changes."""

import json
import time

from .protocol import MAX_RESPONSE_BYTES, OcrError

MODEL = "PaddlePaddle/PaddleOCR-VL-1.5"


def prepare_request_body(body):
    """The SDK hardcodes token caps; remove them only at this owned wire seam."""
    try:
        value = json.loads(body)
        if not isinstance(value, dict) or value.get("model") != MODEL:
            raise ValueError()
        if value.get("stream", False) is not False:
            raise ValueError()
        value.pop("max_tokens", None)
        value.pop("max_completion_tokens", None)
        return json.dumps(value, ensure_ascii=False, allow_nan=False,
                          separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, UnicodeError):
        raise OcrError("invalid_request") from None


def validate_completion(status, body):
    if status != 200:
        raise OcrError("upstream_unavailable")
    if len(body) > MAX_RESPONSE_BYTES:
        raise OcrError("response_too_large")
    try:
        value = json.loads(body)
        choices = value["choices"]
        if not isinstance(choices, list) or len(choices) != 1:
            raise ValueError()
        choice = choices[0]
        message = choice["message"]
        if (choice["finish_reason"] != "stop" or not isinstance(message["content"], str)
                or message.get("tool_calls") or message.get("refusal")):
            raise ValueError()
        return message["content"]
    except (ValueError, KeyError, TypeError, UnicodeError):
        raise OcrError("invalid_model_response") from None


def create_http_client(context_getter, base_url, httpx_module=None, transport=None):
    """Inject this AsyncClient OBJECT through the SDK's supported client_kwargs."""
    if httpx_module is None:
        import httpx as httpx_module
    httpx = httpx_module
    endpoint = base_url.rstrip("/") + "/chat/completions"

    class GuardedAsyncClient(httpx.AsyncClient):
        async def send(self, request, **kwargs):
            context = context_getter()
            if context is None:
                raise OcrError("invalid_request")
            context.check()
            if request.method != "POST" or str(request.url) != endpoint:
                context.cancel("invalid_request")
                raise OcrError("invalid_request", context.request_id)
            try:
                content = prepare_request_body(request.content)
            except OcrError as failure:
                context.cancel(failure.code)
                raise
            headers = [(key, value) for key, value in request.headers.multi_items()
                       if key.lower() not in ("content-length", "transfer-encoding")]
            extensions = dict(request.extensions)
            remaining = max(0.001, context.deadline - time.monotonic())
            extensions["timeout"] = {key: remaining for key in ("connect", "read", "write", "pool")}
            outgoing = httpx.Request(request.method, request.url, headers=headers,
                                     content=content, extensions=extensions)
            # Never allow redirects to another host or the SDK's unbounded read.
            kwargs.update(stream=True, follow_redirects=False)

            async def exchange():
                response = await super(GuardedAsyncClient, self).send(outgoing, **kwargs)
                try:
                    if response.status_code != 200:
                        raise OcrError("upstream_unavailable")
                    length = response.headers.get("content-length")
                    if length is not None:
                        try:
                            if int(length) < 0 or int(length) > MAX_RESPONSE_BYTES:
                                raise OcrError("response_too_large")
                        except ValueError:
                            raise OcrError("invalid_model_response") from None
                    body = bytearray()
                    async for chunk in response.aiter_bytes(chunk_size=65536):
                        context.check()
                        if len(body) + len(chunk) > MAX_RESPONSE_BYTES:
                            raise OcrError("response_too_large")
                        body.extend(chunk)
                    validate_completion(response.status_code, bytes(body))
                    # Fully consumed and validated response; OpenAI may now decode it.
                    clean_headers = [(key, value) for key, value in response.headers.multi_items()
                                     if key.lower() not in ("content-length", "content-encoding",
                                                            "transfer-encoding")]
                    return httpx.Response(response.status_code, headers=clean_headers,
                                          content=bytes(body), request=outgoing,
                                          extensions=response.extensions)
                finally:
                    await response.aclose()

            return await context.http_call(exchange)

    return GuardedAsyncClient(
        timeout=None, follow_redirects=False, trust_env=False,
        limits=httpx.Limits(max_connections=1, max_keepalive_connections=1),
        transport=transport if transport is not None else httpx.AsyncHTTPTransport(retries=0, trust_env=False),
    )
