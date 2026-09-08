package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.dto.TaskResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TaskResponseMapperTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void ingestionAndIndexingKeepExactPublicVariantsIncludingExplicitNullFields() {
    var fields =
        new HashSet<>(
            Set.of(
                "task_id",
                "document_id",
                "revision_id",
                "filename",
                "state",
                "status",
                "attempt",
                "error_code",
                "created_at",
                "updated_at",
                "can_retry",
                "can_cancel"));
    var ingestion = json.valueToTree(TaskResponseMapper.from(task(false, null)));
    assertEquals(fields, new HashSet<>(ingestion.propertyNames()));
    assertTrue(ingestion.path("error_code").isNull());
    assertEquals("queued", ingestion.path("state").asString());
    assertEquals("queued", ingestion.path("status").asString());
    assertEquals("source-revision", ingestion.path("revision_id").asString());
    assertEquals("synthetic.txt", ingestion.path("filename").asString());
    assertEquals("created", ingestion.path("created_at").asString());
    assertEquals("updated", ingestion.path("updated_at").asString());
    assertEquals(1, ingestion.path("attempt").asInt());
    assertFalse(ingestion.path("can_retry").asBoolean());
    assertTrue(ingestion.path("can_cancel").asBoolean());
    fields.add("index_publication_id");
    var indexing = json.valueToTree(TaskResponseMapper.from(task(true, null)));
    assertEquals(fields, new HashSet<>(indexing.propertyNames()));
    assertTrue(indexing.path("index_publication_id").isNull());
    assertFalse(indexing.has("indexing"));
    assertEquals(
        "publication",
        json.valueToTree(TaskResponseMapper.from(task(true, "publication")))
            .path("index_publication_id")
            .asString());
  }

  @Test
  void missingLatestTaskStaysNullRatherThanCreatingAnEmptyTask() {
    assertNull(TaskResponseMapper.from(null));
  }

  private static TaskResult task(boolean indexing, String publication) {
    return new TaskResult(
        "task",
        "document",
        "source-revision",
        "synthetic.txt",
        "queued",
        1,
        null,
        "created",
        "updated",
        false,
        true,
        indexing,
        publication);
  }
}
