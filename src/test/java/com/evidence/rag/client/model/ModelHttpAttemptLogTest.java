package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ModelHttpAttemptLogTest {
  @Test
  void countsSuccessAndHttpFailureOnceWithoutLoggingCredentialsOrContent() throws Exception {
    var received = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          int status = received.incrementAndGet() == 1 ? 200 : 503;
          byte[] body = "{\"provider_secret_response\":true}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var logger = (Logger) LoggerFactory.getLogger(ModelHttpTransport.class);
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try (var transport = new ModelHttpTransport(Duration.ofSeconds(2), 4096, 4096)) {
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/private-path"),
              "private-model",
              "private-credential");
      transport.post(endpoint, "embeddings", Map.of("input", "private-question"));
      var failure =
          assertThrows(
              TextModels.Failure.class,
              () ->
                  transport.post(
                      endpoint, "chat/completions", Map.of("input", "private-question")));
      assertEquals("model_http_failed", failure.code());
      assertEquals(2, received.get());
      var lines = events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertEquals(4, lines.size());
      assertTrue(
          lines
              .get(0)
              .matches(
                  "model_http_started id=[0-9a-f-]{36} provider=compatible operation=embedding"));
      assertTrue(
          lines
              .get(1)
              .matches(
                  "model_http_finished id=[0-9a-f-]{36} status=200 transport_ok=true elapsed_ms=[0-9]+"));
      assertTrue(lines.get(2).contains("operation=generation"));
      assertTrue(lines.get(3).contains("status=503 transport_ok=false"));
      assertEquals(lines.get(0).split(" ")[1], lines.get(1).split(" ")[1]);
      assertEquals(lines.get(2).split(" ")[1], lines.get(3).split(" ")[1]);
      assertNotEquals(lines.get(0).split(" ")[1], lines.get(2).split(" ")[1]);
      for (String forbidden : new String[] {"private-", "provider_secret", "127.0.0.1", "Bearer"}) {
        assertFalse(String.join("\n", lines).contains(forbidden));
      }
      events.list.clear();
      assertThrows(
          TextModels.Failure.class,
          () -> transport.post(endpoint, "embeddings", Map.of("input", "x".repeat(8192))));
      assertTrue(events.list.isEmpty());
      assertEquals(2, received.get());
    } finally {
      logger.detachAppender(events);
      events.stop();
      server.stop(0);
    }
  }
}
