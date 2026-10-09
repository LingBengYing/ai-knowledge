package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentProtocol;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentProtocolClientTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void currentGenerationProtocolReturnsReActTextWithoutJsonModeTokenLimitOrRevisionChange()
      throws Exception {
    try (var server = new Server()) {
      server.reply.set(
          "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Thought: need evidence\\nAction: knowledge_search\"}}]}");
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(server.uri(), "synthetic", "local-fixture-only");
      try (var models =
          new OpenAiCompatibleModels(
              new OpenAiCompatibleModels.Configuration(
                  endpoint, endpoint, endpoint, 2, Duration.ofSeconds(2), 1024 * 1024, true))) {
        String revision = models.revision();
        String content =
            models.agentChat(
                List.of(
                    new AgentMessage("system", "Read original evidence"),
                    new AgentMessage("user", "整理资料")));
        assertTrue(content.startsWith("Thought:"));
        assertEquals("/chat/completions", server.path.get());
        assertEquals("Bearer local-fixture-only", server.auth.get());
        assertEquals(2, server.request.get().path("messages").size());
        assertFalse(server.request.get().has("max_tokens"));
        assertFalse(server.request.get().has("max_completion_tokens"));
        assertFalse(server.request.get().has("response_format"));
        assertEquals(revision, models.revision());
        for (String reason : List.of("length", "tool_calls", "content_filter")) {
          server.reply.set(
              "{\"choices\":[{\"index\":0,\"finish_reason\":\""
                  + reason
                  + "\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}");
          int before = server.calls.get();
          assertThrows(
              TextModels.Failure.class,
              () -> models.agentChat(List.of(new AgentMessage("user", "question"))));
          assertEquals(before + 1, server.calls.get(), "No automatic retries");
        }
      }
    }
  }

  @Test
  void sidecarHasOneFixedLoopbackDestinationAndStrictStructuredProposal() throws Exception {
    try (var server = new Server()) {
      String token = UUID.randomUUID().toString();
      try (var client = new AgentHttpClient(server.uri(), token, Duration.ofSeconds(2))) {
        server.reply.set(
            "{\"refused\":false,\"statements\":[{\"text\":\"结果\",\"evidence_ids\":[\"source-1\"]}],\"suggestions\":[]}");
        var result =
            client.execute(
                new AgentProtocol.RunRequest(UUID.randomUUID().toString(), "问题", "b".repeat(64)));
        assertEquals("/v1/runs", server.path.get());
        assertEquals("Bearer " + token, server.auth.get());
        assertEquals(3, server.request.get().size());
        assertEquals(List.of("source-1"), result.statements().getFirst().evidenceIds());
        for (String malformed :
            List.of(
                "{}",
                "{\"refused\":true,\"statements\":[],\"suggestions\":[],\"url\":\"external\"}",
                "{\"refused\":false,\"statements\":[{\"text\":\"bad\",\"evidence_ids\":[]}],\"suggestions\":[]}")) {
          server.reply.set(malformed);
          int before = server.calls.get();
          assertThrows(
              TextModels.Failure.class,
              () ->
                  client.execute(
                      new AgentProtocol.RunRequest(
                          UUID.randomUUID().toString(), "question", "b".repeat(64))));
          assertEquals(before + 1, server.calls.get());
        }
      }
      for (String uri :
          List.of(
              "http://example.com",
              "https://127.0.0.1",
              "http://127.0.0.1/other",
              "http://127.0.0.1?url=external")) {
        assertThrows(
            TextModels.Failure.class,
            () -> new AgentHttpClient(URI.create(uri), token, Duration.ofSeconds(2)));
      }
    }
  }

  private static final class Server implements AutoCloseable {
    final HttpServer server;
    final AtomicReference<String> reply = new AtomicReference<>("{}");
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();

    Server() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
