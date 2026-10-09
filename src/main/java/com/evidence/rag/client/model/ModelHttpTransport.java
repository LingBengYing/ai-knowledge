package com.evidence.rag.client.model;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.model.TextModels.Failure;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared bounded model HTTP transport; protocol payloads and revisions belong to each Adapter. */
final class ModelHttpTransport implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ModelHttpTransport.class);
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(1_048_576)
                          .maxNumberLength(128)
                          .build())
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Duration deadline;
  private final int maxResponseBytes;
  private final int maxRequestBytes;
  private final HttpClient client;
  private volatile boolean closed;

  ModelHttpTransport(Duration deadline, int maxResponseBytes, int maxRequestBytes) {
    this.deadline = deadline;
    this.maxResponseBytes = maxResponseBytes;
    this.maxRequestBytes = maxRequestBytes;
    client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(deadline)
            .build();
  }

  @Override
  public void close() {
    closed = true;
    client.shutdownNow();
  }

  static String encodeJson(Map<String, ?> payload) {
    return JSON.writeValueAsString(payload);
  }

  JsonNode post(Endpoint endpoint, String path, Map<String, Object> payload) {
    return post(
        endpoint, path, () -> JSON.writeValueAsBytes(payload), "application/json", false, null);
  }

  /**
   * Agent-only opt-in: bounded error JSON is decoded by its strict protocol Adapter, never logged.
   */
  JsonNode postWithSafeErrorDecoder(
      Endpoint endpoint,
      String path,
      Map<String, Object> payload,
      Function<JsonNode, Failure> errorDecoder) {
    return post(
        endpoint,
        path,
        () -> JSON.writeValueAsBytes(payload),
        "application/json",
        false,
        java.util.Objects.requireNonNull(errorDecoder));
  }

  JsonNode post(Endpoint endpoint, String path, byte[] payload, String contentType) {
    return post(endpoint, path, () -> payload, contentType, false, null);
  }

  JsonNode postGoogleEmbedding(Endpoint endpoint, Map<String, Object> payload) {
    return post(
        endpoint,
        "v1beta/models/" + endpoint.model() + ":embedContent",
        () -> JSON.writeValueAsBytes(payload),
        "application/json",
        true,
        null);
  }

  JsonNode postGoogleSound(Endpoint endpoint, Map<String, Object> payload) {
    return post(
        endpoint,
        "v1beta/interactions",
        () -> JSON.writeValueAsBytes(payload),
        "application/json",
        true,
        null);
  }

  private JsonNode post(
      Endpoint endpoint,
      String path,
      Supplier<byte[]> payload,
      String contentType,
      boolean googleEmbedding,
      Function<JsonNode, Failure> errorDecoder) {
    if (closed) {
      throw new Failure("model_closed");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new Failure("model_interrupted");
    }
    long started = System.nanoTime();
    var body = new BoundedBody(maxResponseBytes);
    CompletableFuture<HttpResponse<byte[]>> exchange = null;
    String attemptId = null;
    var status = new java.util.concurrent.atomic.AtomicInteger(-1);
    boolean transportOk = false;
    try {
      byte[] requestBytes = payload.get();
      if (requestBytes == null || requestBytes.length > maxRequestBytes) {
        throw invalidInput();
      }
      String base = endpoint.baseUrl().toString();
      var request =
          HttpRequest.newBuilder(URI.create(base + (base.endsWith("/") ? "" : "/") + path))
              .timeout(deadline)
              .header("Content-Type", contentType)
              .header("Accept", "application/json")
              .header(
                  googleEmbedding ? "x-goog-api-key" : "Authorization",
                  googleEmbedding ? endpoint.apiKey() : "Bearer " + endpoint.apiKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes))
              .build();
      // Reserve/count the attempt before dispatch. Never log URLs, credentials or model content.
      attemptId = UUID.randomUUID().toString();
      LOG.info(
          "model_http_started id={} provider={} operation={}",
          attemptId,
          provider(endpoint.baseUrl().getHost()),
          operation(path));
      exchange =
          client.sendAsync(
              request,
              info -> {
                status.set(info.statusCode());
                if (info.statusCode() != 200 && errorDecoder == null) {
                  body.fail(new Failure("model_http_failed", info.statusCode()));
                } else if (!info.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .split(";", 2)[0]
                    .trim()
                    .equalsIgnoreCase("application/json")) {
                  body.fail(invalidResponse());
                }
                return body;
              });
      long remaining = deadline.toNanos() - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      var response = exchange.get(remaining, TimeUnit.NANOSECONDS);
      var decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(response.body()))
              .toString();
      var parsed = parseObject(decoded);
      if (System.nanoTime() - started > deadline.toNanos()) {
        throw new TimeoutException();
      }
      if (response.statusCode() != 200) {
        throw errorDecoder.apply(parsed);
      }
      transportOk = true;
      return parsed;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Failure("model_interrupted");
    } catch (TimeoutException timeout) {
      throw new Failure("model_timeout");
    } catch (ExecutionException failed) {
      Throwable reason = failed.getCause();
      for (int depth = 0; reason != null && depth < 8; depth++, reason = reason.getCause()) {
        if (reason instanceof Failure safe) {
          throw safe;
        }
        if (reason instanceof HttpTimeoutException) {
          throw new Failure("model_timeout");
        }
      }
      throw new Failure("model_transport_failed");
    } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
      throw invalidResponse();
    } catch (Failure safe) {
      throw safe;
    } catch (RuntimeException failed) {
      throw new Failure("model_transport_failed");
    } finally {
      if (attemptId != null) {
        LOG.info(
            "model_http_finished id={} status={} transport_ok={} elapsed_ms={}",
            attemptId,
            status.get(),
            transportOk,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      }
      body.cancel();
      if (exchange != null && !exchange.isDone()) {
        exchange.cancel(true);
      }
    }
  }

  private static String provider(String host) {
    return switch (host == null ? "" : host) {
      case "api.siliconflow.cn", "api.siliconflow.com" -> "siliconflow";
      case "api.deepseek.com" -> "deepseek";
      default -> "compatible";
    };
  }

  private static String operation(String path) {
    return switch (path) {
      case "embeddings" -> "embedding";
      case "rerank" -> "rerank";
      case "chat/completions" -> "generation";
      case "audio/transcriptions" -> "transcription";
      default -> "model";
    };
  }

  static void validateEndpoint(Endpoint endpoint, boolean local) {
    if (endpoint == null
        || endpoint.baseUrl() == null
        || endpoint.model() == null
        || !endpoint.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")
        || endpoint.apiKey() == null
        || !endpoint.apiKey().matches("[\\x21-\\x7E]{1,4096}")) {
      throw new Failure("model_invalid_configuration");
    }
    URI uri = endpoint.baseUrl();
    String host = uri.getHost();
    String path = uri.getRawPath();
    boolean tls = "https".equalsIgnoreCase(uri.getScheme());
    boolean loopback = "127.0.0.1".equals(host) || "[::1]".equals(host);
    if (!uri.isAbsolute()
        || uri.isOpaque()
        || host == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || uri.toString().contains("%")
        || path == null
        || List.of(path.split("/", -1)).stream()
            .anyMatch(part -> part.equals(".") || part.equals(".."))
        || !(tls || local && loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
      throw new Failure("model_invalid_configuration");
    }
  }

  static JsonNode parseObject(String value) {
    try {
      var node = JSON.readTree(value);
      if (node == null || !node.isObject()) {
        throw invalidResponse();
      }
      return node;
    } catch (RuntimeException invalid) {
      throw invalidResponse();
    }
  }

  private static Failure invalidInput() {
    return new Failure("model_invalid_input");
  }

  private static Failure invalidResponse() {
    return new Failure("model_invalid_response");
  }

  /**
   * Cancels upstream before retaining bytes above the cap; deadline also cancels this subscription.
   */
  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int cap;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private BoundedBody(int cap) {
      this.cap = cap;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription next) {
      if (subscription != null || result.isDone()) {
        next.cancel();
        return;
      }
      subscription = next;
      next.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
      if (result.isDone()) {
        return;
      }
      for (var buffer : buffers) {
        int length = buffer.remaining();
        if (length > cap - bytes.size()) {
          fail(new Failure("model_response_too_large"));
          return;
        }
        byte[] chunk = new byte[length];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable ignored) {
      fail(new Failure("model_transport_failed"));
    }

    @Override
    public synchronized void onComplete() {
      result.complete(bytes.toByteArray());
    }

    private synchronized void fail(Failure failure) {
      result.completeExceptionally(failure);
      if (subscription != null) {
        subscription.cancel();
      }
    }

    private synchronized void cancel() {
      if (!result.isDone()) {
        result.completeExceptionally(new Failure("model_transport_failed"));
      }
      if (subscription != null) {
        subscription.cancel();
      }
    }
  }
}
