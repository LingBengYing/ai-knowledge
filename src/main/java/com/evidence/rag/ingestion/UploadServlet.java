package com.evidence.rag.ingestion;

import com.evidence.rag.corpus.TextParser;
import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import com.evidence.rag.web.ProblemHandler;
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
import java.util.Locale;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.json.JsonMapper;

/** Nonblocking, bounded HTTP upload Adapter. It never parses files or runs models. */
public final class UploadServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private final transient ManagementModule authority;
  private final int deadlineMs;
  private final JsonMapper json;
  private final transient ProblemHandler errors;
  private final Semaphore uploads = new Semaphore(2);

  public UploadServlet(
      ManagementModule authority, int deadlineMs, JsonMapper json, ProblemHandler errors) {
    this.authority = authority;
    this.deadlineMs = deadlineMs;
    this.json = json;
    this.errors = errors;
  }

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    long deadline = System.nanoTime() + deadlineMs * 1_000_000L;
    try {
      if (!"POST".equals(request.getMethod()))
        throw new Problem(405, "method_not_allowed", "上传仅支持POST。");
      Actor actor = IngestionController.actor(request);
      var contentTypes = Collections.list(request.getHeaders("Content-Type"));
      if (contentTypes.size() != 1
          || !"application/octet-stream".equalsIgnoreCase(contentTypes.getFirst()))
        throw new Problem(415, "unsupported_media_type", "上传需要application/octet-stream原始文件内容。");
      var parameters = request.getParameterMap();
      if (parameters.size() != 1
          || !parameters.containsKey("filename")
          || parameters.get("filename").length != 1)
        throw new Problem(422, "invalid_request", "请提供唯一文件名，不接受其他参数。");
      String filename = parameters.get("filename")[0];
      String lower = filename.toLowerCase(Locale.ROOT);
      String mime =
          lower.endsWith(".pdf")
              ? "application/pdf"
              : lower.endsWith(".md") ? "text/markdown" : "text/plain";
      // Validate names before reading. Full envelope and content are checked again by authority.
      TextParser.validateEnvelope(
          filename,
          mime,
          lower.endsWith(".pdf") ? new byte[] {'%', 'P', 'D', 'F'} : new byte[] {'x'});
      if (request.getContentLengthLong() > TextParser.MAX_BYTES) throw tooLarge();
      if (!uploads.tryAcquire()) throw new Problem(429, "upload_busy", "已有文件正在上传，请稍后重试。");
      boolean handedOff = false;
      try {
        AsyncContext async = request.startAsync();
        async.setTimeout(deadlineMs);
        var receiver = new Receiver(request, response, async, actor, filename, mime, deadline);
        async.addListener(receiver);
        handedOff = true;
        request.getInputStream().setReadListener(receiver);
      } finally {
        if (!handedOff) uploads.release();
      }
    } catch (TextParser.Failure invalid) {
      writeProblem(request, response, new Problem(422, "unsupported_document", "文件名、格式或内容不受支持。"));
    } catch (Problem problem) {
      writeProblem(request, response, problem);
    }
  }

  private final class Receiver implements ReadListener, AsyncListener {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final AsyncContext async;
    private final Actor actor;
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
      this.filename = filename;
      this.mime = mime;
      this.deadline = deadline;
    }

    @Override
    public synchronized void onDataAvailable() throws IOException {
      if (done) return;
      ServletInputStream input = request.getInputStream();
      byte[] buffer = new byte[8192];
      while (!done && input.isReady() && !input.isFinished()) {
        if (System.nanoTime() >= deadline) {
          fail(timeout());
          return;
        }
        int count = input.read(buffer);
        if (count < 0) break;
        if (bytes.size() > TextParser.MAX_BYTES - count) {
          fail(tooLarge());
          return;
        }
        bytes.write(buffer, 0, count);
      }
    }

    @Override
    public synchronized void onAllDataRead() throws IOException {
      if (done) return;
      if (System.nanoTime() >= deadline) {
        fail(timeout());
        return;
      }
      try {
        var task = authority.uploadDocument(actor, filename, mime, bytes.toByteArray());
        finish(202, task);
      } catch (TextParser.Failure invalid) {
        fail(new Problem(422, "unsupported_document", "文件格式或内容不受支持。"));
      } catch (Problem problem) {
        fail(problem);
      } catch (RuntimeException error) {
        fail(new Problem(503, "ingestion_unavailable", "文件摄取暂不可用。"));
      }
    }

    private void fail(Problem problem) throws IOException {
      if (done) return;
      var result = errors.problem(problem, request);
      response.setContentType("application/problem+json");
      finish(result.getStatusCode().value(), result.getBody());
    }

    private void finish(int status, Object body) throws IOException {
      if (done) return;
      done = true;
      try {
        response.setStatus(status);
        if (response.getContentType() == null) response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(json.writeValueAsBytes(body));
      } finally {
        uploads.release();
        async.complete();
      }
    }

    @Override
    public synchronized void onError(Throwable error) {
      try {
        fail(new Problem(400, "upload_interrupted", "文件上传中断，请检查资料列表后再试。"));
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
        uploads.release();
      }
    }
  }

  private void writeProblem(
      HttpServletRequest request, HttpServletResponse response, Problem problem)
      throws IOException {
    var result = errors.problem(problem, request);
    response.setStatus(problem.status());
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.getOutputStream().write(json.writeValueAsBytes(result.getBody()));
  }

  private static Problem tooLarge() {
    return new Problem(413, "upload_too_large", "文件不得超过20MiB。");
  }

  private static Problem timeout() {
    return new Problem(408, "upload_timeout", "文件上传超时，未创建解析任务。");
  }
}
