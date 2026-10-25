package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.dto.RuntimeCapabilities;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A batch only completes through automatic import indexing, so both must be available. */
class BatchUploadCapabilityTest {
  private static RuntimeCapabilities base(String... enabled) {
    return new RuntimeCapabilities(
        "development_headers", "org-main", "text_answers", List.of(enabled), List.of());
  }

  @Test
  void batchUploadNeedsIngestionIndexingAndAutomaticImportIndexing() {
    assertTrue(
        RuntimeConfiguration.reindexCapabilities(base("ingestions", "indexings"), true)
            .capabilities()
            .contains("batch_upload"));
    assertTrue(
        RuntimeConfiguration.reindexCapabilities(base("ingestions", "indexings"), true)
            .capabilities()
            .contains("batch_streaming_upload"));
    var noIndexing = RuntimeConfiguration.reindexCapabilities(base("ingestions"), true);
    assertFalse(noIndexing.capabilities().contains("batch_upload"));
    assertTrue(noIndexing.unavailable().contains("batch_upload"));
    assertTrue(noIndexing.unavailable().contains("batch_streaming_upload"));
    assertTrue(
        noIndexing.unavailable().containsAll(List.of("batch_organization", "batch_sources")));
    assertTrue(
        RuntimeConfiguration.reindexCapabilities(base("ingestions", "indexings"), true)
            .capabilities()
            .containsAll(List.of("batch_organization", "batch_sources", "wiki_maintenance")));
    assertFalse(
        RuntimeConfiguration.reindexCapabilities(base("ingestions", "indexings"), false)
            .capabilities()
            .contains("batch_upload"));
  }

  @Test
  void managedRuntimeWithoutTextModelsWithdrawsBatchUpload() {
    var withBatch =
        RuntimeConfiguration.reindexCapabilities(
            base("ingestions", "indexings", "text_index"), true);
    var filtered =
        RuntimeConfiguration.managedCapabilities(withBatch, false, true, true, true, true);
    assertFalse(filtered.capabilities().contains("batch_upload"));
    assertTrue(filtered.unavailable().contains("batch_upload"));
    assertFalse(filtered.capabilities().contains("batch_streaming_upload"));
    assertTrue(filtered.unavailable().contains("batch_streaming_upload"));
    assertFalse(filtered.capabilities().contains("batch_organization"));
    assertFalse(filtered.capabilities().contains("batch_sources"));
    assertTrue(filtered.capabilities().contains("wiki_maintenance"));
  }
}
