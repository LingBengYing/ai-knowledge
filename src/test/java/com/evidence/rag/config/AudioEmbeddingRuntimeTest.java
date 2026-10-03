package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.service.RuntimeService;
import org.junit.jupiter.api.Test;

class AudioEmbeddingRuntimeTest {
  @Test
  void completeAudioAttachmentPathCanAdvertiseWithoutImageVectors() {
    var runtime = runtime(true, true, true, true, true, true, true, true, true, false);
    assertTrue(runtime.capabilities().capabilities().contains("audio_vector_retrieval"));
    assertFalse(runtime.capabilities().capabilities().contains("image_vector_retrieval"));
  }

  @Test
  void capabilityRequiresEveryExistingAttachmentAndAudioDependency() {
    for (int missing = 0; missing < 9; missing++) {
      assertFalse(
          runtime(
                  missing != 0,
                  missing != 1,
                  missing != 2,
                  missing != 3,
                  missing != 4,
                  missing != 5,
                  missing != 6,
                  missing != 7,
                  missing != 8,
                  true)
              .capabilities()
              .capabilities()
              .contains("audio_vector_retrieval"));
    }
  }

  @Test
  void oldImageVectorConstructorIsIdenticalToExplicitlyDisabledAudioVectorFeature() {
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
            false,
            true,
            true);
    assertEquals(
        legacy.capabilities(),
        runtime(true, true, true, true, true, true, true, true, false, true).capabilities());
  }

  private static RuntimeService runtime(
      boolean ingestion,
      boolean indexing,
      boolean answers,
      boolean imageOcr,
      boolean visual,
      boolean audio,
      boolean video,
      boolean attachments,
      boolean enabled,
      boolean imageVectors) {
    return new RuntimeService(
        "development_headers",
        "org-main",
        ingestion,
        indexing,
        answers,
        false,
        imageOcr,
        visual,
        audio,
        video,
        false,
        attachments,
        false,
        true,
        imageVectors,
        enabled);
  }
}
