package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.model.domain.TextModelRole;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit one-request probes over fixed synthetic material, never library content. */
public final class TextModelConnectionProbe {
  public static final URI DEEPSEEK_BASE_URL = URI.create("https://api.deepseek.com");
  private static final String QUESTION = "合成资料中的核验词是什么？";
  private static final String RELEVANT = "合成资料中的核验词是青松。";
  private static final String OTHER = "这条合成资料描述一张空白纸。";
  private final URI provider;
  private final URI deepseekProvider;
  private final Duration deadline;
  private final int maxResponseBytes;
  private final boolean loopback;
  private final Projection projection;

  public record Projection(URI endpoint, String token, String database, String collection) {
    public Projection {
      if (endpoint == null
          || token == null
          || !token.matches("[\\x21-\\x7E]{1,4096}")
          || database == null
          || !database.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
          || collection == null
          || !collection.matches("java_[A-Za-z0-9_]{1,122}")) {
        throw new IllegalArgumentException("Invalid projection probe configuration");
      }
    }

    @Override
    public String toString() {
      return "Projection[redacted]";
    }
  }

  public TextModelConnectionProbe(
      URI providerBaseUrl,
      Duration deadline,
      int maxResponseBytes,
      boolean allowLoopbackHttp,
      Projection projection) {
    this(
        providerBaseUrl,
        deadline,
        maxResponseBytes,
        allowLoopbackHttp,
        projection,
        DEEPSEEK_BASE_URL);
  }

  public TextModelConnectionProbe(
      URI providerBaseUrl,
      Duration deadline,
      int maxResponseBytes,
      boolean allowLoopbackHttp,
      Projection projection,
      URI deepseekProviderBaseUrl) {
    validateUri(providerBaseUrl, allowLoopbackHttp, false);
    validateUri(deepseekProviderBaseUrl, allowLoopbackHttp, false);
    boolean localDeepseek =
        allowLoopbackHttp
            && "http".equalsIgnoreCase(deepseekProviderBaseUrl.getScheme())
            && ("127.0.0.1".equals(deepseekProviderBaseUrl.getHost())
                || "[::1]".equals(deepseekProviderBaseUrl.getHost()))
            && List.of("", "/", "/v1", "/v1/").contains(deepseekProviderBaseUrl.getRawPath());
    if (!TextIndexAnchor.isDeepSeekEndpoint(deepseekProviderBaseUrl.toASCIIString())
        && !localDeepseek) {
      throw new IllegalArgumentException("Invalid trusted DeepSeek endpoint");
    }
    if (deadline == null
        || deadline.toMillis() < 1
        || deadline.toMillis() > 60000
        || maxResponseBytes < 1024
        || maxResponseBytes > 4194304) {
      throw new IllegalArgumentException("Invalid model probe limits");
    }
    if (projection != null) {
      validateUri(projection.endpoint(), allowLoopbackHttp, true);
    }
    this.provider = providerBaseUrl;
    this.deepseekProvider = deepseekProviderBaseUrl;
    this.deadline = deadline;
    this.maxResponseBytes = maxResponseBytes;
    this.loopback = allowLoopbackHttp;
    this.projection = projection;
  }

  public boolean projectionConfigured() {
    return projection != null;
  }

  /** Null means passed; every failure is a fixed non-secret code. */
  public String test(TextModelConfiguration configuration, TextModelRole role) {
    if (role == TextModelRole.PROJECTION) {
      return projectionTest();
    }
    if (configuration == null || role == null) {
      return "model_invalid_configuration";
    }
    try (var models =
        new OpenAiCompatibleModels(
            new OpenAiCompatibleModels.Configuration(
                endpoint(
                    configuration.embedding().provider(),
                    configuration.embedding().model(),
                    configuration.embedding().apiKey()),
                endpoint(
                    configuration.rerank().provider(),
                    configuration.rerank().model(),
                    configuration.rerank().apiKey()),
                endpoint(
                    configuration.generation().provider(),
                    configuration.generation().model(),
                    configuration.generation().apiKey()),
                configuration.embedding().dimensions(),
                deadline,
                maxResponseBytes,
                loopback))) {
      switch (role) {
        case EMBEDDING -> models.embed(List.of(RELEVANT, OTHER));
        case RERANK -> models.rerank(QUESTION, List.of(RELEVANT, OTHER));
        case GENERATION -> {
          var extraction =
              models.extract(QUESTION, List.of(new TextModels.Evidence("synthetic-1", RELEVANT)));
          if (extraction.refused()
              || extraction.quotes().stream().noneMatch(quote -> quote.quote().contains("青松"))) {
            return "model_test_refused";
          }
        }
        default -> throw new IllegalStateException();
      }
      return null;
    } catch (TextModels.Failure failed) {
      return code(failed, "model");
    } catch (RuntimeException failed) {
      return "model_invalid_configuration";
    }
  }

