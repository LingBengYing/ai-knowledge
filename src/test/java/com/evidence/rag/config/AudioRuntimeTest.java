package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evidence.rag.service.RuntimeService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AudioRuntimeTest {
  @Test
  void audioRuntimeAdvertisesOnlyImplementedUploadAndIndexStages() {
    for (boolean ingestion : List.of(false, true)) {
      for (boolean indexing : List.of(false, true)) {
        for (boolean answers : List.of(false, true)) {
          for (boolean visual : List.of(false, true)) {
            for (boolean audio : List.of(false, true)) {
              var environment =
                  new MockEnvironment()
                      .withProperty("rag.visual.enabled", Boolean.toString(visual))
                      .withProperty("rag.audio.enabled", Boolean.toString(audio));
              var actual =
                  new RuntimeConfiguration()
                      .runtimeService(
                          new RagProperties(
                              "test", "org-main", "development_headers", "", "", "", Path.of(".")),
                          new IngestionSettings(ingestion, 30000, 30000),
                          new IndexingSettings(indexing, 60000),
                          new AnswersSettings(answers, 60000, 2),
                          new DocumentRemovalSettings(false),
                          environment)
                      .capabilities();
              var baseline =
                  new RuntimeService(
                          "development_headers",
                          "org-main",
                          ingestion,
                          indexing,
                          answers,
                          false,
                          false,
                          visual)
                      .capabilities();
              assertEquals(audio && ingestion, actual.capabilities().contains("audio_upload"));
              assertEquals(audio && indexing, actual.capabilities().contains("audio_index"));
              assertEquals(audio && answers, actual.capabilities().contains("audio_answers"));
              assertEquals(audio && answers, actual.capabilities().contains("audio_sources"));
              assertEquals(baseline.migrationStage(), actual.migrationStage());
              assertEquals(
                  baseline.capabilities(),
                  actual.capabilities().stream()
                      .filter(name -> !name.startsWith("audio_"))
                      .toList());
              assertEquals(baseline.unavailable(), actual.unavailable());
            }
          }
        }
      }
    }
  }
}
