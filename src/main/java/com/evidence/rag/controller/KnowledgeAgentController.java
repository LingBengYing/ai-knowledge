package com.evidence.rag.controller;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.AgentConfiguration;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.KnowledgeAgentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Existing trusted Actor authentication applies to every public Agent route. */
@RestController
public final class KnowledgeAgentController {
  private final ObjectProvider<KnowledgeAgentService> services;

  public KnowledgeAgentController(ObjectProvider<KnowledgeAgentService> services) {
    this.services = services;
  }

  @GetMapping(value = "/v1/knowledge-agent/config", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentConfiguration> configuration(HttpServletRequest request) {
    read(request);
    AuthenticatedActor.require(request);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(
            new AgentConfiguration(
                services.getIfAvailable() != null,
                "knowledge-native-agent",
                KnowledgeAgentService.MAX_STEPS));
  }

  @PostMapping(
      value = "/v1/knowledge-agent/runs",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentRunResult> start(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null
        || !Set.of("question", "request_id", "conversation_id").containsAll(body.keySet())
        || (body.containsKey("conversation_id") && !(body.get("conversation_id") instanceof String))
        || !(body.get("question") instanceof String question)
        || !(body.get("request_id") instanceof String requestId)) throw ModelValues.invalid();
    return ResponseEntity.accepted()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(
            service()
                .start(
                    AuthenticatedActor.require(request),
                    question,
                    requestId,
                    (String) body.get("conversation_id")));
  }

  @GetMapping(value = "/v1/knowledge-agent/runs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentRunResult> get(HttpServletRequest request, @PathVariable String id) {
    read(request);
    return response(service().get(AuthenticatedActor.require(request), id));
  }

  @GetMapping(
      value = "/v1/knowledge-agent/runs/{id}/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> events(HttpServletRequest request, @PathVariable String id) {
    read(request);
    var subscription = service().subscribe(AuthenticatedActor.require(request), id);
    var emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
    var sender =
        Thread.ofVirtual()
            .name("knowledge-agent-sse")
            .unstarted(
                () -> {
                  try (subscription) {
                    while (!subscription.closed()) {
                      var snapshot = subscription.next(Duration.ofSeconds(15));
                      if (snapshot == null) {
                        if (!subscription.closed())
                          emitter.send(SseEmitter.event().comment("keepalive"));
                        continue;
                      }
                      boolean terminal = !"running".equals(snapshot.run().status());
                      emitter.send(
                          SseEmitter.event()
                              .name(terminal ? "terminal" : "snapshot")
                              .id(Long.toString(snapshot.revision()))
                              .data(snapshot, MediaType.APPLICATION_JSON));
                      if (terminal) break;
                    }
                    emitter.complete();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    emitter.complete();
                  } catch (IOException | IllegalStateException disconnected) {
                    // A closed observer is not a failed or cancelled Agent task.
                    emitter.complete();
                  }
                });
    Runnable dispose =
        () -> {
          subscription.close();
          sender.interrupt();
        };
    emitter.onCompletion(dispose);
    emitter.onTimeout(dispose);
    emitter.onError(ignored -> dispose.run());
    sender.start();
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("X-Accel-Buffering", "no")
        .body(emitter);
  }

  @PostMapping(
      value = "/v1/knowledge-agent/runs/{id}/cancel",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AgentRunResult> cancel(
      HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
    if (request.getQueryString() != null || !body.isEmpty()) throw ModelValues.invalid();
    return response(service().cancel(AuthenticatedActor.require(request), id));
  }

  private KnowledgeAgentService service() {
    var service = services.getIfAvailable();
    if (service == null)
      throw new ApplicationException(FailureKind.UNAVAILABLE, "agent_disabled", "知识助手尚未启用。");
    return service;
  }

  private static void read(HttpServletRequest request) {
    if (request.getQueryString() != null
        || request.getContentLengthLong() > 0
        || request.getHeader("Transfer-Encoding") != null) throw ModelValues.invalid();
  }

  private static ResponseEntity<AgentRunResult> response(AgentRunResult result) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(result);
  }
}
