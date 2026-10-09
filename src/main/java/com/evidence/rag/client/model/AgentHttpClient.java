package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.AgentFailureCode;
import com.evidence.rag.model.dto.AgentProtocol;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Fixed loopback DB-GPT endpoint, bounded exchange, no retries or redirect following. */
public final class AgentHttpClient implements AgentClient, AutoCloseable {
  private final OpenAiCompatibleModels.Endpoint endpoint;
  private final ModelHttpTransport transport;

  public AgentHttpClient(URI origin, String token, Duration deadline) {
    if (origin == null
        || !"http".equals(origin.getScheme())
        || !Set.of("127.0.0.1", "[::1]").contains(origin.getHost() == null ? "" : origin.getHost())
        || !("".equals(origin.getPath()) || "/".equals(origin.getPath()))
        || token == null
        || !token.matches("[A-Za-z0-9_-]{32,256}")) {
      throw new TextModels.Failure("agent_invalid_configuration");
    }
    endpoint = new OpenAiCompatibleModels.Endpoint(origin, "db-gpt", token);
    ModelHttpTransport.validateEndpoint(endpoint, true);
    transport = new ModelHttpTransport(deadline, 1024 * 1024, 64 * 1024);
  }

  @Override
  public AgentProtocol.Proposal execute(AgentProtocol.RunRequest request) {
    try {
      var result =
          transport.postWithSafeErrorDecoder(
              endpoint,
              "v1/runs",
              Map.of(
                  "run_id",
                  request.runId(),
                  "question",
                  request.question(),
                  "callback_token",
                  request.callbackToken()),
              AgentHttpClient::remoteFailure);
      exact(result, Set.of("refused", "statements", "suggestions"));
      if (!result.path("refused").isBoolean()
          || !result.path("statements").isArray()
          || !result.path("suggestions").isArray()
          || result.path("statements").size() > 64
          || result.path("suggestions").size() > 8) throw invalid();
      var statements = new ArrayList<AgentProtocol.Statement>();
      for (var row : result.path("statements")) {
        exact(row, Set.of("text", "evidence_ids"));
        statements.add(
            new AgentProtocol.Statement(
                text(row.path("text"), 16000), strings(row.path("evidence_ids"), 64)));
      }
      var suggestions = new ArrayList<AgentProtocol.Suggestion>();
      for (var row : result.path("suggestions")) {
        exact(row, Set.of("title", "reason", "document_ids"));
        suggestions.add(
            new AgentProtocol.Suggestion(
                text(row.path("title"), 200),
                text(row.path("reason"), 2000),
                strings(row.path("document_ids"), 32)));
      }
      return new AgentProtocol.Proposal(
          result.path("refused").booleanValue(), statements, suggestions);
    } catch (TextModels.Failure failed) {
      String code =
          switch (failed.code()) {
            case "model_timeout" -> "agent_timeout";
            case "model_invalid_response", "model_response_too_large" -> "agent_invalid_response";
            default -> AgentFailureCode.safe(failed.code(), AgentFailureCode.AGENT_UNAVAILABLE);
          };
      throw new TextModels.Failure(code);
    } catch (RuntimeException failed) {
      throw invalid();
    }
  }

  private static TextModels.Failure remoteFailure(JsonNode response) {
    if (!response.propertyNames().equals(Set.of("error")) || !response.path("error").isString())
      return new TextModels.Failure("agent_unavailable");
    return new TextModels.Failure(
        AgentFailureCode.safe(
            response.path("error").asString(), AgentFailureCode.AGENT_UNAVAILABLE));
  }

  private static void exact(JsonNode value, Set<String> fields) {
    if (!value.isObject() || !value.propertyNames().equals(fields)) throw invalid();
  }

  private static String text(JsonNode node, int max) {
    if (!node.isString()
        || node.asString().isBlank()
        || node.asString().length() > max
        || node.asString()
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
      throw invalid();
    return node.asString();
  }

  private static List<String> strings(JsonNode node, int max) {
    if (!node.isArray() || node.isEmpty() || node.size() > max) throw invalid();
    var values = new ArrayList<String>();
    for (var value : node) values.add(text(value, 128));
    return List.copyOf(values);
  }

  private static TextModels.Failure invalid() {
    return new TextModels.Failure("agent_invalid_response");
  }

  @Override
  public void close() {
    transport.close();
  }
}
