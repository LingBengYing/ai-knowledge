"""One page at a time over a filesystem-protected local Unix socket."""

import os
import select
import socket
import stat
import threading
import time
from pathlib import Path

from .execution import PageContext
from .protocol import OcrError, decode_request, encode_response, read_frame


class OcrServer:
    def __init__(self, socket_path, pipeline, audit):
        self.path = Path(socket_path)
        self.pipeline = pipeline
        self.audit = audit
        self._active = threading.Lock()
        self._stopped = threading.Event()
        self._context = None
        self._thread = None
        self._listener = None

    def start(self):
        if not self.path.is_absolute() or self.path.exists() or self.path.is_symlink():
            raise ValueError("unsafe socket path")
        parent = self.path.parent
        if not parent.exists():
            parent.mkdir(mode=0o700)
        info = parent.lstat()
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid()
                or stat.S_IMODE(info.st_mode) != 0o700):
            raise ValueError("socket parent must be owned and mode 0700")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            listener.bind(str(self.path))
            os.chmod(self.path, 0o600)
            self._identity = self.path.lstat().st_ino
            listener.listen(1)
            listener.settimeout(0.2)
        except BaseException:
            listener.close()
            raise
        self._listener = listener
        self._thread = threading.Thread(target=self._accept, daemon=True)
        self._thread.start()

    def _accept(self):
        while not self._stopped.is_set():
            try:
                connection, _ = self._listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            if not self._active.acquire(blocking=False):
                # Bounded handshake solely to obtain a validated correlation UUID.
                # No application queue and no second pipeline worker is created.
                with connection:
                    try:
                        page = decode_request(read_frame(connection, timeout=0.2))
                        connection.sendall(encode_response(page.request_id, error_code="busy"))
                    except (OcrError, OSError):
                        pass
                continue
            threading.Thread(target=self._handle, args=(connection,), daemon=True).start()

    def _handle(self, connection):
        identifier = None
        finished = threading.Event()
        response_lock = threading.Lock()
        responded = False

        def respond(text=None, code=None):
            nonlocal responded
            with response_lock:
                if responded or identifier is None:
                    return
                responded = True
                try:
                    response = encode_response(identifier, text=text, error_code=code)
                except OcrError as failure:
                    response = encode_response(identifier, error_code=failure.code)
                try:
                    connection.settimeout(1)
                    connection.sendall(response)
                except OSError:
                    pass

        try:
            page = decode_request(read_frame(connection))
            identifier = page.request_id
            context = PageContext(identifier, page.deadline_monotonic, self.audit)
            self._context = context

            def watch():
                while not finished.is_set():
                    remaining = context.deadline - time.monotonic()
                    if remaining <= 0:
                        context.cancel("deadline_exceeded")
                        respond(code="deadline_exceeded")
                        return
                    try:
                        readable, _, _ = select.select([connection], [], [], min(0.1, remaining))
                        if readable:
                            # Protocol has exactly one request: EOF or extra data cancels.
                            connection.recv(1)
                            context.cancel("cancelled")
                            return
                    except (OSError, ValueError):
                        context.cancel("cancelled")
                        return

            watcher = threading.Thread(target=watch, daemon=True)
            watcher.start()
            try:
                context.check()
                text = self.pipeline(page.image, context)
                context.check()
                respond(text=text)
            finally:
                finished.set()
                watcher.join(timeout=0.2)
        except OcrError as failure:
            if identifier is None:
                identifier = failure.request_id
            respond(code=failure.code)
        except Exception:
            respond(code="pipeline_failed")
        finally:
            finished.set()
            connection.close()
            self._context = None
            self._active.release()

    def close(self):
        self._stopped.set()
        if self._context is not None:
            self._context.cancel("cancelled")
        if self._listener is not None:
            self._listener.close()
        if self._thread is not None:
            self._thread.join(timeout=1)
        if self.path.exists() and self.path.lstat().st_ino == getattr(self, "_identity", None):
            self.path.unlink()
