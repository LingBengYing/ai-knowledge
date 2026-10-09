# Private PaddleOCR page service

This service is an OCR-only companion to the Java knowledge library. Java owns original files, PDF page numbers, parsing revisions, evidence, storage and publication. Python receives one rendered PNG page and returns the **full PaddleOCR-VL 1.5 pipeline's Markdown** as page text. It does not implement knowledge answers, embedding, reranking, indexing, or database access.

The pipeline uses local CPU **PP-DocLayoutV3** layout detection and reading order. It sends each detected recognition region to SiliconFlow's exact `PaddlePaddle/PaddleOCR-VL-1.5` model through OpenAI-compatible chat completions. A page is **not** assumed to equal one model HTTP request. No whole-page chat-only replacement is presented as layout-aware OCR. An empty layout may legitimately produce empty text and zero model calls.

## Runtime

Production sets `OCR_LAYOUT_MODEL_DIR` to the pre-provisioned, checksum-verified PP-DocLayoutV3 directory and `PADDLE_PDX_CACHE_HOME` to a private service-owned cache. Set these before importing Paddle; source-check disabling alone does not prevent downloads when weights are missing.

Use a dedicated Python 3.11 environment; do not add these dependencies to the existing knowledge Agent environment. Install the pinned requirements separately, run `pip check`, and provision the CPU layout model/cache under the service account before accepting production traffic. Only the layout model is local; the remote recognition backend must not download the local VLM weights. Document orientation and unwarping are disabled, while layout detection and reading order remain enabled.

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

- PaddleOCR and PaddleX are both runtime-checked at `3.4.0`.
- `use_queues=False`, page/layout/recognition `batch_size=1`, `max_concurrency=1`, and SDK `max_retries=0` prevent speculative region dispatch and automatic retries.
- The owned `GuardedAsyncClient.send` seam is injected as an actual client object through supported `client_kwargs`. There is no process-global HTTP monkey patch or modification of installed SDK files.
- The SDK internally supplies a 600-second timeout and defaults output token caps. This adapter replaces HTTP phase timeouts with the remaining **page** deadline and wraps the whole request/complete-body read in the same deadline. It explicitly removes `max_tokens` and `max_completion_tokens` at the wire boundary, leaving the provider's own capacity limit. Other request contents are preserved.
- Streaming model responses and redirects are not accepted. The HTTP response body is read with a hard byte bound. Only one plain-text choice with `finish_reason="stop"` succeeds. Connect failures, non-200 status, incomplete/length-limited output, malformed responses, timeouts and cancellation latch the page as failed; subsequent region sends are blocked.
- No partial page is published after any regional failure. Markdown filtering is explicitly `markdown_ignore_labels=[]`, preserving headers, footers and footnotes in the pipeline output rather than silently omitting them.
- On socket disconnect or page deadline, cancellation interrupts in-flight asynchronous HTTP and prevents future HTTP. Local CPU inference cannot be forcibly interrupted by `asyncio`; the socket watchdog still emits the deadline error and retains the active slot until that computation exits. Use the supervisor's stop timeout for a genuinely stuck native inference process. This is not a claim that a Python coroutine can kill native CPU work.

Each provider dispatch attempt has one `start` and one `finish` JSON audit record, including connection errors. The only fields are `request_id`, `event`, safe `status`, and `elapsed_ms`. Model content, URLs, HTTP headers, keys and external error strings are never logged. A failed start-audit write prevents dispatch. SDK stdout/stderr and Python logging are suppressed; the dedicated audit stream is retained. A `start` without a `finish` after process termination is an interrupted/unknown attempt, not success or an automatic retry authorization.

## Verification

Stdlib-only contract regression (synthetic pipeline, no network/model/weights):

```sh
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s tests -v
```

Real `httpx` wire regression with `MockTransport` (still no network/model/SDK/weights):

```sh
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s wire_tests -v
```

The contract suite covers framing, limits, fields, no extra URLs/paths, permissions, single active work, deadline/disconnect, fixed safe errors and accounting. The wire suite checks cap removal, SDK timeout override, complete response validation, connection-failure accounting and cancellation. Neither suite measures Paddle layout quality, provider OCR quality, production capacity or Java integration. Those require separately recorded real-Paddle and real-provider evidence before a release is called verified.

## Version-bound SDK source review

The integration was traced through the official pinned sources, not inferred from an unrelated whole-image OCR example:

- [PaddleOCR 3.4.0 wrapper](https://github.com/PaddlePaddle/PaddleOCR/blob/fc82f229112a03aff46cc53db6166a98ea65a8fa/paddleocr/_pipelines/paddleocr_vl.py)
- [PaddleX 3.4.0 pipeline](https://github.com/PaddlePaddle/PaddleX/blob/b1bfbc6fa0bcca335ead0c9308c4723c23ccaa06/paddlex/inference/pipelines/paddleocr_vl/pipeline.py)
- [PaddleX recognition predictor](https://github.com/PaddlePaddle/PaddleX/blob/b1bfbc6fa0bcca335ead0c9308c4723c23ccaa06/paddlex/inference/models/doc_vlm/predictor.py)

The inspected path shallow-copies configuration dictionaries and preserves the injected AsyncClient object. PaddleX executes recognition coroutines on one persistent SDK event-loop thread; this client is not used from a separate socket event loop. Revisit those assumptions before changing either pinned SDK version.
