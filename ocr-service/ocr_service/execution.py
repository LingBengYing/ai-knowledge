"""Page cancellation, a shared deadline and complete HTTP-attempt accounting."""

import asyncio
import json
import threading
import time

from .protocol import OcrError


class AuditLog:
    """A retained stream bypasses SDK stdout suppression, never SDK diagnostics."""

    def __init__(self, stream):
        self.stream = stream
        self.lock = threading.Lock()

    def __call__(self, value):
        # Reconstruct the whitelist instead of trusting callers with extra keys.
        event = {name: value[name] for name in (
            "request_id", "event", "status", "elapsed_ms",
        )}
        with self.lock:
            self.stream.write(json.dumps(event, separators=(",", ":")) + "\n")
            self.stream.flush()


class PageContext:
    def __init__(self, request_id, deadline_monotonic, audit):
        self.request_id = request_id
        self.deadline = deadline_monotonic
        self.audit = audit
        self.cancelled = threading.Event()
        self._failure = None
        self._callbacks = set()
        self._lock = threading.RLock()

    @property
    def failure(self):
        with self._lock:
            return self._failure

    def cancel(self, code):
        with self._lock:
            if self._failure is not None:
                return
            self._failure = code
            self.cancelled.set()
            callbacks = tuple(self._callbacks)
        for callback in callbacks:
            callback()

    def check(self):
        if time.monotonic() >= self.deadline:
            self.cancel("deadline_exceeded")
        if self.failure is not None:
            raise OcrError(self.failure, self.request_id)

    async def http_call(self, operation):
        """operation includes send AND complete bounded response validation."""
        self.check()
        start = time.monotonic()
        loop = asyncio.get_running_loop()

        async def dispatch():
            self.check()
            return await operation()

        task = asyncio.create_task(dispatch())

        def cancel_task():
            loop.call_soon_threadsafe(task.cancel)

        with self._lock:
            # Nothing has run yet: a cancelled page must not count a new attempt.
            if self._failure is not None:
                task.cancel()
                raise OcrError(self._failure, self.request_id)
            self._callbacks.add(cancel_task)
        try:
            self.audit({"request_id": self.request_id, "event": "start",
                        "status": "started", "elapsed_ms": 0})
        except Exception:
            # Dispatch is only scheduled, not run: a missing audit cannot send HTTP.
            self.cancel("pipeline_failed")
            task.cancel()
            with self._lock:
                self._callbacks.discard(cancel_task)
            raise OcrError("pipeline_failed", self.request_id) from None
        status = "upstream_unavailable"
        try:
            result = await asyncio.wait_for(task, timeout=max(0, self.deadline - time.monotonic()))
            self.check()
            status = "ok"
            return result
        except asyncio.TimeoutError:
            self.cancel("deadline_exceeded")
            status = self.failure
            raise OcrError(status, self.request_id) from None
        except asyncio.CancelledError:
            self.cancel("cancelled")
            status = self.failure
            raise OcrError(status, self.request_id) from None
        except OcrError as failure:
            self.cancel(failure.code)
            status = self.failure
            raise OcrError(status, self.request_id) from None
        except Exception:
            self.cancel("upstream_unavailable")
            status = self.failure
            raise OcrError(status, self.request_id) from None
        finally:
            with self._lock:
                self._callbacks.discard(cancel_task)
            try:
                self.audit({"request_id": self.request_id, "event": "finish",
                            "status": status,
                            "elapsed_ms": round((time.monotonic() - start) * 1000)})
            except Exception:
                self.cancel("pipeline_failed")
                raise OcrError(self.failure, self.request_id) from None
