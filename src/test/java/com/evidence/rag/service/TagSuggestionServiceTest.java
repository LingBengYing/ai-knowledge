package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.SynopsisClaim;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.model.dto.TagSuggestionApplyCommand;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real authority transactions and synthetic persisted summaries; no model adapter is invoked. */
class TagSuggestionServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org-main", "owner");
  private final AtomicReference<String> model = new AtomicReference<>("synopsis-fixture-v1");
  private final AtomicInteger revisionReads = new AtomicInteger();

  @Test
  void readingDoesNotWriteAndExplicitMergePreservesEvidenceFilterAndRestart() {
    String documentId;
    String fingerprint;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      documentId = fixture.publish(owner, "设备采用太阳能，预算已确认。").documentId();
      var library = library(fixture.authority.store());
      available(library, documentId, "设备", "太阳能");
      var service = service(fixture, library);
      var before = manualTags(fixture, documentId, List.of("人工"));
      var auditBefore = fixture.authority.management().auditEvents(owner);

      var result = service.get(owner, documentId);
      fingerprint = result.suggestions().suggestionFingerprint();
      assertEquals(List.of("人工"), result.existingTags());
      assertTrue(result.canApply());
      assertEquals(
          List.of("太阳能", "设备"),
          result.suggestions().candidates().stream().map(candidate -> candidate.tag()).toList());
      assertEquals(before, document(fixture, documentId));
      assertEquals(auditBefore, fixture.authority.management().auditEvents(owner));

      var saved =
          service.apply(
              owner, documentId, new TagSuggestionApplyCommand(fingerprint, List.of(1, 2)));
      assertEquals(List.of("人工", "太阳能", "设备"), saved.tags());
      assertEquals(before.activeRevisionId(), saved.activeRevisionId());
      assertEquals(before.registeredRevisionId(), saved.registeredRevisionId());
      assertEquals(before.indexPublicationId(), saved.indexPublicationId());
      assertEquals(before.sha256(), saved.sha256());
      assertEquals(before.filename(), saved.filename());
      assertEquals(before.segmentCount(), saved.segmentCount());
      assertEquals(
          1, fixture.authority.management().auditEvents(owner).size() - auditBefore.size());
      assertEquals(
          fingerprint, service.get(owner, documentId).suggestions().suggestionFingerprint());
      assertEquals(1, fixture.authority.management().listDocuments(owner, query("太阳能")).total());
      assertEquals(
          saved.tags(),
          service
              .apply(owner, documentId, new TagSuggestionApplyCommand(fingerprint, List.of(1)))
              .tags());
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var service = service(fixture, library(fixture.authority.store()));
      var result = service.get(owner, documentId);
      assertEquals(fingerprint, result.suggestions().suggestionFingerprint());
      assertEquals(List.of("人工", "太阳能", "设备"), result.existingTags());
    }
  }

  @Test
  void confirmationMergesTheLatestTagsAndExistingSelectionIsIdempotent() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      available(library, documentId, "设备", "太阳能");
      var service = service(fixture, library);
      var preview = service.get(owner, documentId);
      manualTags(fixture, documentId, List.of("后来添加", "太阳能"));
      var updated = service.get(owner, documentId);
      assertEquals(preview.suggestions(), updated.suggestions());
      assertEquals(List.of("后来添加", "太阳能"), updated.existingTags());
      var saved =
          service.apply(
              owner,
              documentId,
              new TagSuggestionApplyCommand(
                  preview.suggestions().suggestionFingerprint(), List.of(1, 2)));
      assertEquals(List.of("后来添加", "太阳能", "设备"), saved.tags());
    }
  }

  @Test
  void readersMayReadButApplyChecksCurrentWritePermissionBeforeSynopsis() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      available(library, documentId, "设备", "太阳能");
      var service = service(fixture, library);
      var reader = new Actor(owner.workspaceId(), "reader");
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                new ManagementRepository(fixture.authority.store())
                    .insertGrant(documentId, reader.principalId(), "reader");
                return null;
              });
      var read = service.get(reader, documentId);
      assertFalse(read.canApply());
      var command =
          new TagSuggestionApplyCommand(read.suggestions().suggestionFingerprint(), List.of(1));
      revisionReads.set(0);
      assertCode("not_found", () -> service.apply(reader, documentId, command));
      assertEquals(0, revisionReads.get());
      assertCode(
          "not_found", () -> service.get(new Actor("other", reader.principalId()), documentId));
      sql(
          "UPDATE document_acl SET role='reader' WHERE document_id=? AND principal_id=?",
          documentId,
          owner.principalId());
      revisionReads.set(0);
      assertCode("not_found", () -> service.apply(owner, documentId, command));
      assertEquals(0, revisionReads.get());
      assertEquals(List.of(), service.get(owner, documentId).existingTags());
      sql(
          "DELETE FROM document_acl WHERE document_id=? AND principal_id=?",
          documentId,
          owner.principalId());
      assertCode("not_found", () -> service.get(owner, documentId));
    }
  }

  @Test
  void unavailableOrChangedModelAndReplacedSynopsisRejectEarlierSelection() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      var service = service(fixture, library);
      assertCode("not_found", () -> service.get(owner, documentId));
      available(library, documentId, "设备", "太阳能");
      var original = service.get(owner, documentId);
      var command =
          new TagSuggestionApplyCommand(original.suggestions().suggestionFingerprint(), List.of(1));
      var before = document(fixture, documentId);
      model.set("synopsis-fixture-v2");
      assertCode("not_found", () -> service.get(owner, documentId));
      assertCode("not_found", () -> service.apply(owner, documentId, command));
      available(library, documentId, "设备", "太阳能");
      var current = service.get(owner, documentId);
      assertNotEquals(original.suggestions().synopsisId(), current.suggestions().synopsisId());
      assertNotEquals(
          original.suggestions().suggestionFingerprint(),
          current.suggestions().suggestionFingerprint());
      assertCode("tag_suggestions_changed", () -> service.apply(owner, documentId, command));
      assertEquals(before, document(fixture, documentId));
    }
  }

  @Test
  void fingerprintCannotBeMovedToAnotherPublicationAndUnknownOrdinalsDoNotWrite() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String first = fixture.publish(owner, "设备采用太阳能。").documentId();
      String second = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      available(library, first, "设备", "太阳能");
      available(library, second, "设备", "太阳能");
      var service = service(fixture, library);
      var initial = service.get(owner, first);
      var secondBefore = document(fixture, second);
      var command =
          new TagSuggestionApplyCommand(initial.suggestions().suggestionFingerprint(), List.of(1));
      assertCode("tag_suggestions_changed", () -> service.apply(owner, second, command));
      assertEquals(secondBefore, document(fixture, second));
      var firstBefore = document(fixture, first);
      var auditBefore = fixture.authority.management().auditEvents(owner);
      assertCode(
          "invalid_request",
          () ->
              service.apply(
                  owner,
                  first,
                  new TagSuggestionApplyCommand(
                      initial.suggestions().suggestionFingerprint(), List.of(3))));
      assertCode("invalid_request", () -> service.apply(owner, first, null));
      assertEquals(firstBefore, document(fixture, first));
      assertEquals(auditBefore, fixture.authority.management().auditEvents(owner));
    }
  }

  @Test
  void exceedingTwentyMergedTagsRollsBackMetadataAndAudit() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      available(library, documentId, "设备", "太阳能");
      var service = service(fixture, library);
      var initial = service.get(owner, documentId);
      var before =
          manualTags(fixture, documentId, IntStream.range(0, 19).mapToObj(i -> "手工" + i).toList());
      var auditBefore = fixture.authority.management().auditEvents(owner);
      assertCode(
          "tag_limit_reached",
          () ->
              service.apply(
                  owner,
                  documentId,
                  new TagSuggestionApplyCommand(
                      initial.suggestions().suggestionFingerprint(), List.of(1, 2))));
      assertEquals(before, document(fixture, documentId));
      assertEquals(auditBefore, fixture.authority.management().auditEvents(owner));
    }
  }

  @Test
  void auditFailureRollsBackTheWholeMerge() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      available(library, documentId, "设备", "太阳能");
      var service = service(fixture, library);
      var initial = service.get(owner, documentId);
      var before = document(fixture, documentId);
      var auditBefore = fixture.authority.management().auditEvents(owner);
      sql(
          "CREATE TRIGGER reject_tag_audit BEFORE INSERT ON management_audit BEGIN SELECT RAISE(ABORT,'synthetic_failure'); END");
      assertThrows(
          ApplicationException.class,
          () ->
              service.apply(
                  owner,
                  documentId,
                  new TagSuggestionApplyCommand(
                      initial.suggestions().suggestionFingerprint(), List.of(1))));
      assertEquals(before, document(fixture, documentId));
      assertEquals(auditBefore, fixture.authority.management().auditEvents(owner));
      sql("DROP TRIGGER reject_tag_audit");
      assertEquals(
          List.of("太阳能"),
          service
              .apply(
                  owner,
                  documentId,
                  new TagSuggestionApplyCommand(
                      initial.suggestions().suggestionFingerprint(), List.of(1)))
              .tags());
    }
  }

  @Test
  void completeLongEntriesProduceNoShortenedCandidatesOrTagWrites() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String documentId = fixture.publish(owner, "设备采用太阳能。").documentId();
      var library = library(fixture.authority.store());
      available(library, documentId, "主题".repeat(21), "术语".repeat(21));
      var service = service(fixture, library);
      var before = document(fixture, documentId);
      var result = service.get(owner, documentId);
      assertTrue(result.suggestions().candidates().isEmpty());
      assertCode(
          "invalid_request",
          () ->
              service.apply(
                  owner,
                  documentId,
                  new TagSuggestionApplyCommand(
                      result.suggestions().suggestionFingerprint(), List.of(1))));
      assertEquals(before, document(fixture, documentId));
    }
  }

  private SynopsisLibraryService library(SqliteAuthorityStore store) {
    return new SynopsisLibraryService(
        store,
        new SynopsisRepository(store),
        new SynopsisMaterialRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        () -> {
          revisionReads.incrementAndGet();
          return model.get();
        });
  }

  private static TagSuggestionService service(
      PublishedCorpusFixture fixture, SynopsisLibraryService library) {
    return new TagSuggestionService(
        fixture.authority.store(), library, fixture.authority.management());
  }

  private void available(
      SynopsisLibraryService library, String documentId, String topic, String term) {
    library.create(owner, documentId);
    var claim = library.claim(owner.workspaceId()).orElseThrow();
    assertTrue(library.complete(claim, output(claim, topic, term)));
  }

  private static FileSynopsis output(SynopsisClaim claim, String topic, String term) {
    var source = claim.input().evidence().getFirst();
    var reference =
        new FileSynopsis.Reference(source.id(), source.sha256(), source.kind(), source.time());
    var entries =
        List.of(
            new FileSynopsis.Entry(
                new SynopsisDraft.Item(
                    SynopsisDraft.Section.OVERVIEW, "设备摘要", List.of(source.id())),
                List.of(reference),
                null),
            new FileSynopsis.Entry(
                new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, topic, List.of(source.id())),
                List.of(reference),
                null),
            new FileSynopsis.Entry(
                new SynopsisDraft.Item(SynopsisDraft.Section.TERM, term, List.of(source.id())),
                List.of(reference),
                null));
    return new FileSynopsis(
        claim.publication(),
        claim.input().fingerprint(),
        claim.modelRevision(),
        claim.policyRevision(),
        entries,
        null);
  }

  private DocumentResult manualTags(
      PublishedCorpusFixture fixture, String documentId, List<String> tags) {
    return fixture
        .authority
        .management()
        .updateDocument(
            owner, documentId, new DocumentPatchCommand(false, null, false, null, true, tags));
  }

  private DocumentResult document(PublishedCorpusFixture fixture, String documentId) {
    return fixture.authority.management().listDocuments(owner, query(null)).items().stream()
        .filter(document -> document.documentId().equals(documentId))
        .findFirst()
        .orElseThrow();
  }

  private static DocumentQuery query(String tag) {
    return new DocumentQuery("", null, null, null, tag, "updated_desc", 1, 100);
  }

  private static void assertCode(String code, Runnable action) {
    assertEquals(code, assertThrows(ApplicationException.class, action::run).code());
  }

  private void sql(String statement, String... parameters) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var command = connection.prepareStatement(statement)) {
      for (int index = 0; index < parameters.length; index++) {
        command.setString(index + 1, parameters[index]);
      }
      command.executeUpdate();
    }
  }
}
