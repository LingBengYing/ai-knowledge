package com.evidence.rag.web;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.LibraryWorkContext;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.web.converter.SoundResponseMapper;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.json.JsonMapper;

/** Bounded original-sound upload; UTF8 filename header is decoded once, without form semantics. */
public final class SoundUploadServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final int MAX_BYTES = 20 * 1024 * 1024;
  private final transient SoundLibraryService authority;
  private final int deadlineMs = 30000;
  private final JsonMapper json;
  private final transient ProblemHandler errors;
  private final Semaphore uploads = new Semaphore(2);

  public SoundUploadServlet(SoundLibraryService authority, JsonMapper json, ProblemHandler errors) {
    this.authority = authority;
    this.json = json;
    this.errors = errors;
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
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
          || !"application/octet-stream".equalsIgnoreCase(contentTypes.getFirst())) {
        throw new ApplicationException(
            FailureKind.UNSUPPORTED_MEDIA,
            "unsupported_media_type",
            "声音上传需要application/octet-stream和原始文件内容。");
      }
      var filenames = Collections.list(request.getHeaders("X-Filename"));
      if (request.getQueryString() != null || filenames.size() != 1) {
        throw invalid();
      }
      String filename = filename(filenames.getFirst());
      String mime = "application/octet-stream";
      if (request.getContentLengthLong() > MAX_BYTES) {
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
        var receiver = new Receiver(request, response, async, actor, filename, mime, deadline);
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
        long deadline) {
      this.request = request;
      this.response = response;
      this.async = async;
      this.actor = actor;
      this.operation =
          LibraryWorkContext.currentGate().map(LibraryOperationGate::reserve).orElse(null);
      this.filename = filename;
      this.mime = mime;
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
        if (bytes.size() > MAX_BYTES - count) {
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
        var original = authority.upload(actor, filename, mime, bytes.toByteArray());
        finish(201, SoundResponseMapper.upload(original));
      } catch (ApplicationException problem) {
        fail(problem);
      } catch (RuntimeException error) {
        fail(new ApplicationException(FailureKind.UNAVAILABLE, "sound_unavailable", "声音资料暂不可用。"));
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
    response.setHeader("Cache-Control", "private, no-store");
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.getOutputStream().write(json.writeValueAsBytes(result.getBody()));
  }

  private static String filename(String encoded) {
    if (encoded == null || encoded.isEmpty() || encoded.length() > 4096) {
      throw invalid();
    }
    var bytes = new ByteArrayOutputStream();
    try {
      for (int i = 0; i < encoded.length(); i++) {
        char c = encoded.charAt(i);
        if (c == '%') {
          if (i + 2 >= encoded.length()) {
            throw invalid();
          }
          int high = Character.digit(encoded.charAt(++i), 16);
          int low = Character.digit(encoded.charAt(++i), 16);
          if (high < 0 || low < 0) {
            throw invalid();
          }
          bytes.write(high * 16 + low);
        } else {
          if (c < 32 || c > 126) {
            throw invalid();
          }
          bytes.write(c);
        }
      }
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException failure) {
      throw invalid();
    }
  }

  private static ApplicationException invalid() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT, "invalid_request", "请提供唯一有效的声音文件名，不接受其他参数。");
  }

  private static ApplicationException tooLarge() {
    return new ApplicationException(
        FailureKind.PAYLOAD_TOO_LARGE, "upload_too_large", "文件不得超过20MiB。");
  }

  private static ApplicationException timeout() {
    return new ApplicationException(FailureKind.TIMEOUT, "upload_timeout", "声音上传超时，请检查资料列表后再试。");
  }
}
