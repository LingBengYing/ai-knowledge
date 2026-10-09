package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundProof;
import com.evidence.rag.model.domain.SoundProofIdentity;
import com.evidence.rag.model.domain.SoundPublishedSpan;
import com.evidence.rag.model.domain.SoundScope;
import com.evidence.rag.model.domain.SoundTraceDraft;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.tool.answer.SoundProofBinding;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

class SoundAuthorityBoundaryTest {
  @TempDir Path directory;

  @Test
  void anEmptyAllScopeMustStillBeCurrentAtHydrationAndTraceCommit() {
    try (var fixture = new SoundTestFixture(directory)) {
      var scope = scope(fixture, DocumentSelection.allDocuments());
      assertTrue(
          fixture.store.transaction(() -> fixture.repository.hydrate(scope, List.of())).isEmpty());
      var refusal =
          new SoundTraceDraft(
              SoundProofIdentity.sha(SoundTestFixture.QUESTION),
              null,
              "abstained",
              "empty_scope",
              List.of(),
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      assertEquals(
          "abstained",
          fixture.store.transaction(() -> fixture.repository.finish(scope, refusal)).status());
      fixture.register("new-sound", SoundTestFixture.pcm(2, 1), false);
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      fixture.store.transaction(() -> fixture.repository.hydrate(scope, List.of())))
              .code());
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () -> fixture.store.transaction(() -> fixture.repository.finish(scope, refusal)))
              .code());
      assertEquals(1, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void libraryBeyond128ProceedsToIndexQualificationWithoutProviderCalls() {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      for (int index = 0; index < 129; index++) {
        fixture.register("sound-" + index, SoundTestFixture.pcm(2, 1), false);
      }
      assertEquals(
          "sound_index_required",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          SoundTestFixture.OWNER,
                          new AnswerCommand(
                              SoundTestFixture.QUESTION, DocumentSelection.allDocuments())))
              .code());
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void anOversizedHydrationBatchIsRejectedBeforeAnyCandidateCanBeReturned() {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("library", SoundTestFixture.pcm(2, 1), true);
      var scope = scope(fixture, DocumentSelection.selected(List.of("library")));
      var ids = IntStream.range(0, 65).mapToObj(index -> "candidate-" + index).toList();
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.hydrate(scope, ids)));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void independentSoundRegistrationDoesNotAcceptATextOriginal() {
    try (var fixture = new SoundTestFixture(directory)) {
      byte[] bytes = new byte[] {65};
      var text =
          new DocumentOriginal(
              "note",
              "note-v1",
              "note.txt",
              "document",
              "text/plain",
              ModelValues.sha256(bytes),
              bytes.length,
              bytes);
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> {
                    fixture.repository.insertOriginal(text, "2026-10-03T00:00:00Z");
                    return null;
                  }));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_originals"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"revision", "source-sha", "filename", "media-type"})
  void aSavedPublicationIsNotReusableForChangedOriginalIdentity(String field) {
    try (var fixture = new SoundTestFixture(directory)) {
      var original = fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      byte[] content = original.content();
      if (field.equals("source-sha")) {
        content[content.length - 1] ^= 1;
      }
      var changed =
          new DocumentOriginal(
              original.documentId(),
              field.equals("revision") ? "new-source" : original.revisionId(),
              field.equals("filename") ? "renamed.wav" : original.filename(),
              "audio",
              field.equals("media-type") ? "audio/flac" : original.mediaType(),
              ModelValues.sha256(content),
              content.length,
              content);
      assertTrue(
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.findPublication(
                          changed, SoundTestFixture.TARGET, SoundTestFixture.PROFILE))
              .isEmpty());
      assertTrue(
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.findPublication(
                          original, SoundTestFixture.TARGET, SoundTestFixture.PROFILE))
              .isPresent());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"revision", "source-sha", "filename", "media-type", "size"})
  void currentMetadataDriftInvalidatesTheWholeSavedScope(String field) {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      var before = scope(fixture, DocumentSelection.selected(List.of("library")));
      String assignment =
          switch (field) {
            case "revision" -> "active_revision_id='new-source'";
            case "source-sha" -> "source_sha256='" + "0".repeat(64) + "'";
            case "filename" -> "filename='changed.wav'";
            case "media-type" -> "mime_type='audio/flac'";
            case "size" -> "size_bytes=size_bytes+2";
            default -> throw new AssertionError(field);
          };
      String mutation = "UPDATE documents SET " + assignment + " WHERE id='library'";
      var blocked = assertThrows(AssertionError.class, () -> ordinarySql(fixture.store, mutation));
      assertTrue(blocked.getCause() instanceof SQLException);
      assertTrue(
          blocked.getCause().getMessage().contains("immutable source identity"),
          blocked.getCause().getMessage());
      assertEquals(
          before, scope(fixture, before.selection()), "The normal SQL guard preserves identity");
      // Simulate an offline damaged restore only in this synthetic temporary database. The
      // original guard refusal above is part of the contract; every other guard/CHECK stays active.
      ordinarySql(fixture.store, "DROP TRIGGER immutable_identity");
      ordinarySql(fixture.store, mutation);
      assertEquals(
          "not_found",
          assertThrows(ApplicationException.class, () -> scope(fixture, before.selection()))
              .code());
      assertFalse(
          fixture.store.transaction(
              () ->
                  fixture.repository.current(
                      before, SoundTestFixture.TARGET, SoundTestFixture.PROFILE)));
      assertTrue(
          fixture
              .store
              .transaction(() -> fixture.repository.findOriginal(SoundTestFixture.OWNER, "library"))
              .isEmpty());
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"foreign-publication", "duplicate-proof", "changed-model", "uncited-withdrawn"})
  void traceSealRejectsProofsOutsideTheExactCurrentAuthority(String defect) {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("cited", SoundTestFixture.pcm(2, 1), true);
      fixture.register("uncited", SoundTestFixture.pcm(2, 2), true);
      var both = scope(fixture, DocumentSelection.allDocuments());
      var citedOnly = scope(fixture, DocumentSelection.selected(List.of("cited")));
      var source =
          new SoundPublishedSpan(
              fixture.publications.getFirst(), fixture.publications.getFirst().spans().getFirst());
      var proof = proof(source);
      var chosenScope = defect.equals("foreign-publication") ? citedOnly : both;
      List<SoundProof> proofs = List.of(proof);
      String model = SoundTestFixture.MODEL;
      switch (defect) {
        case "foreign-publication" ->
            proofs =
                List.of(
                    proof(
                        new SoundPublishedSpan(
                            fixture.publications.getLast(),
                            fixture.publications.getLast().spans().getFirst())));
        case "duplicate-proof" -> proofs = List.of(proof, proof);
        case "changed-model" -> model = "other-analysis-model";
        case "uncited-withdrawn" ->
            fixture.sql(
                "INSERT INTO document_tombstones SELECT id,workspace_id,'owner','2026-10-08T00:00:00Z' FROM documents WHERE id='uncited'");
        default -> throw new AssertionError(defect);
      }
      var draft =
          new SoundTraceDraft(
              SoundProofIdentity.sha(SoundTestFixture.QUESTION),
              SoundProofIdentity.sha(SoundTestFixture.FACT),
              "answered",
              null,
              proofs,
              model,
              SoundAnswerService.POLICY_REVISION);
      assertThrows(
          ApplicationException.class,
          () -> fixture.store.transaction(() -> fixture.repository.finish(chosenScope, draft)));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_trace_documents"));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_trace_evidence"));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void aSourceReadRevalidatesEveryProofIncludingAnUnrequestedSibling() {
    try (var fixture = new SoundTestFixture(directory)) {
      fixture.register("first", SoundTestFixture.pcm(2, 1), true);
      fixture.register("second", SoundTestFixture.pcm(2, 2), true);
      var scope = scope(fixture, DocumentSelection.allDocuments());
      var proofs =
          fixture.publications.stream()
              .map(
                  publication ->
                      proof(new SoundPublishedSpan(publication, publication.spans().getFirst())))
              .toList();
      var draft =
          new SoundTraceDraft(
              SoundProofIdentity.sha(SoundTestFixture.QUESTION),
              SoundProofIdentity.sha(SoundTestFixture.FACT),
              "answered",
              null,
              proofs,
              SoundTestFixture.MODEL,
              SoundAnswerService.POLICY_REVISION);
      var receipt = fixture.store.transaction(() -> fixture.repository.finish(scope, draft));
      assertEquals(
          "second",
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.source(
                          SoundTestFixture.OWNER,
                          receipt.traceId(),
                          2,
                          SoundTestFixture.TARGET,
                          SoundTestFixture.PROFILE))
              .proof()
              .source()
              .publication()
              .documentId());
      corrupt(
          fixture,
          "sound_trace_evidence",
          "UPDATE sound_trace_evidence SET proof_sha256='" + "0".repeat(64) + "' WHERE ordinal=2");
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () ->
                      fixture.repository.source(
                          SoundTestFixture.OWNER,
                          receipt.traceId(),
                          1,
                          SoundTestFixture.TARGET,
                          SoundTestFixture.PROFILE)));
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.textEmbeds);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "scope-count",
        "scope-order",
        "evidence-count",
        "evidence-order",
        "proof-sha",
        "answer-sha",
        "facts-number",
        "facts-whitespace",
        "span-count",
        "publication-sha",
        "publication-filename",
        "publication-media",
        "publication-size"
      })
  void damagedPersistedSourceCannotBeReleasedEvenWithCurrentReadPermission(String corruption) {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", SoundTestFixture.pcm(2, 1), true);
      var result = answers.answer(SoundTestFixture.OWNER, command());
      int decodes = fixture.decodes, embeddings = fixture.textEmbeds;
      switch (corruption) {
        case "scope-count" ->
            corrupt(fixture, "sound_traces", "UPDATE sound_traces SET scope_count=scope_count+1");
        case "scope-order" ->
            corrupt(fixture, "sound_trace_documents", "UPDATE sound_trace_documents SET ordinal=1");
        case "evidence-count" ->
            corrupt(fixture, "sound_traces", "UPDATE sound_traces SET citation_count=2");
        case "evidence-order" ->
            corrupt(fixture, "sound_trace_evidence", "UPDATE sound_trace_evidence SET ordinal=2");
        case "proof-sha" ->
            corrupt(
                fixture,
                "sound_trace_evidence",
                "UPDATE sound_trace_evidence SET proof_sha256='" + "0".repeat(64) + "'");
        case "answer-sha" ->
            corrupt(
                fixture,
                "sound_traces",
                "UPDATE sound_traces SET answer_sha256='" + "0".repeat(64) + "'");
        case "facts-number" ->
            corrupt(
                fixture,
                "sound_trace_evidence",
                "UPDATE sound_trace_evidence SET facts_json='[1]'");
        case "facts-whitespace" ->
            corrupt(
                fixture,
                "sound_trace_evidence",
                "UPDATE sound_trace_evidence SET facts_json='[ \""
                    + SoundTestFixture.FACT
                    + "\" ]'");
        case "span-count" ->
            corrupt(fixture, "sound_publications", "UPDATE sound_publications SET span_count=2");
        case "publication-sha" ->
            corrupt(
                fixture,
                "sound_publications",
                "UPDATE sound_publications SET source_sha256='" + "0".repeat(64) + "'");
        case "publication-filename" ->
            corrupt(
                fixture,
                "sound_publications",
                "UPDATE sound_publications SET filename='other.wav'");
        case "publication-media" ->
            corrupt(
                fixture,
                "sound_publications",
                "UPDATE sound_publications SET media_type='audio/flac'");
        case "publication-size" ->
            corrupt(
                fixture,
                "sound_publications",
                "UPDATE sound_publications SET size_bytes=size_bytes+2");
        default -> throw new AssertionError(corruption);
      }
      assertThrows(
          ApplicationException.class,
          () -> answers.source(SoundTestFixture.OWNER, result.answerId(), 1));
      assertThrows(
          ApplicationException.class,
          () -> answers.content(SoundTestFixture.OWNER, result.answerId(), 1));
      assertEquals(decodes, fixture.decodes);
      assertEquals(embeddings, fixture.textEmbeds);
      assertEquals(1, fixture.count("SELECT count(*) FROM sound_traces"));
    }
  }

  @Test
  void citationOrdinalAndWorkspaceCannotBeChangedOnAnExistingSourceUrl() {
    try (var fixture = new SoundTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", SoundTestFixture.pcm(2, 1), true);
      var result = answers.answer(SoundTestFixture.OWNER, command());
      assertThrows(
          ApplicationException.class,
          () -> answers.source(SoundTestFixture.OWNER, result.answerId(), 0));
      assertThrows(
          ApplicationException.class,
          () -> answers.source(SoundTestFixture.OWNER, result.answerId(), 33));
      assertThrows(
          ApplicationException.class,
          () -> answers.source(new Actor("other-workspace", "owner"), result.answerId(), 1));
      assertEquals(1, fixture.decodes);
      assertEquals(1, fixture.textEmbeds);
    }
  }

  private static SoundProof proof(SoundPublishedSpan source) {
    return SoundProofBinding.create(
        SoundProofIdentity.sha(SoundTestFixture.QUESTION),
        source,
        List.of(SoundTestFixture.FACT),
        SoundTestFixture.MODEL,
        SoundAnswerService.POLICY_REVISION);
  }

  private static SoundScope scope(SoundTestFixture fixture, DocumentSelection selection) {
    return fixture.store.transaction(
        () ->
            fixture.repository.scope(
                SoundTestFixture.OWNER,
                selection,
                SoundTestFixture.TARGET,
                SoundTestFixture.PROFILE));
  }

  private static AnswerCommand command() {
    return new AnswerCommand(
        SoundTestFixture.QUESTION, DocumentSelection.selected(List.of("library")));
  }

  private static void corrupt(SoundTestFixture fixture, String table, String sql) {
    // Only this fresh synthetic @TempDir database: emulate an offline damaged restore, not an
    // authorized write. Normal immutability and CHECK guards remain covered by schema tests.
    var blocked = assertThrows(AssertionError.class, () -> ordinarySql(fixture.store, sql));
    assertTrue(blocked.getCause() instanceof SQLException);
    assertFalse(blocked.getCause().getMessage().contains("no such function"));
    ordinarySql(fixture.store, "DROP TRIGGER " + table + "_no_update");
    if (table.equals("sound_trace_evidence") && sql.contains("facts_json=")) {
      ordinarySql(fixture.store, "DROP TRIGGER cleanup_sound_trace_evidence_purge");
    }
    ordinarySql(fixture.store, sql);
  }

  private static void ordinarySql(SqliteAuthorityStore store, String sql) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.libraryPath())) {
      // This direct connection has no authorized replacement transaction. Register the v26
      // predicate as false so the actual immutable-identity guard rejects ordinary writes.
      Function.create(
          connection,
          "java_replacement_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
      Function.create(
          connection,
          "java_cleanup_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
      try (var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    } catch (SQLException failure) {
      throw new AssertionError(failure);
    }
  }
}
