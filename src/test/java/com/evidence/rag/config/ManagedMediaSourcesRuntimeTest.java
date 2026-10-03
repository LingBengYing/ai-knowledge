package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.dto.RuntimeCapabilities;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Source route availability is independent of a legacy provider graph's executing profile. */
class ManagedMediaSourcesRuntimeTest {
  @Test
  void incompatibleProvidersDoNotHideAssembledVisualOrVideoSourceReads() {
    var base = capabilities(true);
    var filtered = RuntimeConfiguration.managedCapabilities(base, true, false, true, true, true);
    assertTrue(filtered.capabilities().containsAll(List.of("visual_sources", "video_sources")));
    assertFalse(filtered.unavailable().contains("visual_sources"));
    assertFalse(filtered.unavailable().contains("video_sources"));
    for (String provider :
        List.of(
            "visual_image_upload",
            "visual_answers",
            "video_upload",
            "video_index",
            "video_answers",
            "query_attachments",
            "image_vector_retrieval",
            "audio_vector_retrieval")) {
      assertFalse(filtered.capabilities().contains(provider));
      assertTrue(filtered.unavailable().contains(provider));
    }
    assertTrue(base.capabilities().contains("video_answers"));
    assertEquals("text_answers", filtered.migrationStage());
  }

  @Test
  void activeManagedVideoSourcesNeedNoDecoderWhileMissingVisualServiceRemainsUnavailable() {
    var filtered =
        RuntimeConfiguration.managedCapabilities(
            capabilities(true), true, false, false, false, false);
    assertTrue(filtered.capabilities().contains("video_sources"));
    assertFalse(filtered.capabilities().contains("visual_sources"));
    assertTrue(filtered.unavailable().contains("visual_sources"));
    assertFalse(filtered.capabilities().contains("video_answers"));
    assertTrue(
        filtered
            .capabilities()
            .containsAll(
                List.of(
                    "audio_upload",
                    "audio_index",
                    "audio_answers",
                    "audio_sources",
                    "sound_sources",
                    "video_av_sources",
                    "synopsis_sources")));
  }

  @Test
  void noActiveTextBundleDoesNotAdvertiseManagedMediaReads() {
    var filtered =
        RuntimeConfiguration.managedCapabilities(
            capabilities(true), false, false, true, true, true);
    assertFalse(filtered.capabilities().contains("visual_sources"));
    assertFalse(filtered.capabilities().contains("video_sources"));
    assertTrue(filtered.unavailable().containsAll(List.of("visual_sources", "video_sources")));
    assertEquals("text_configuration_required", filtered.migrationStage());
  }

  @Test
  void disabledVideoRoutesCannotBeCreatedByAnActiveManagedBundle() {
    var filtered =
        RuntimeConfiguration.managedCapabilities(
            capabilities(false), true, false, true, false, false);
    assertFalse(filtered.capabilities().contains("video_sources"));
    assertTrue(filtered.capabilities().contains("visual_sources"));
    assertFalse(filtered.capabilities().contains("video_answers"));
  }

  @Test
  void matchingLegacyGraphKeepsItsOriginalProviderAndSourceCapabilities() {
    var base = capabilities(true);
    var filtered = RuntimeConfiguration.managedCapabilities(base, true, true, true, true, true);
    assertTrue(filtered.capabilities().containsAll(base.capabilities()));
    assertTrue(filtered.capabilities().contains("retrieval_test"));
    assertEquals(base.unavailable(), filtered.unavailable());
  }

  private static RuntimeCapabilities capabilities(boolean videoRoutes) {
    var sources =
        List.of(
            "answers",
            "sources",
            "visual_image_upload",
            "visual_answers",
            "visual_sources",
            "query_attachments",
            "image_vector_retrieval",
            "audio_vector_retrieval",
            "audio_upload",
            "audio_index",
            "audio_answers",
            "audio_sources",
            "sound_sources",
            "video_av_sources",
            "synopsis_sources");
    var enabled = new ArrayList<>(sources);
    if (videoRoutes) {
      enabled.addAll(List.of("video_upload", "video_index", "video_answers", "video_sources"));
    }
    return new RuntimeCapabilities(
        "development_headers",
        "org-main",
        "text_answers",
        enabled,
        List.of("production_readiness"));
  }
}
