package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.service.RuntimeService;
import org.junit.jupiter.api.Test;

class ImageEmbeddingRuntimeTest {
  @Test
  void imageVectorCapabilityRequiresIndexingAndTheActualVisualAttachmentPath() {
    assertTrue(
        runtime(true, true, true, true, true, true, true)
            .capabilities()
            .capabilities()
            .contains("image_vector_retrieval"));
    for (int missing = 0; missing < 7; missing++) {
      assertFalse(
          runtime(
                  missing != 0,
                  missing != 1,
                  missing != 2,
                  missing != 3,
                  missing != 4,
                  missing != 5,
                  missing != 6)
              .capabilities()
              .capabilities()
              .contains("image_vector_retrieval"));
    }
    assertFalse(
        new RuntimeService(
                "development_headers",
                "org-main",
                true,
                true,
                true,
                false,
                true,
                true,
                true,
                true,
                false,
                false,
                false,
                true)
            .capabilities()
            .capabilities()
            .contains("image_vector_retrieval"));
  }

  @Test
  void oldConstructorIsIdenticalToExplicitlyDisabledImageVectorFeature() {
    var legacy =
        new RuntimeService(
            "development_headers",
            "org-main",
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            false,
            true,
            false);
    var disabled =
        new RuntimeService(
            "development_headers",
            "org-main",
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false);
    assertEquals(legacy.capabilities(), disabled.capabilities());
  }

  private static RuntimeService runtime(
      boolean indexing,
      boolean answers,
      boolean visual,
      boolean imageOcr,
      boolean audio,
      boolean video,
      boolean enabled) {
    return new RuntimeService(
        "development_headers",
        "org-main",
        true,
        indexing,
        answers,
        false,
        imageOcr,
        visual,
        audio,
        video,
        false,
        true,
        false,
        enabled);
  }
}
