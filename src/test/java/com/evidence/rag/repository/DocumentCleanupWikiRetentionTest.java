package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.WikiContent;
import com.evidence.rag.model.domain.WikiPageRevision;
import com.evidence.rag.model.domain.WikiProposal;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentCleanupWikiRetentionTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"current", "history", "proposal_before", "proposal_after"})
  void retainedWikiContentBlocksCompletionWithoutBlockingActualOriginalCleanup(String location) {
    String documentId;
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      documentId = parsed(authority, "original synthetic payload for cleanup");
      String otherDocument = parsed(authority, "unrelated original must remain");
      seed(store, OWNER, documentId, otherDocument, location);
      var service = service(store);
      assertEquals("pending", service.request(OWNER, documentId).cleanupStatus());
      assertTrue(service.runOnce());
      var result = service.status(OWNER, documentId);
      assertEquals("blocked", result.cleanupStatus());
      assertEquals("cleanup_wiki_content_retained", result.errorCode());
      assertNull(result.completedAt());
      assertEquals(
          "blocked",
          result.resources().stream()
              .filter(resource -> resource.kind().equals("database_payload"))
              .findFirst()
              .orElseThrow()
              .status());
      assertTrue(
          result.resources().stream()
              .filter(resource -> !resource.kind().equals("database_payload"))
              .allMatch(
                  resource -> List.of("completed", "not_applicable").contains(resource.status())));
      store.transaction(
          () -> {
            assertEquals(0, new IngestionRepository(store).original(documentId).length);
            assertTrue(new IngestionRepository(store).original(otherDocument).length > 0);
            assertEquals(
                "retained derived synthetic body",
                new WikiWorkspaceRepository(store)
                    .version(OWNER, "retained-page", 1)
                    .orElseThrow()
                    .content()
                    .sections()
                    .getFirst()
                    .body());
            return null;
          });
      assertFalse(service.runOnce());
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals("blocked", service(store).status(OWNER, documentId).cleanupStatus());
      assertEquals(
          "cleanup_wiki_content_retained", service(store).status(OWNER, documentId).errorCode());
    }
  }

  @Test
  void unrelatedOrAnotherOrganizationsWikiDoesNotBlockLocalCleanup() {
    try (var authority = new AuthorityTestContext(directory)) {
      String documentId = parsed(authority, "isolated original");
      seed(authority.store(), new Actor("other-org", "member"), documentId, "unrelated", "current");
      seed(authority.store(), OWNER, documentId + "-suffix", "unrelated", "current");
      var service = service(authority.store());
      service.request(OWNER, documentId);
      assertTrue(service.runOnce());
      assertEquals("completed", service.status(OWNER, documentId).cleanupStatus());
    }
  }

  private void seed(
      SqliteAuthorityStore store,
      Actor actor,
      String documentId,
      String otherDocument,
      String location) {
    store.transaction(
        () -> {
          var repository = new WikiWorkspaceRepository(store);
          var related = content(documentId);
          var unrelated = content(otherDocument);
          var initial = List.of("current", "history").contains(location) ? related : unrelated;
          repository.saveRevision(
              actor,
              new WikiPageRevision(
                  "retained-page", 1, initial, "fixture-model-v1", "fixture-wiki-v1", 1),
              0);
          if (location.equals("history")) {
            repository.saveRevision(
                actor,
                new WikiPageRevision(
                    "retained-page", 2, unrelated, "fixture-model-v1", "fixture-wiki-v1", 2),
                1);
          }
          if (location.startsWith("proposal_")) {
            repository.insertProposal(
                actor,
                new WikiProposal(
                    "retained-proposal",
                    "retained-page",
                    1,
                    location.equals("proposal_before") ? related : unrelated,
                    location.equals("proposal_after") ? related : unrelated,
                    "extractive",
                    "fixture-model-v1",
                    "fixture-wiki-v1",
                    "pending",
                    3,
                    null));
            repository.review(actor, "retained-proposal", "dismissed", 4);
          }
          return null;
        });
  }

  private WikiContent content(String documentId) {
    var initial = WikiWorkspaceRepositoryTest.content("保留知识页", "retained derived synthetic body");
    var section = initial.sections().getFirst();
    var source = section.sources().getFirst();
    var publication = source.publication();
    var rebound =
        new PublicationVersion(
            documentId,
            publication.publicationId(),
            publication.sourceRevisionId(),
            publication.projectionGenerationId(),
            publication.sourceSha256(),
            publication.parserRevision(),
            publication.target(),
            publication.manifestSha256(),
            publication.segmentCount());
    return new WikiContent(
        initial.title(),
        initial.kind(),
        List.of(
            new WikiContent.Section(
                section.id(),
                section.heading(),
                section.body(),
                List.of(new WikiContent.Source(source.id(), rebound, source.reference())))));
  }

  private String parsed(AuthorityTestContext authority, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    var task = authority.ingestion().uploadDocument(OWNER, "fixture.txt", "text/plain", bytes);
    var claim = authority.ingestion().claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(claim, new TextParser().parse("fixture.txt", "text/plain", bytes)));
    return task.documentId();
  }

  private DocumentCleanupService service(SqliteAuthorityStore store) {
    return new DocumentCleanupService(
        store,
        new DocumentCleanupRepository(store),
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        attempts -> {
          throw new AssertionError("Local cleanup must not call a provider");
        });
  }
}
