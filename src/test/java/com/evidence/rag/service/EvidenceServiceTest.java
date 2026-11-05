package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");
  private static final String TEXT = "😀退货政策：签收后14天内可以退货。";

  @Test
  void allUsesEveryCurrentWorkspacePublicationAndGenerationNotSourceRevision() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var visible = fixture.publish(owner, TEXT);
      fixture.publish(new Actor("other", "owner"), "Foreign corpus.");
      fixture.publish(new Actor("org", "someone-else"), "Shared corpus.");
      fixture
          .authority
          .ingestion()
          .uploadDocument(owner, "queued.txt", "text/plain", new byte[] {65});
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      assertEquals(2, scope.publications().size());
      var binding =
          scope.publications().stream()
              .filter(item -> item.documentId().equals(visible.documentId()))
              .findFirst()
              .orElseThrow();
      assertEquals(visible.documentId(), binding.documentId());
      assertEquals(visible.revisionId(), binding.sourceRevisionId());
      assertEquals(visible.projectionGenerationId(), binding.projectionGenerationId());
      assertNotEquals(binding.sourceRevisionId(), binding.projectionGenerationId());
      assertEquals(TARGET, binding.target());
    }
  }

  @Test
  void selectedSetFailsWholeForMissingForeignUnpublishedOrTargetMismatch() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var visible = fixture.publish(owner, TEXT);
      var privateDoc = fixture.publish(new Actor("foreign-org", "someone-else"), "Foreign.");
      var unpublished =
          fixture
              .authority
              .ingestion()
              .uploadDocument(owner, "queued.txt", "text/plain", new byte[] {65});
      for (String unavailable :
          List.of("missing", privateDoc.documentId(), unpublished.documentId())) {
        fails(
            FailureKind.NOT_FOUND,
            () ->
                fixture.evidence.snapshot(
                    owner,
                    DocumentSelection.selected(List.of(visible.documentId(), unavailable)),
                    TARGET));
      }
      fails(
          FailureKind.NOT_FOUND,
          () ->
              fixture.evidence.snapshot(
                  owner,
                  DocumentSelection.selected(List.of(visible.documentId())),
                  new IndexTarget(
                      "other-embed", TARGET.projectionIdentity(), TARGET.modelRevision(), 2)));
    }
  }

  @Test
  void workspaceLibraryBeyondOld128LimitRemainsComplete() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      for (int index = 0; index < 129; index++) {
        fixture.publish(owner, "Published " + index);
      }
      assertEquals(
          129,
          fixture
              .evidence
              .snapshot(owner, DocumentSelection.allDocuments(), TARGET)
              .publications()
              .size());
    }
  }

  private static ApplicationException fails(FailureKind expected, Runnable action) {
    var error = assertThrows(ApplicationException.class, action::run);
    assertEquals(expected, error.kind());
    return error;
  }
}
