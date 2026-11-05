package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AudioVectorAuthorityBoundaryTest {
  @TempDir Path directory;
  private static final IndexTarget OTHER =
      new IndexTarget("audio-other", "e".repeat(64), "audio-other", 2);

  @ParameterizedTest(name = "repository source identity: {0}")
  @ValueSource(strings = {"document", "revision", "sha"})
  void callerCannotRebindASavedReceiptByReusingItsPublicationId(String changed) {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of(VectorReceiptFixtures.AUDIO_FACT));
      var scope = scope(context, audio.documentId());
      var saved = VectorReceiptFixtures.publishAudioVectors(context, scope, audio);
      var base = saved.basePublication();
      var forged =
          new PublicationVersion(
              changed.equals("document") ? "different-doc" : base.documentId(),
              base.publicationId(),
              changed.equals("revision") ? "different-revision" : base.sourceRevisionId(),
              base.projectionGenerationId(),
              changed.equals("sha") ? "f".repeat(64) : base.sourceSha256(),
              base.parserRevision(),
              base.target(),
              base.manifestSha256(),
              base.segmentCount());
      var repository = new AudioVectorRepository(context.authority.store());
      assertThrows(
          ApplicationException.class,
          () ->
              context
                  .authority
                  .store()
                  .transaction(
                      () ->
                          repository.findPublications(
                              context.owner.workspaceId(),
                              List.of(forged),
                              VectorReceiptFixtures.AUDIO_TARGET)));
      assertEquals(
          List.of(saved),
          context
              .authority
              .store()
              .transaction(
                  () ->
                      repository.findPublications(
                          context.owner.workspaceId(),
                          List.of(base),
                          VectorReceiptFixtures.AUDIO_TARGET)));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void repositoryKeepsWorkspaceAndUniquePublicationSelectionBoundaries() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of(VectorReceiptFixtures.AUDIO_FACT));
      var scope = scope(context, audio.documentId());
      var saved = VectorReceiptFixtures.publishAudioVectors(context, scope, audio);
      var repository = new AudioVectorRepository(context.authority.store());
      assertTrue(
          context
              .authority
              .store()
              .transaction(
                  () ->
                      repository.findPublications(
                          "other-workspace",
                          List.of(saved.basePublication()),
                          VectorReceiptFixtures.AUDIO_TARGET))
              .isEmpty());
      assertTrue(
          context
              .authority
              .store()
              .transaction(
                  () ->
                      repository.findPublications(
                          context.owner.workspaceId(),
                          List.of(),
                          VectorReceiptFixtures.AUDIO_TARGET))
              .isEmpty());
      assertThrows(
          ApplicationException.class,
          () ->
              context
                  .authority
                  .store()
                  .transaction(
                      () ->
                          repository.findPublications(
                              context.owner.workspaceId(),
                              List.of(saved.basePublication(), saved.basePublication()),
                              VectorReceiptFixtures.AUDIO_TARGET)));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
    }
  }

  @Test
  void truncatedHeaderCannotCommitAnEntryPrefixAndLeavesPriorReceiptIntact() {
    try (var context = context()) {
      var audio =
          AudioTestFixture.publish(context, List.of("会议开始。", VectorReceiptFixtures.AUDIO_FACT));
      var scope = scope(context, audio.documentId());
      var saved = VectorReceiptFixtures.publishAudioVectors(context, scope, audio);
      var base = saved.basePublication();
      var truncatedBase =
          new PublicationVersion(
              base.documentId(),
              base.publicationId(),
              base.sourceRevisionId(),
              base.projectionGenerationId(),
              base.sourceSha256(),
              base.parserRevision(),
              base.target(),
              base.manifestSha256(),
              1);
      String generation = UUID.randomUUID().toString();
      var entry = remap(saved.entries().getFirst(), generation);
      var prefix =
          new AudioVectorPublication(
              UUID.randomUUID().toString(),
              truncatedBase,
              OTHER,
              generation,
              VectorReceiptFixtures.AUDIO_DECODER,
              List.of(entry),
              "0".repeat(64),
              Instant.now().toString());
      assertThrows(
          ApplicationException.class,
          () ->
              context
                  .authority
                  .store()
                  .transaction(
                      () -> {
                        new AudioVectorRepository(context.authority.store()).insert(prefix);
                        return null;
                      }));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM audio_vector_entries"));
      assertEquals(
          1,
          context.scalar(
              "SELECT COUNT(*) FROM audio_vector_publications WHERE id='" + saved.id() + "'"));
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory);
  }

  private static EvidenceScope scope(AnswerTestContext context, String... ids) {
    return context.evidence.snapshot(
        context.owner, DocumentSelection.selected(List.of(ids)), context.target);
  }

  private static AudioVectorEntry remap(AudioVectorEntry original, String generation) {
    return new AudioVectorEntry(
        original.audioEvidenceId(),
        original.basePhysicalSegmentId(),
        RetrievalProjection.physicalSegmentId(generation, original.audioEvidenceId()),
        original.ordinal(),
        original.startSample(),
        original.endSample(),
        original.pcmSha256(),
        original.entrySha256());
  }
}
