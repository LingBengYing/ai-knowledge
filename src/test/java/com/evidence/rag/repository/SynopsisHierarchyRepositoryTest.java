package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SynopsisHierarchyRepositoryTest {
  @TempDir Path directory;

  @Test
  void wholeLongPublicationPersistsBeyondSixtyFourInputsAndRetainsLastLeafSourceAfterReopen() {
    FileSynopsis expected;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var claim =
          fixture.publish(SynopsisRepositoryTest.OWNER, "前文😀预算42。".repeat(13000) + "尾部结论85万元。");
      var publication =
          fixture
              .evidence
              .snapshot(
                  SynopsisRepositoryTest.OWNER,
                  DocumentSelection.allDocuments(),
                  PublishedCorpusFixture.TARGET)
              .publications()
              .getFirst();
      var physicalIds = PublishedCorpusFixture.physicalIds(claim);
      var evidence = new ArrayList<SynopsisEvidence>();
      for (int ordinal = 0; ordinal < claim.items().size(); ordinal++) {
        evidence.add(
            new SynopsisEvidence(
                physicalIds.get(ordinal),
                SynopsisEvidence.Kind.TEXT,
                new SynopsisEvidence.Text(claim.items().get(ordinal).recallText()),
                null));
      }
      var input = new SynopsisFileInput(publication, evidence);
      assertTrue(input.evidence().size() > 64);
      assertTrue(
          ((SynopsisEvidence.Text) input.evidence().getLast().content())
              .text()
              .contains("尾部结论85万元"));
      var leaf = input.evidence().getLast();
      var reference =
          new FileSynopsis.Reference(leaf.id(), leaf.sha256(), leaf.kind(), leaf.time());
      var entries =
          List.of(
                  SynopsisDraft.Section.OVERVIEW,
                  SynopsisDraft.Section.TOPIC,
                  SynopsisDraft.Section.TERM)
              .stream()
              .map(
                  section ->
                      new FileSynopsis.Entry(
                          new SynopsisDraft.Item(section, "尾部结论85万元。", List.of(leaf.id())),
                          List.of(reference),
                          null))
              .toList();
      expected =
          new FileSynopsis(
              publication,
              input.fingerprint(),
              SynopsisRepositoryTest.MODEL,
              SynopsisRepositoryTest.POLICY,
              entries,
              null);
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                var repository = new SynopsisRepository(fixture.authority.store());
                repository.insertTask(
                    "long-file",
                    SynopsisRepositoryTest.OWNER,
                    publication,
                    SynopsisRepositoryTest.MODEL,
                    SynopsisRepositoryTest.POLICY,
                    SynopsisRepositoryTest.NOW);
                assertTrue(
                    repository.markProcessing(
                        "long-file",
                        SynopsisRepositoryTest.CLAIM_HASH,
                        input,
                        SynopsisRepositoryTest.NOW));
                assertTrue(
                    repository.complete(
                        "long-file",
                        SynopsisRepositoryTest.CLAIM_HASH,
                        expected,
                        SynopsisRepositoryTest.NOW));
                assertEquals(expected, repository.findSynopsis("long-file").orElseThrow());
                assertEquals(
                    publication.segmentCount(),
                    fixture
                        .authority
                        .store()
                        .count(
                            "SELECT COUNT(*) FROM synopsis_input_evidence WHERE task_id='long-file'"));
                return null;
              });
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                expected, new SynopsisRepository(store).findSynopsis("long-file").orElseThrow());
            var source =
                new SynopsisMaterialRepository(store)
                    .source(
                        SynopsisRepositoryTest.OWNER,
                        expected.publication(),
                        expected.entries().getFirst().evidence().getFirst());
            assertTrue(
                ((SynopsisEvidence.Text) source.evidence().content()).text().contains("尾部结论85万元"));
            return null;
          });
    }
  }
}
