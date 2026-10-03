package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.AudioTraceEvidence;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.AudioVectorScope;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
      var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var scope = scope(context, audio.documentId());
      var saved = AudioVectorQueryFixture.publishVectors(context, scope, audio);
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
                              AudioVectorQueryFixture.TARGET)));
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
                          AudioVectorQueryFixture.TARGET)));
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @Test
  void repositoryKeepsWorkspaceAndUniquePublicationSelectionBoundaries() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var scope = scope(context, audio.documentId());
      var saved = AudioVectorQueryFixture.publishVectors(context, scope, audio);
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
                          AudioVectorQueryFixture.TARGET))
              .isEmpty());
      assertTrue(
          context
              .authority
              .store()
              .transaction(
                  () ->
                      repository.findPublications(
                          context.owner.workspaceId(), List.of(), AudioVectorQueryFixture.TARGET))
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
                              AudioVectorQueryFixture.TARGET)));
      assertEquals(1, context.scalar("SELECT COUNT(*) FROM audio_vector_publications"));
    }
  }

  @ParameterizedTest(name = "scope mapping: {0}")
  @ValueSource(strings = {"foreign-publication", "colliding-vector", "colliding-base"})
  void independentAudioReceiptsCannotAliasAnotherDocumentsAuthority(String defect) {
    try (var context = context()) {
      var a = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var b = AudioTestFixture.publish(context, List.of("另一份会议。"));
      var scope = scope(context, a.documentId(), b.documentId());
      var first = AudioVectorQueryFixture.publishVectors(context, scope, a);
      var second = AudioVectorQueryFixture.publishVectors(context, scope, b);
      if (defect.equals("foreign-publication")) {
        assertThrows(
            ApplicationException.class,
            () ->
                new AudioVectorScope(
                    scope(context, a.documentId()),
                    AudioVectorQueryFixture.TARGET,
                    AudioVectorQueryFixture.DECODER,
                    List.of(second)));
      } else {
        var original = second.entries().getFirst();
        var alias =
            new AudioVectorEntry(
                original.audioEvidenceId(),
                defect.equals("colliding-base")
                    ? first.entries().getFirst().basePhysicalSegmentId()
                    : original.basePhysicalSegmentId(),
                defect.equals("colliding-vector")
                    ? first.entries().getFirst().vectorPhysicalSegmentId()
                    : original.vectorPhysicalSegmentId(),
                original.ordinal(),
                original.startSample(),
                original.endSample(),
                original.pcmSha256(),
                original.entrySha256());
        var altered =
            new AudioVectorPublication(
                second.id(),
                second.basePublication(),
                second.target(),
                second.vectorGenerationId(),
                second.decoderRevision(),
                List.of(alias),
                second.manifestSha256(),
                second.createdAt());
        assertThrows(
            ApplicationException.class,
            () ->
                new AudioVectorScope(
                    scope,
                    AudioVectorQueryFixture.TARGET,
                    AudioVectorQueryFixture.DECODER,
                    List.of(first, altered)));
      }
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  @ParameterizedTest(name = "persisted receipt qualification: {0}")
  @ValueSource(strings = {"manifest", "vector-generation"})
  void selfConsistentRowShapesDoNotReplaceTheDerivedVectorIdentityOrCompleteManifest(
      String defect) {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of(AudioVectorQueryFixture.FACT));
      var scope = scope(context, audio.documentId());
      var saved = AudioVectorQueryFixture.publishVectors(context, scope, audio);
      String generation = UUID.randomUUID().toString();
      var entry =
          remap(
              saved.entries().getFirst(),
              defect.equals("vector-generation") ? UUID.randomUUID().toString() : generation);
      String manifest =
          new RetrievalProjection.RevisionManifest(
                  context.owner.workspaceId(),
                  audio.documentId(),
                  generation,
                  Map.of(entry.vectorPhysicalSegmentId(), entry.entrySha256()))
              .sha256();
      var forged =
          new AudioVectorPublication(
              UUID.randomUUID().toString(),
              saved.basePublication(),
              OTHER,
              generation,
              AudioVectorQueryFixture.DECODER,
              List.of(entry),
              defect.equals("manifest") ? "0".repeat(64) : manifest,
              Instant.now().toString());
      context
          .authority
          .store()
          .transaction(
              () -> {
                new AudioVectorRepository(context.authority.store()).insert(forged);
                return null;
              });
      assertEquals(
          "audio_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      context.evidence.audioVectorScope(
                          scope, OTHER, AudioVectorQueryFixture.DECODER))
              .code());
      var unqualified =
          new AudioVectorScope(scope, OTHER, AudioVectorQueryFixture.DECODER, List.of(forged));
      assertEquals(
          "audio_vector_required",
          assertThrows(
                  ApplicationException.class,
                  () -> context.evidence.hydrateAudioVectors(unqualified, List.of()))
              .code());
      var material = context.evidence.hydrateAudio(scope, audio.physicalIds()).getFirst();
      var draft =
          new TraceDraft(
              hash(AudioVectorQueryFixture.QUESTION),
              hash(AudioVectorQueryFixture.FACT),
              "answered",
              null,
              "test-answer-model-v1",
              "audio-test-prompt",
              "audio-test-policy",
              List.of(),
              List.of(),
              List.of(
                  new AudioTraceEvidence(
                      1,
                      material.physicalSegmentId(),
                      material.transcript().startCodePoint(),
                      material.transcript().endCodePoint(),
                      1.0,
                      1.0,
                      List.of(hash("预算")))));
      var refused =
          context.evidence.finish(
              scope, draft, () -> AnswerEligibility.ELIGIBLE, null, unqualified);
      assertEquals("audio_vector_required", refused.reasonCode());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM audio_trace_evidence"));
    }
  }

  @Test
  void truncatedHeaderCannotCommitAnEntryPrefixAndLeavesPriorReceiptIntact() {
    try (var context = context()) {
      var audio = AudioTestFixture.publish(context, List.of("会议开始。", AudioVectorQueryFixture.FACT));
      var scope = scope(context, audio.documentId());
      var saved = AudioVectorQueryFixture.publishVectors(context, scope, audio);
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
              AudioVectorQueryFixture.DECODER,
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
          List.of(saved),
          context
              .evidence
              .audioVectorScope(
                  scope, AudioVectorQueryFixture.TARGET, AudioVectorQueryFixture.DECODER)
              .publications());
    }
  }

  @Test
  void textOnlyScopeHasNoAudioReceiptsButRetainsItsOriginalAuthorityChecks() {
    try (var context = context()) {
      String document = context.publish("text-only.txt", "完整文字范围。");
      var scope = scope(context, document);
      var vectors =
          context.evidence.audioVectorScope(
              scope, AudioVectorQueryFixture.TARGET, AudioVectorQueryFixture.DECODER);
      assertTrue(vectors.publications().isEmpty());
      assertEquals(scope, vectors.base());
      assertTrue(context.evidence.hydrateAudioVectors(vectors, List.of()).isEmpty());
      context.revoke(document);
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      context.evidence.audioVectorScope(
                          scope, AudioVectorQueryFixture.TARGET, AudioVectorQueryFixture.DECODER))
              .code());
      assertEquals(0, context.scalar("SELECT COUNT(*) FROM query_traces"));
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, AudioVectorQueryFixture.BUDGET, 1);
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

  private static String hash(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
