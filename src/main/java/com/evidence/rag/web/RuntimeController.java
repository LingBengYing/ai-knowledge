package com.evidence.rag.web;

import com.evidence.rag.config.RagProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeController {
  private final RagProperties properties;

  public RuntimeController(RagProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/v1/config")
  public Map<String, Object> configuration() {
    return Map.of(
        "auth_mode",
        properties.authMode(),
        "workspace_id",
        properties.workspaceId(),
        "edition",
        "java",
        "migration_stage",
        "management_slice",
        "capabilities",
        List.of("management", "folders", "metadata", "batch_move", "batch_tag"),
        "unavailable",
        List.of("upload", "answers", "sources", "ingestions", "reindex", "document_delete"));
  }

  @GetMapping("/health/live")
  public Map<String, String> live() {
    return Map.of("status", "ok", "edition", "java");
  }

  @GetMapping("/health/ready")
  public ResponseEntity<Map<String, String>> ready() {
    return ResponseEntity.status(503)
        .body(
            Map.of(
                "status",
                "migration_incomplete",
                "edition",
                "java",
                "detail",
                "资料整理开发版；尚不具备完整 RAG 与生产发布条件。"));
  }
}
