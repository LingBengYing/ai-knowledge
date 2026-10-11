package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentProtocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configured JSON document limit counts UTF-8 bytes of the whole callback body, while the
 * callback contract (ModelRequest) budgets 262144 characters of content. Chinese text is 3 bytes
 * per character, so the byte limit must leave room for every body the contract accepts.
 */
class KnowledgeAgentCallbackBodyLimitTest {
  @Test
  void modelCallbackThatTheContractAcceptsSurvivesTheConfiguredBodyParser() throws Exception {
    String[] properties =
        Files.readAllLines(Path.of("src/main/resources/application.properties")).stream()
            .filter(line -> line.startsWith("spring.jackson."))
            .toArray(String[]::new);
    var messages = new ArrayList<AgentMessage>();
    messages.add(new AgentMessage("system", "prompt"));
    // Python wraps prior turns + summary (default threshold 24000 + summary <= 12000 chars).
    messages.add(
        new AgentMessage("user", "Untrusted conversation background:" + "史".repeat(36000)));
    messages.add(new AgentMessage("user", "继续问"));
    for (int step = 1; step <= 2; step++) {
      // Each knowledge_read returns 20 document segments of <= 1200 code points.
      var rows = new StringBuilder("{\"sources\":[");
      for (int i = 1; i <= 20; i++) {
        if (i > 1) rows.append(',');
        rows.append("{\"source_id\":\"source-")
            .append(i)
            .append(
                "\",\"document_id\":\"doc\",\"title\":\"t\",\"kind\":\"document_text\",\"text\":\"")
            .append("资".repeat(1200))
            .append("\"}");
      }
      rows.append("]}");
      String call = "call-" + step;
      messages.add(
          new AgentMessage(
              "assistant",
              null,
              List.of(
                  new AgentMessage.ToolCall(
                      call,
                      "function",
                      new AgentMessage.FunctionCall(
                          "knowledge_read", "{\"source_ids\":[\"source-1\"]}"))),
              null,
              "思".repeat(4000)));
      messages.add(new AgentMessage("tool", rows.toString(), null, call));
    }
    // Java's own callback contract accepts it (well under 262144 characters).
    assertDoesNotThrow(() -> new AgentProtocol.ModelRequest(messages));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withPropertyValues(properties)
        .run(
            context -> {
              var mapper = context.getBean(JsonMapper.class);
              byte[] body =
                  mapper
                      .writeValueAsString(Map.of("messages", messages))
                      .getBytes(StandardCharsets.UTF_8);
              assertTrue(body.length > 262144, "UTF-8 body " + body.length);
              assertDoesNotThrow(() -> mapper.readValue(body, Map.class));
              // The parser still bounds the body: 1 MiB is the cap shared with the agent sidecar.
              byte[] oversized =
                  mapper
                      .writeValueAsString(
                          Map.of("messages", Collections.nCopies(5, "资".repeat(80000))))
                      .getBytes(StandardCharsets.UTF_8);
              assertTrue(oversized.length > 1048576, "UTF-8 body " + oversized.length);
              assertThrows(Exception.class, () -> mapper.readValue(oversized, Map.class));
            });
  }
}
