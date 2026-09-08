package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static com.evidence.rag.support.PublishedCorpusFixture.physicalIds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceTraceSealTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("org", "owner");

  @Test
  void committedTraceRejectsNewNonduplicateScopeAndCitationChildren() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var first = fixture.publish(owner, "Original source.");
      var second = fixture.publish(owner, "Other source.");
      var scope =
          fixture.evidence.snapshot(
              owner, DocumentSelection.selected(List.of(first.documentId())), TARGET);
      var receipt =
          fixture.evidence.finish(
              scope, answered(physicalIds(first).getFirst()), () -> AnswerEligibility.ELIGIBLE);
      try (var db =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = db.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
        try (var insert =
            db.prepareStatement(
                "INSERT INTO query_trace_documents(trace_id,ordinal,publication_id) SELECT ?,1,id FROM index_publications WHERE document_id=?")) {
          insert.setString(1, receipt.traceId());
          insert.setString(2, second.documentId());
          assertThrows(SQLException.class, insert::executeUpdate);
        }
        assertThrows(
            SQLException.class,
            () ->
                statement.executeUpdate(
                    """
            INSERT INTO query_trace_evidence(trace_id,citation_ordinal,publication_id,source_segment_id,
              physical_segment_id,page_number,start_offset,end_offset,text_sha256,page_sha256,quote_sha256,
              retrieval_score,rerank_score,fact_sha256)
            SELECT trace_id,2,publication_id,source_segment_id,physical_segment_id,page_number,start_offset,
              end_offset,text_sha256,page_sha256,quote_sha256,retrieval_score,rerank_score,fact_sha256
            FROM query_trace_evidence
            """));
        try (var count = statement.executeQuery("SELECT COUNT(*) FROM query_trace_documents")) {
          assertTrue(count.next());
          assertEquals(1, count.getInt(1));
        }
      }
      assertEquals(
          first.documentId(),
          fixture
              .evidence
              .source(owner, receipt.traceId(), 1)
              .evidence()
              .publication()
              .documentId());
    }
  }

  @Test
  void insertOrReplaceCannotBypassImmutableTraceHeaderEvenWithoutRecursiveTriggers()
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var publication = fixture.publish(owner, "Original source.");
      var scope = fixture.evidence.snapshot(owner, DocumentSelection.allDocuments(), TARGET);
      var receipt =
          fixture.evidence.finish(
              scope,
              answered(physicalIds(publication).getFirst()),
              () -> AnswerEligibility.ELIGIBLE);
      try (var db =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = db.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
        statement.execute("PRAGMA recursive_triggers=OFF");
        assertThrows(
            SQLException.class,
            () ->
                statement.executeUpdate(
                    "INSERT OR REPLACE INTO query_traces SELECT * FROM query_traces"));
      }
      assertEquals(0, fixture.evidence.source(owner, receipt.traceId(), 1).start());
    }
  }

  private static TraceDraft answered(String physicalId) {
    String hash = ModelValues.sha256("fixture".getBytes(StandardCharsets.UTF_8));
    return new TraceDraft(
        hash,
        hash,
        "answered",
        null,
        "models-v1",
        "prompt-v1",
        "policy-v1",
        List.of(new TraceEvidence(1, physicalId, 0, 8, 0.5, -2, List.of(hash))));
  }
}
