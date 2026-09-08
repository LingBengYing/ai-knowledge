package com.evidence.rag.controller;

import com.evidence.rag.model.vo.HealthResponse;
import com.evidence.rag.model.vo.RuntimeResponse;
import com.evidence.rag.service.RuntimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class RuntimeController {
  private final RuntimeService runtime;

  public RuntimeController(RuntimeService runtime) {
    this.runtime = runtime;
  }

  @GetMapping("/v1/config")
  public RuntimeResponse configuration() {
    var c = runtime.capabilities();
    return new RuntimeResponse(
        c.authMode(),
        c.workspaceId(),
        "java",
        c.migrationStage(),
        c.capabilities(),
        c.unavailable());
  }

  @GetMapping("/health/live")
  public HealthResponse live() {
    return new HealthResponse("ok", "java", null);
  }

  @GetMapping("/health/ready")
  public ResponseEntity<HealthResponse> ready() {
    return ResponseEntity.status(503)
        .body(new HealthResponse("migration_incomplete", "java", "资料整理开发版；尚不具备完整 RAG 与生产发布条件。"));
  }
}
