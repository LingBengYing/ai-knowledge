package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.RuntimeController;
import com.evidence.rag.service.RuntimeService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class VoiceQuestionRuntimeTest {
  @TempDir Path directory;

  @Test
  void explicitVoiceCapabilityNeedsNoVisualVideoOrQueryAttachmentConfiguration() {
    var environment =
        new MockEnvironment()
            .withProperty("rag.voice-questions.enabled", "true")
            .withProperty("rag.audio.enabled", "true")
            .withProperty("rag.ingestion.enabled", "true")
            .withProperty("rag.answers.enabled", "true")
            .withProperty("server.address", "127.0.0.1")
            .withProperty("rag.environment", "test");
    var service =
        new RuntimeConfiguration()
            .runtimeService(
                new RagProperties("test", "org-main", "development_headers", "", "", "", directory),
                new IngestionSettings(true, 30000, 30000),
                new IndexingSettings(false, 60000),
                new AnswersSettings(true, 60000, 2),
                new DocumentRemovalSettings(false),
                environment);
    var response = new RuntimeController(service).configuration();
    assertTrue(response.capabilities().contains("voice_questions"));
    assertFalse(response.capabilities().contains("query_attachments"));
    assertFalse(response.capabilities().contains("visual_answers"));
    assertFalse(response.capabilities().contains("video_answers"));
    assertEquals("text_answers", response.migrationStage());
    assertEquals(503, new RuntimeController(service).ready().getStatusCode().value());
  }

  @Test
  void absentFlagAndEveryMissingPrerequisiteKeepVoiceUnavailableAndPreserveOldConstructor() {
    var legacy =
        new RuntimeService(
            "development_headers",
            "org-main",
            true,
            true,
            true,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false);
    assertFalse(legacy.capabilities().capabilities().contains("voice_questions"));
    var disabled =
        new RuntimeService(
            "development_headers",
            "org-main",
            true,
            true,
            true,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            false);
    assertEquals(legacy.capabilities(), disabled.capabilities());
    for (int missing = 0; missing < 3; missing++) {
      var unavailable =
          new RuntimeService(
              "development_headers",
              "org-main",
              missing != 0,
              false,
              missing != 1,
              false,
              false,
              false,
              missing != 2,
              false,
              false,
              false,
              false,
              true);
      assertFalse(unavailable.capabilities().capabilities().contains("voice_questions"));
    }
  }
}
