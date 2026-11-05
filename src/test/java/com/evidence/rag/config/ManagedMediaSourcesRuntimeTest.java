package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.dto.RuntimeCapabilitiesResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Web-compatibility media names follow the managed text bundle, not a legacy provider profile. */
class ManagedMediaSourcesRuntimeTest {
  @Test
  void incompatibleProvidersDoNotHideAssembledVisualSourceName() {
    var base = capabilities(true);
    var filtered = RuntimeConfiguration.managedCapabilities(base, true, false, true, true);
    assertTrue(filtered.capabilities().contains("visual_sources"));
    assertFalse(filtered.unavailable().contains("visual_sources"));
    for (String provider :
        List.of(
            "visual_image_upload",
            "visual_answers",
            "video_upload",
            "video_index",
            "image_vector_retrieval",
            "audio_vector_retrieval")) {
      assertFalse(filtered.capabilities().contains(provider));
      assertTrue(filtered.unavailable().contains(provider));
    }
    assertEquals("text_answers", filtered.migrationStage());
  }

  @Test
  void missingVisualServiceKeepsVisualSourcesUnavailableAndAudioNamesAvailable() {
    var filtered =
        RuntimeConfiguration.managedCapabilities(capabilities(true), true, false, false, false);
    assertFalse(filtered.capabilities().contains("visual_sources"));
    assertTrue(filtered.unavailable().contains("visual_sources"));
    assertTrue(
        filtered
            .capabilities()
            .containsAll(
                List.of(
                    "audio_upload",
                    "audio_index",
                    "audio_answers",
                    "audio_sources",
                    "synopsis_sources")));
  }

  @Test
  void noActiveTextBundleDoesNotAdvertiseManagedMediaNames() {
    var filtered =
        RuntimeConfiguration.managedCapabilities(capabilities(true), false, false, true, true);
    assertFalse(filtered.capabilities().contains("visual_sources"));
    assertTrue(filtered.unavailable().containsAll(List.of("visual_sources", "audio_answers")));
    assertEquals("text_configuration_required", filtered.migrationStage());
  }

  @Test
  void missingVideoResourcesWithdrawVideoUploadAndIndex() {
    var filtered =
        RuntimeConfiguration.managedCapabilities(capabilities(true), true, true, true, false);
    assertFalse(filtered.capabilities().contains("video_upload"));
    assertFalse(filtered.capabilities().contains("video_index"));
    assertTrue(filtered.capabilities().contains("visual_sources"));
  }

  @Test
  void matchingLegacyGraphKeepsItsOriginalProviderAndSourceCapabilities() {
    var base = capabilities(true);
    var filtered = RuntimeConfiguration.managedCapabilities(base, true, true, true, true);
    assertTrue(filtered.capabilities().containsAll(base.capabilities()));
    assertTrue(filtered.capabilities().contains("retrieval_test"));
    assertEquals(base.unavailable(), filtered.unavailable());
  }

  private static RuntimeCapabilitiesResult capabilities(boolean videoRoutes) {
    var sources =
        List.of(
            "visual_image_upload",
            "visual_answers",
            "visual_sources",
            "image_vector_retrieval",
            "audio_vector_retrieval",
            "audio_upload",
            "audio_index",
            "audio_answers",
            "audio_sources",
            "synopsis_sources");
    var enabled = new ArrayList<>(sources);
    if (videoRoutes) {
      enabled.addAll(List.of("video_upload", "video_index"));
    }
    return new RuntimeCapabilitiesResult(
        "development_headers",
        "org-main",
        "text_answers",
        enabled,
        List.of("production_readiness"));
  }
}
