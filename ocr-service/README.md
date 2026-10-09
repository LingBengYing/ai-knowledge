# Private PaddleOCR page service

This service is an OCR-only companion to the Java knowledge library. Java owns original files, PDF page numbers, parsing revisions, evidence, storage and publication. Python receives one rendered PNG page and returns the **full PaddleOCR-VL 1.5 pipeline's Markdown** as page text. It does not implement knowledge answers, embedding, reranking, indexing, or database access.

No local model runs. Each rendered page PNG is sent as one image to SiliconFlow's exact `PaddlePaddle/PaddleOCR-VL-1.5` model through OpenAI-compatible chat completions (prompt `OCR:`), and the single plain-text reply is the page text. Layout detection and reading order are left to the remote model; there is no PaddlePaddle, PaddleX or PP-DocLayoutV3 dependency.

## Runtime

Use a dedicated Python 3.11 environment with `requirements.txt` (only `httpx`).

From this directory:

```sh
python -m ocr_service
```

Private environment variables:

| Variable | Meaning |
| --- | --- |
| `OCR_SOCKET` | Required absolute Unix socket path; parent must already be owned by the service UID with mode `0700`, or be a new immediate directory. |
| `OCR_API_KEY` | Required provider credential, only in a private `0600` environment file; never passed to Java. |
| `OCR_BASE_URL` | Default `https://api.siliconflow.cn/v1`; HTTPS `/v1` endpoint without URL credentials, query or fragment. |
| `OCR_MODEL` | Must be `PaddlePaddle/PaddleOCR-VL-1.5`; default is this exact value. |
| `OCR_PROFILE` | Must be `paddleocr-vl-1.5-pipeline-v1`; default is this exact value. |
| `OMP_NUM_THREADS`, `MKL_NUM_THREADS` | Default `1`; deployment should also bound CPU and memory. |

The process creates its socket with mode `0600`, sets umask `0077`, opens no TCP listener, and refuses an existing socket path rather than unlinking a potentially live listener. Supervision should use a private runtime directory, the same service UID as the Java socket client, and a finite stop timeout. A stale socket after forced termination must be verified and removed by the supervisor before restarting. No privileged account is required.

## Wire contract, version 1

Each connection carries exactly one request and response: a four-byte unsigned big-endian byte length, then UTF-8 JSON. The client must not half-close or send more bytes after its request; disconnect/extra data cancels that page.

Request fields, **no additional fields**:

```json
{
  "version": 1,
  "request_id": "00000000-0000-4000-8000-000000000001",
  "profile": "paddleocr-vl-1.5-pipeline-v1",
  "deadline_epoch_ms": 0,
  "mime": "image/png",
  "image_base64": "<base64 encoded PNG>"
}
```

`deadline_epoch_ms` must be an integer strictly in the future and at most 300 seconds away; `0` above is an intentionally non-executable placeholder. The UUID must use canonical lowercase representation. Duplicate JSON keys, booleans in numeric fields, arbitrary URLs/paths, non-PNG or animated PNG, and Pillow decompression-bomb inputs are rejected. The request frame is at most **15 MiB**. Initial frame reading has a total five-second limit, not a per-byte reset.

Success:

```json
{"version":1,"request_id":"00000000-0000-4000-8000-000000000001","profile":"paddleocr-vl-1.5-pipeline-v1","text":"full page Markdown"}
```

Failure:

```json
{"version":1,"request_id":"00000000-0000-4000-8000-000000000001","error_code":"deadline_exceeded"}
```

Responses are at most **4 MiB**; page text is at most **1,000,000 Unicode code points**. Exceeding either bound fails the entire page, never returns a truncated success. The profile and UUID bind the response to the Java request. If framing or invalid JSON/UUID prevents safe correlation, the server closes the connection without inventing an identifier.

Fixed failure codes: `invalid_request`, `request_too_large`, `deadline_exceeded`, `cancelled`, `busy`, `upstream_unavailable`, `invalid_model_response`, `pipeline_failed`, `response_too_large`. No external exception, model body, document text, filesystem path or credential appears in failures.

There is one active pipeline operation and no application work queue. A second valid request is rejected as `busy`. Its bounded handshake only recovers the UUID and never starts a pipeline operation. The kernel listener backlog is one. After cancellation, the active slot remains occupied until the local pipeline exits; it is not released while stale work is still running.

## Provider boundary and failure behavior

- Exactly one HTTPS request per page to `<OCR_BASE_URL>/chat/completions`, no automatic retry and no redirects. The `GuardedAsyncClient.send` seam rejects any other method or URL.
- HTTP phase timeouts are the remaining **page** deadline, and the whole request/complete-body read is wrapped in the same deadline. `max_tokens` and `max_completion_tokens` are never sent, leaving the provider's own capacity limit.
- Streaming responses are not accepted. The response body is read with a hard byte bound. Only one plain-text choice with `finish_reason="stop"` succeeds; connect failures, non-200 status, length-limited or malformed output, timeouts and cancellation fail the page with a fixed code. No partial page is ever returned.
- The PNG header is checked before any HTTP (valid chunks/CRC, non-zero size, at most ~179 megapixels, not animated).
- On socket disconnect or page deadline, cancellation interrupts the in-flight HTTP request.

Each provider dispatch attempt has one `start` and one `finish` JSON audit record, including connection errors. The only fields are `request_id`, `event`, safe `status`, and `elapsed_ms`. Model content, URLs, HTTP headers, keys and external error strings are never logged. A failed start-audit write prevents dispatch. A `start` without a `finish` after process termination is an interrupted/unknown attempt, not success or an automatic retry authorization.

## Verification

Stdlib-only contract regression (synthetic pipeline, no network/model):

```sh
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s tests -v
```

Real `httpx` wire regression with `MockTransport` (no network/model):

```sh
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s wire_tests -v
```

The contract suite covers framing, limits, fields, no extra URLs/paths, permissions, single active work, deadline/disconnect, fixed safe errors and accounting. The wire suite checks cap removal, SDK timeout override, complete response validation, connection-failure accounting and cancellation. Neither suite measures provider OCR quality, production capacity or Java integration. Those require separately recorded real-provider evidence before a release is called verified.
