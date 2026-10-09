package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class IndexWorkerModelHttpLogTest {
  @Test
  void actualChildForwardsEveryModelAttemptWithoutProtocolOrSecretLeakage() throws Exception {
    try (var events = new Events();
        var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      assertEquals(17, indexer.index(server.claim(17)).verified().segmentCount());
      long requests =
          server.requests.stream().filter(request -> request.path().equals("/embeddings")).count();
      assertEquals(2, requests);
      assertEquals(
          requests,
          events.lines.stream().filter(line -> line.startsWith("model_http_started ")).count());
      assertEquals(
          requests,
          events.lines.stream().filter(line -> line.startsWith("model_http_finished ")).count());
      assertTrue(
          events.lines.stream()
              .filter(line -> line.startsWith("model_http_finished "))
              .allMatch(line -> line.contains("status=200 transport_ok=true")));
      assertFalse(String.join("\n", events.lines).contains("synthetic"));
      assertFalse(String.join("\n", events.lines).contains("credential"));
      assertFalse(String.join("\n", events.lines).contains("Bearer"));
    }
  }

  @Test
  void actualHttpFailureIsCountedOnceAndRetainsStatusWithoutProviderBody() throws Exception {
    var received = new AtomicInteger();
    var models = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    models.createContext(
        "/embeddings",
        exchange -> {
          received.incrementAndGet();
          byte[] body = "{\"secret\":\"private-response\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(503, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    models.start();
    try (var events = new Events();
        var server =
            new IndexingTestServer(
                2,
                4 * 1024 * 1024,
                URI.create("http://127.0.0.1:" + models.getAddress().getPort()));
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15))) {
      assertEquals(
          "indexing_failed",
          assertThrows(ProcessTextIndexer.Failure.class, () -> indexer.index(server.claim(1)))
              .code());
      assertEquals(1, received.get());
      assertEquals(1, events.count("model_http_started "));
      assertEquals(1, events.count("model_http_finished "));
      assertTrue(
          events.lines.stream().anyMatch(line -> line.contains("status=503 transport_ok=false")));
      assertTrue(
          events.lines.contains("index_model_audit_closed started=1 finished=1 complete=true"));
      assertFalse(String.join("\n", events.lines).contains("private-response"));
    } finally {
      models.stop(0);
    }
  }

  @Test
  void cancellationDrainsStartedAttemptAndDoesNotFabricateFinishedResponse() throws Exception {
    try (var events = new Events();
        var server = new IndexingTestServer()) {
      server.failureMode = "block-embedding";
      try (var indexer =
          new ProcessTextIndexer(
              server.settings().models(), server.settings().projection(), Duration.ofSeconds(15))) {
        var result =
            new FutureTask<>(
                () ->
                    assertThrows(
                            ProcessTextIndexer.Failure.class, () -> indexer.index(server.claim(1)))
                        .code());
        Thread.ofVirtual().start(result);
        assertTrue(server.embeddingStarted.await(5, TimeUnit.SECONDS));
        indexer.close();
        assertEquals("indexing_closed", result.get(5, TimeUnit.SECONDS));
        assertEquals(1, events.count("model_http_started "));
        assertEquals(0, events.count("model_http_finished "));
        assertTrue(
            events.lines.contains("index_model_audit_closed started=1 finished=0 complete=false"));
      } finally {
        server.releaseEmbedding.countDown();
      }
    }
  }

  @Test
  void arbitraryOversizedAndMalformedStderrCannotLeakOrDeadlockAndDuplicatesAreIncomplete()
      throws Exception {
    try (var events = new Events();
        var server = new IndexingTestServer();
        var indexer =
            new ProcessTextIndexer(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofSeconds(15),
                NoisyFixture.class.getName(),
                List.of())) {
      assertEquals(1, indexer.index(server.claim(1)).verified().segmentCount());
      assertEquals(1, events.count("model_http_started "));
      assertEquals(1, events.count("model_http_finished "));
      assertTrue(
          events.lines.contains("index_model_audit_closed started=1 finished=1 complete=false"));
      assertFalse(String.join("\n", events.lines).contains("private"));
      assertFalse(String.join("\n", events.lines).contains("Bearer"));
      assertTrue(server.requests.isEmpty());
    }
  }

  public static final class NoisyFixture {
    public static void main(String[] args) throws Exception {
      String start =
          "model_http_started id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee provider=compatible operation=embedding";
      System.err.println(start);
      System.err.println("private arbitrary diagnostic ".repeat(50000));
      System.err.println("index_model_audit_ready");
      System.err.println(start);
      System.err.println(start);
      System.err.println("model_http_started id=private provider=compatible operation=embedding");
      System.err.println(
          "model_http_finished id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee status=200 transport_ok=true elapsed_ms=1 private=Bearer");
      System.err.println(
          "model_http_finished id=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee status=200 transport_ok=true elapsed_ms=1");
      System.err.println("index_model_audit_complete");
      ProcessTextIndexerTest.ProcessFixture.main(new String[] {"success", "unused"});
    }
  }

  private static final class Events extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger logger =
        (Logger) LoggerFactory.getLogger("com.evidence.rag.client.model.ModelHttpTransport");

    Events() {
      start();
      logger.addAppender(this);
    }

    long count(String prefix) {
      return lines.stream().filter(line -> line.startsWith(prefix)).count();
    }

    @Override
    protected void append(ILoggingEvent event) {
      lines.add(event.getFormattedMessage());
    }

    @Override
    public void close() {
      logger.detachAppender(this);
      stop();
    }
  }
}
