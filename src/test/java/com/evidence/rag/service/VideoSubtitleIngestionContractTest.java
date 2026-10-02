package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoSubtitleIngestionContractTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void v3CannotDropTheRequiredSubtitleProductEvenWhenNoTrackWouldHaveBeenFound(boolean ocr) {
    var valid = VideoSubtitleCompilationFixture.compilation(ocr);
    var missing =
        new VideoCompilation(
            valid.sourceSha256(),
            valid.decoderRevision(),
            valid.compilerRevision(),
            valid.timelineOriginUs(),
            valid.durationUs(),
            valid.frames(),
            valid.audio(),
            valid.ocr(),
            null);
    rejects(missing, ocr);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void v3CannotInterpretMissingOrExtraOcrAsAConfigurationChange(boolean configuredOcr) {
    rejects(VideoSubtitleCompilationFixture.compilation(!configuredOcr), configuredOcr);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void legacyCompilerCannotSilentlyDiscardAnUnexpectedSubtitleProduct(int version) {
    var valid = VideoSubtitleCompilationFixture.compilation(version == 2);
    var legacy =
        new VideoCompilation(
            valid.sourceSha256(),
            valid.decoderRevision(),
            "java-video-compiler-v" + version + ":" + "a".repeat(64),
            valid.timelineOriginUs(),
            valid.durationUs(),
            valid.frames(),
            valid.audio(),
            valid.ocr(),
            valid.subtitles());
    rejects(legacy, version == 2);
  }

  private void rejects(VideoCompilation value, boolean expectedOcr) {
    try (var store = new SqliteAuthorityStore(directory)) {
      var repository = new IngestionRepository(store);
      var ingestion =
          new IngestionService(
              store,
              repository,
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              null,
              null,
              null,
              value.compilerRevision(),
              expectedOcr);
      var task =
          ingestion.uploadDocument(
              OWNER, "subtitles.mp4", "video/mp4", VideoSubtitleCompilationFixture.ORIGINAL);
      var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
      assertEquals(
          "parser_output_invalid",
          assertThrows(
                  ApplicationException.class, () -> ingestion.completeVideoIngestion(claim, value))
              .code());
      assertEquals("processing", ingestion.ingestionStatus(OWNER, task.taskId()).state());
      assertTrue(
          store.transaction(() -> repository.findVideoCompilation(claim.revisionId()).isEmpty()));
    }
  }
}
