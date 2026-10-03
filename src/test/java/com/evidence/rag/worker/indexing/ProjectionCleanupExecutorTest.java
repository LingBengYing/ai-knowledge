package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.MilvusProjectionCleanup;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectionCleanupExecutorTest {
  @Test
  void heldOriginalCollectionLeaseBlocksWithoutDeleteAndIsReusableAfterRelease() throws Exception {
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            requests.incrementAndGet();
            byte[] response =
                "{\"code\":0,\"data\":{\"has\":false}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    server.start();
    try (var executor = Executors.newSingleThreadExecutor()) {
      URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      var heldSettings = settings(endpoint, Duration.ofSeconds(3));
      var shortSettings = settings(endpoint, Duration.ofMillis(150));
      var attempt =
          new ProjectionAttempt(
              "removed",
              "org-main",
              "source",
              "a".repeat(64),
              "gen",
              "legacy",
              MilvusProjectionCleanup.qualified(shortSettings),
              true);
      try (var originalLease =
          IndexWorkerLifetime.acquire(
              heldSettings, IndexWorkerLifetime.Parent.current(), Duration.ofSeconds(3), false)) {
        var waiting =
            executor.submit(
                () ->
                    ProjectionCleanupExecutor.clean(
                        new MilvusProjectionCleanup(List.of(shortSettings)), List.of(attempt)));
        var result = waiting.get(2, TimeUnit.SECONDS);
        assertEquals("blocked", result.logicalRows());
        assertEquals("cleanup_projection_busy", result.errorCode());
        assertEquals(0, requests.get());
        originalLease.check();
      }
      var result =
          executor
              .submit(
                  () ->
                      ProjectionCleanupExecutor.clean(
                          new MilvusProjectionCleanup(List.of(heldSettings)), List.of(attempt)))
              .get(3, TimeUnit.SECONDS);
      assertEquals("completed", result.logicalRows());
      assertEquals("blocked", result.physicalStorage());
      assertEquals(1, requests.get());
      assertTrue(server.getAddress().getPort() > 0);
    } finally {
      server.stop(0);
    }
  }

  private static MilvusRestProjection.Settings settings(URI endpoint, Duration timeout) {
    return new MilvusRestProjection.Settings(
        endpoint,
        "",
        "default",
        "java_cleanup_lease_test",
        "org-main",
        "fixture-embed-v1",
        2,
        timeout,
        1048576,
        true);
  }
}
