package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAnswersRuntimeTest {
  @Test
  void videoAnswersRequireBothExistingVideoAndAnswerOptIns() {
    var enabled =
        new RuntimeService(
                "development_headers",
                "org-main",
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                true)
            .capabilities();
    assertTrue(enabled.capabilities().containsAll(List.of("video_answers", "video_sources")));
    var disabled =
        new RuntimeService(
                "development_headers",
                "org-main",
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                true)
            .capabilities();
    assertFalse(disabled.capabilities().contains("video_answers"));
    assertFalse(disabled.capabilities().contains("video_sources"));
  }
}
