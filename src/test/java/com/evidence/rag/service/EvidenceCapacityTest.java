package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static com.evidence.rag.support.PublishedCorpusFixture.physicalIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceCapacityTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void distinctAuthorityPagesOverEightMiBUtf8FailWholeHydration() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ids = new ArrayList<String>();
      for (int index = 0; index < 3; index++) {
        var publication = fixture.publish(owner, "汉".repeat(950_000));
        ids.add(physicalIds(publication).getFirst());
      }
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var error =
          assertThrows(ApplicationException.class, () -> fixture.evidence.hydrate(scope, ids));
      assertEquals(FailureKind.CAPACITY_EXCEEDED, error.kind());
      assertEquals("evidence_capacity_exceeded", error.code());
    }
  }

  @Test
  void severalCandidatesFromSamePageCountPageBytesOnlyOnce() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var publication = fixture.publish(owner, "汉".repeat(750_000));
      var candidates = physicalIds(publication).subList(0, 4);
      var scope =
          fixture.evidence.snapshot(
              owner, DocumentSelection.selected(List.of(publication.documentId())), TARGET);
      var hydrated = fixture.evidence.hydrate(scope, candidates);
      assertEquals(4, hydrated.size());
      assertEquals(candidates, hydrated.stream().map(item -> item.physicalSegmentId()).toList());
      assertEquals(1, hydrated.stream().map(item -> item.page()).distinct().count());
    }
  }
}
