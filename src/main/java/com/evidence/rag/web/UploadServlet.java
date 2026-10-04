package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.LibraryWorkContext;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.DocumentReplacementService;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.web.converter.DocumentReplacementResponseMapper;
import com.evidence.rag.web.converter.TaskResponseMapper;
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
import java.util.Set;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.json.JsonMapper;

/** Nonblocking, bounded HTTP upload Adapter. It never parses files or runs models. */
public final class UploadServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final Set<String> VIDEO_CONTENT_TYPES =
      Set.of("video/mp4", "video/webm", "video/quicktime", "video/x-matroska");
  private final transient IngestionService authority;
  private final transient DocumentReplacementService replacements;
  private final int deadlineMs;
  private final JsonMapper json;
  private final transient ProblemHandler errors;
  private final Semaphore uploads = new Semaphore(2);

  public UploadServlet(
      IngestionService authority, int deadlineMs, JsonMapper json, ProblemHandler errors) {
    this(authority, deadlineMs, json, errors, null);
  }

  public UploadServlet(
      IngestionService authority,
      int deadlineMs,
      JsonMapper json,
      ProblemHandler errors,
      DocumentReplacementService replacements) {
    this.authority = authority;
    this.replacements = replacements;
    this.deadlineMs = deadlineMs;
    this.json = json;
    this.errors = errors;
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    receive(request, response, null);
  }

  /** Called only by the exact replacement MVC route; shares bounded original-file reception. */
  public void replacement(
      HttpServletRequest request, HttpServletResponse response, String documentId)
      throws IOException {
    ModelValues.identifier(documentId, 128);
    receive(request, response, documentId);
  }

  private void receive(
      HttpServletRequest request, HttpServletResponse response, String documentId)
      throws IOException {
    long deadline = System.nanoTime() + deadlineMs * 1_000_000L;
    try {
      if (!"POST".equals(request.getMethod())) {
        throw new ApplicationException(
            FailureKind.METHOD_NOT_ALLOWED, "method_not_allowed", "上传仅支持POST。");
      }
      Actor actor = AuthenticatedActor.require(request);
      var contentTypes = Collections.list(request.getHeaders("Content-Type"));
      if (contentTypes.size() != 1
          || !("application/octet-stream".equalsIgnoreCase(contentTypes.getFirst())
              || VIDEO_CONTENT_TYPES.contains(contentTypes.getFirst()))) {
        throw new ApplicationException(
            FailureKind.UNSUPPORTED_MEDIA,
            "unsupported_media_type",
            "上传需要application/octet-stream，或受支持的显式视频内容类型及原始文件内容。");
      }
      var parameters = request.getParameterMap();
      var expected =
          documentId == null ? Set.of("filename") : Set.of("filename", "base_revision_id");
      if (!parameters.keySet().equals(expected)
          || parameters.values().stream().anyMatch(values -> values.length != 1)) {
        throw new ApplicationException(
            FailureKind.INVALID_INPUT, "invalid_request", "请提供唯一文件名，不接受其他参数。");
      }
      String filename = parameters.get("filename")[0];
      String baseRevisionId =
          documentId == null ? null : parameters.get("base_revision_id")[0];
      String mime;
      if (documentId == null) {
        mime = authority.prepareUpload(filename, contentTypes.getFirst());
      } else {
        if (replacements == null) {
          throw ModelValues.notFound();
        }
        ModelValues.identifier(baseRevisionId, 128);
        mime =
            replacements.prepareUpload(
                actor, documentId, baseRevisionId, filename, contentTypes.getFirst());
      }
      if (request.getContentLengthLong() > authority.maximumUploadBytes()) {
        throw tooLarge();
      }
      if (!uploads.tryAcquire()) {
        throw new ApplicationException(
            FailureKind.CAPACITY_EXCEEDED, "upload_busy", "已有文件正在上传，请稍后重试。");
      }
      boolean handedOff = false;
      try {
        AsyncContext async = request.startAsync();
        async.setTimeout(deadlineMs);
        var receiver =
            new Receiver(
                request, response, async, actor, filename, mime, deadline,
                documentId, baseRevisionId);
        async.addListener(receiver);
        handedOff = true;
        request.getInputStream().setReadListener(receiver);
      } finally {
        if (!handedOff) {
          uploads.release();
        }
      }
    } catch (ApplicationException problem) {
      writeProblem(
          request,
          response,
          problem.code().equals("unsupported_document")
              ? new ApplicationException(
                  FailureKind.INVALID_INPUT, "unsupported_document", "文件名、格式或内容不受支持。")
              : problem);
    }
  }

  private final class Receiver implements ReadListener, AsyncListener {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final AsyncContext async;
    private final Actor actor;
    private final LibraryOperationGate.ReservedOperation operation;
    private final String filename;
    private final String mime;
    private final String documentId;
    private final String baseRevisionId;
    private final long deadline;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean done;

    Receiver(
        HttpServletRequest request,
        HttpServletResponse response,
        AsyncContext async,
        Actor actor,
        String filename,
        String mime,
        long deadline,
        String documentId,
        String baseRevisionId) {
      this.request = request;
      this.response = response;
      this.async = async;
      this.actor = actor;
      this.operation =
          LibraryWorkContext.currentGate().map(LibraryOperationGate::reserve).orElse(null);
      this.filename = filename;
      this.mime = mime;
      this.documentId = documentId;
      this.baseRevisionId = baseRevisionId;
      this.deadline = deadline;
    }

    @Override
    public synchronized void onDataAvailable() throws IOException {
      if (done) {
        return;
      }
      ServletInputStream input = request.getInputStream();
      byte[] buffer = new byte[8192];
      while (!done && input.isReady() && !input.isFinished()) {
        if (System.nanoTime() >= deadline) {
          fail(timeout());
          return;
        }
        int count = input.read(buffer);
        if (count < 0) {
          break;
        }
        if (bytes.size() > authority.maximumUploadBytes() - count) {
          fail(tooLarge());
          return;
        }
        bytes.write(buffer, 0, count);
      }
    }

    @Override
    public synchronized void onAllDataRead() throws IOException {
      if (done) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        fail(timeout());
        return;
      }
      try (var lease = operation == null ? null : operation.begin()) {
        if (documentId == null) {
          var task = authority.uploadDocument(actor, filename, mime, bytes.toByteArray());
          finish(202, TaskResponseMapper.from(task));
        } else {
          var value =
              replacements.upload(
                  actor, documentId, baseRevisionId, filename, mime, bytes.toByteArray());
          finish(202, DocumentReplacementResponseMapper.from(value));
        }
      } catch (ApplicationException problem) {
        fail(problem);
      } catch (RuntimeException error) {
        fail(
            new ApplicationException(
                FailureKind.UNAVAILABLE, "ingestion_unavailable", "文件摄取暂不可用。"));
      }
    }

    private void fail(ApplicationException problem) throws IOException {
      if (done) {
        return;
      }
      var result = errors.problem(problem, request);
      response.setContentType("application/problem+json");
      finish(result.getStatusCode().value(), result.getBody());
    }

    private void finish(int status, Object body) throws IOException {
      if (done) {
        return;
      }
      done = true;
      try {
        response.setStatus(status);
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
        uploads.release();
        async.complete();
      }
    }

    @Override
    public synchronized void onError(Throwable error) {
      try {
        fail(
            new ApplicationException(
                FailureKind.INVALID_REQUEST, "upload_interrupted", "文件上传中断，请检查资料列表后再试。"));
      } catch (IOException ignored) {
        /* Client disconnected; no content is persisted. */
      }
    }

    @Override
    public void onError(AsyncEvent event) {
      onError(event.getThrowable());
    }

    @Override
    public synchronized void onTimeout(AsyncEvent event) throws IOException {
      fail(timeout());
    }

    @Override
    public void onStartAsync(AsyncEvent event) {}

    @Override
    public synchronized void onComplete(AsyncEvent event) {
      if (!done) {
        done = true;
        bytes.reset();
        if (operation != null) {
          operation.close();
        }
        uploads.release();
      }
    }
  }

  private void writeProblem(
      HttpServletRequest request, HttpServletResponse response, ApplicationException problem)
      throws IOException {
    var result = errors.problem(problem, request);
    response.setStatus(HttpProblemMapper.status(problem));
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.getOutputStream().write(json.writeValueAsBytes(result.getBody()));
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "upload_too_large", "文件不得超过20MiB。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "upload_timeout", "文件上传超时，未创建解析任务。");
  }
}
