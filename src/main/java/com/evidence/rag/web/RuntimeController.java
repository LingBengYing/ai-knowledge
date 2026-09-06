package com.evidence.rag.web;

import com.evidence.rag.config.RagProperties;
import com.evidence.rag.ingestion.IngestionSettings;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeController {
  private final RagProperties properties;
  private final IngestionSettings ingestion;

  public RuntimeController(RagProperties properties, IngestionSettings ingestion) {
    this.properties = properties;
    this.ingestion = ingestion;
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
        ingestion.enabled() ? "text_ingestion" : "management_slice",
        "capabilities",
        ingestion.enabled()
            ? List.of(
                "management",
                "folders",
                "metadata",
                "batch_move",
                "batch_tag",
                "text_upload",
                "ingestions")
            : List.of("management", "folders", "metadata", "batch_move", "batch_tag"),
        "unavailable",
        ingestion.enabled()
            ? List.of("answers", "sources", "reindex", "document_delete")
            : List.of("upload", "answers", "sources", "ingestions", "reindex", "document_delete"));
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