  private OpenAiCompatibleModels.Endpoint endpoint(String name, String model, String key) {
    URI selected =
        switch (name) {
          case "siliconflow" -> provider;
          case "deepseek" -> deepseekProvider;
          default -> throw new IllegalArgumentException("Invalid model provider");
        };
    return new OpenAiCompatibleModels.Endpoint(selected, model, key);
  }

  private String projectionTest() {
    if (projection == null) {
      return "projection_configuration_required";
    }
    if (Thread.currentThread().isInterrupted()) {
      return "projection_interrupted";
    }
    long started = System.nanoTime();
    var body = new ProbeBody(maxResponseBytes);
    CompletableFuture<HttpResponse<byte[]>> exchange = null;
    var client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(deadline)
            .build();
    try {
      var request =
          HttpRequest.newBuilder(projection.endpoint().resolve("/v2/vectordb/collections/has"))
              .timeout(deadline)
              .header("Authorization", "Bearer " + projection.token())
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      ModelHttpTransport.encodeJson(
                          Map.of(
                              "dbName",
                              projection.database(),
                              "collectionName",
                              projection.collection())),
                      StandardCharsets.UTF_8))
              .build();
      exchange =
          client.sendAsync(
              request,
              info -> {
                if (info.statusCode() != 200) {
                  body.fail(new TextModels.Failure("model_http_failed", info.statusCode()));
                } else if (!info.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .split(";", 2)[0]
                    .strip()
                    .equalsIgnoreCase("application/json")) {
                  body.fail(new TextModels.Failure("model_invalid_response"));
                }
                return body;
              });
      long remaining = deadline.toNanos() - (System.nanoTime() - started);
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      var response = exchange.get(remaining, TimeUnit.NANOSECONDS);
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(response.body()))
              .toString();
      var parsed = ModelHttpTransport.parseObject(json);
      if (!parsed.path("code").isIntegralNumber()
          || !parsed.path("code").canConvertToInt()
          || parsed.path("code").intValue() != 0
          || !parsed.path("data").path("has").isBoolean()) {
        return "projection_invalid_response";
      }
      return System.nanoTime() - started > deadline.toNanos() ? "projection_timeout" : null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "projection_interrupted";
    } catch (TimeoutException timeout) {
      return "projection_timeout";
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
        if (cause instanceof TextModels.Failure safe) {
          return code(safe, "projection");
        }
        if (cause instanceof HttpTimeoutException) {
          return "projection_timeout";
        }
      }
      return "projection_unreachable";
    } catch (java.nio.charset.CharacterCodingException invalid) {
      return "projection_invalid_response";
    } catch (TextModels.Failure invalid) {
      return code(invalid, "projection");
    } catch (RuntimeException failed) {
      return "projection_unreachable";
    } finally {
      body.cancel();
      if (exchange != null && !exchange.isDone()) {
        exchange.cancel(true);
      }
      client.shutdownNow();
    }
  }

  private static String code(TextModels.Failure failed, String prefix) {
    if (Integer.valueOf(401).equals(failed.httpStatus())
        || Integer.valueOf(403).equals(failed.httpStatus())) {
      return prefix + "_authentication_failed";
    }
    if (Integer.valueOf(429).equals(failed.httpStatus())) {
      return prefix + "_rate_limited";
    }
    return switch (failed.code()) {
      case "model_timeout" -> prefix + "_timeout";
      case "model_interrupted", "model_closed" -> prefix + "_interrupted";
      case "model_invalid_response", "model_response_too_large" -> prefix + "_invalid_response";
      case "model_invalid_configuration", "model_invalid_input" ->
          prefix + "_invalid_configuration";
      default -> prefix + "_unreachable";
    };
  }

  private static void validateUri(URI uri, boolean local, boolean root) {
    String host = uri == null ? null : uri.getHost();
    String path = uri == null ? null : uri.getRawPath();
    if (uri == null
        || !uri.isAbsolute()
        || uri.isOpaque()
        || host == null
        || path == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || uri.toString().contains("%")
        || List.of(path.split("/", -1)).stream()
            .anyMatch(value -> value.equals(".") || value.equals(".."))
        || root && !path.isEmpty() && !path.equals("/")
        || !("https".equalsIgnoreCase(uri.getScheme())
            || local
                && "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "[::1]".equals(host)))) {
      throw new IllegalArgumentException("Invalid trusted probe endpoint");
    }
  }

  private static final class ProbeBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int cap;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private ProbeBody(int cap) {
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
        if (buffer.remaining() > cap - bytes.size()) {
          fail(new TextModels.Failure("model_response_too_large"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable ignored) {
      fail(new TextModels.Failure("model_transport_failed"));
    }

    @Override
    public synchronized void onComplete() {
      result.complete(bytes.toByteArray());
    }

    private synchronized void fail(TextModels.Failure failure) {
      result.completeExceptionally(failure);
      if (subscription != null) {
        subscription.cancel();
      }
    }

    private synchronized void cancel() {
      if (subscription != null) {
        subscription.cancel();
      }
      result.completeExceptionally(new TextModels.Failure("model_interrupted"));
    }
  }
}
