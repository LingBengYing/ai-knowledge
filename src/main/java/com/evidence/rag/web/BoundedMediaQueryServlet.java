package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.LibraryWorkContext;
import com.evidence.rag.security.web.AuthenticatedActor;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.json.JsonMapper;

/** Bounded ephemeral media transport shared by explicit request handlers. */
public final class BoundedMediaQueryServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private final transient Handler handler;
  private final int maxRequestBytes;
  private final int receiveTimeoutMs;
  private final int processingTimeoutMs;
  private final Semaphore admission;
  private final JsonMapper json;
  private final transient ProblemHandler errors;
  private final transient ExecutorService waiters =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("media-query-http-", 0).factory());

  @FunctionalInterface
  public interface Handler {
    Object handle(Actor actor, byte[] body);
  }

  public BoundedMediaQueryServlet(
      Handler handler,
      int maxRequestBytes,
      int receiveTimeoutMs,
      int processingTimeoutMs,
      int maxConcurrent,
      JsonMapper json,
      ProblemHandler errors) {
    if (maxRequestBytes < 1
        || maxRequestBytes > 28 * 1024 * 1024
        || receiveTimeoutMs < 10
        || receiveTimeoutMs > 60000
        || processingTimeoutMs < 10
        || processingTimeoutMs > 600000
        || maxConcurrent < 1
        || maxConcurrent > 8) {
      throw new IllegalArgumentException("Invalid media query transport limits");
    }
    this.handler = Objects.requireNonNull(handler);
    this.maxRequestBytes = maxRequestBytes;
    this.receiveTimeoutMs = receiveTimeoutMs;
    this.processingTimeoutMs = processingTimeoutMs;
    this.admission = new Semaphore(maxConcurrent);
    this.json = Objects.requireNonNull(json);
    this.errors = Objects.requireNonNull(errors);
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      if (!"POST".equals(request.getMethod())) {
        throw new ApplicationException(
            FailureKind.METHOD_NOT_ALLOWED, "method_not_allowed", "媒体输入仅支持POST。");
      }
      Actor actor = AuthenticatedActor.require(request);
      if (request.getQueryString() != null) {
        throw invalid();
      }
      var contentTypes = Collections.list(request.getHeaders("Content-Type"));
      if (contentTypes.size() != 1
          || !("application/json".equalsIgnoreCase(contentTypes.getFirst())
              || "application/json;charset=utf-8"
                  .equalsIgnoreCase(contentTypes.getFirst().replace(" ", "")))) {
        throw new ApplicationException(
            FailureKind.UNSUPPORTED_MEDIA, "unsupported_media_type", "媒体输入需要JSON内容。");
      }
      if (request.getContentLengthLong() > maxRequestBytes) {
        throw tooLarge();
      }
      if (!admission.tryAcquire()) {
        throw new ApplicationException(
            FailureKind.CAPACITY_EXCEEDED, "query_attachment_busy", "媒体输入正在处理中，请稍后重试。");
      }
      Receiver receiver = null;
      try {
        var async = request.startAsync();
        async.setTimeout(receiveTimeoutMs);
        receiver = new Receiver(request, response, async, actor);
        async.addListener(receiver);
        request.getInputStream().setReadListener(receiver);
      } catch (IOException | RuntimeException failure) {
        if (receiver == null) {
          admission.release();
          writeProblem(request, response, unavailable());
        } else {
          receiver.fail(unavailable());
        }
      }
    } catch (ApplicationException problem) {
      writeProblem(request, response, problem);
    }
  }

  @Override
  public void destroy() {
    waiters.shutdownNow();
  }

  private final class Receiver implements ReadListener, AsyncListener {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final AsyncContext async;
    private final Actor actor;
    private final LibraryOperationGate.ReservedOperation operation;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final long receiveDeadline = System.nanoTime() + receiveTimeoutMs * 1_000_000L;
    private boolean done;
    private FutureTask<Void> waiter;
    private volatile Thread waitingThread;

    Receiver(
        HttpServletRequest request, HttpServletResponse response, AsyncContext async, Actor actor) {
      this.request = request;
      this.response = response;
      this.async = async;
      this.actor = actor;
      this.operation =
          LibraryWorkContext.currentGate().map(LibraryOperationGate::reserve).orElse(null);
    }

    @Override
    public synchronized void onDataAvailable() throws IOException {
      if (done || waiter != null) {
        return;
      }
      ServletInputStream input = request.getInputStream();
      byte[] buffer = new byte[8192];
      while (!done && input.isReady() && !input.isFinished()) {
        if (System.nanoTime() >= receiveDeadline) {
          fail(timeout());
          return;
        }
        int count = input.read(buffer);
        if (count < 0) {
          break;
        }
        if (bytes.size() > maxRequestBytes - count) {
          fail(tooLarge());
          return;
        }
        bytes.write(buffer, 0, count);
      }
    }

    @Override
    public void onAllDataRead() throws IOException {
      FutureTask<Void> task;
      synchronized (this) {
        if (done || waiter != null) {
          return;
        }
        if (System.nanoTime() >= receiveDeadline) {
          fail(timeout());
          return;
        }
        byte[] body = bytes.toByteArray();
        bytes.reset();
        async.setTimeout(processingTimeoutMs + 1000L);
        task =
            new FutureTask<>(
                () -> {
                  process(body);
                  return null;
                });
        waiter = task;
      }
      // Parsing and synchronous service waiting happen outside the receiver monitor.
      try {
        waiters.execute(task);
      } catch (RuntimeException rejected) {
        fail(unavailable());
      }
    }

    private void process(byte[] body) {
      waitingThread = Thread.currentThread();
      try (var lease = operation == null ? null : operation.begin()) {
        Object result = handler.handle(actor, body);
        finish(200, result, false);
      } catch (ApplicationException problem) {
        safeFail(problem);
      } catch (RuntimeException failure) {
        safeFail(unavailable());
      } catch (IOException disconnected) {
        safeFail(interrupted());
      }
    }

    private synchronized void fail(ApplicationException problem) throws IOException {
      if (done) {
        return;
      }
      var result = errors.problem(problem, request);
      response.setContentType("application/problem+json");
      finish(result.getStatusCode().value(), result.getBody(), true);
    }

    private synchronized void finish(int status, Object body, boolean cancel) throws IOException {
      if (done) {
        return;
      }
      done = true;
      if (cancel && waiter != null && waitingThread != Thread.currentThread()) {
        waiter.cancel(true);
      }
      try {
        response.setStatus(status);
        response.setHeader("Cache-Control", "private, no-store");
        if (response.getContentType() == null) {
          response.setContentType("application/json");
        }
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(json.writeValueAsBytes(body));
      } finally {
        bytes.reset();
        if (operation != null) {
          operation.close();
        }
        admission.release();
        async.complete();
      }
    }

    private void safeFail(ApplicationException problem) {
      try {
        fail(problem);
      } catch (IOException disconnected) {
        // The terminal path already released admission; no query originals are persisted.
      }
    }

    @Override
    public void onError(Throwable error) {
      safeFail(interrupted());
    }

    @Override
    public void onError(AsyncEvent event) {
      onError(event.getThrowable());
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      safeFail(timeout());
    }

    @Override
    public void onStartAsync(AsyncEvent event) {}

    @Override
    public synchronized void onComplete(AsyncEvent event) {
      if (!done) {
        done = true;
        if (waiter != null) {
          waiter.cancel(true);
        }
        bytes.reset();
        if (operation != null) {
          operation.close();
        }
        admission.release();
      }
    }
  }

  private void writeProblem(
      HttpServletRequest request, HttpServletResponse response, ApplicationException problem)
      throws IOException {
    var result = errors.problem(problem, request);
    response.setStatus(result.getStatusCode().value());
    response.setHeader("Cache-Control", "private, no-store");
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.getOutputStream().write(json.writeValueAsBytes(result.getBody()));
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "媒体输入请求无效。");
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "query_request_too_large", "媒体输入超过接收限额。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "query_attachment_timeout", "媒体输入处理超时。");
  }

  private static ApplicationException interrupted() {
    return new ApplicationException(
        FailureKind.INVALID_REQUEST, "query_attachment_interrupted", "媒体输入已中断。");
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "query_attachment_unavailable", "媒体输入暂不可用。");
  }
}
