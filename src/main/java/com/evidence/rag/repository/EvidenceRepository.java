package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.PublishedEvidence;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.entity.TraceCitationEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL boundary for active publication snapshots, source hydration and append-only query traces. */
@Repository
public final class EvidenceRepository {
  private static final String CURRENT_PUBLICATIONS =
      """
      FROM documents d
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN corpus_documents c ON c.document_id=d.id
      JOIN active_corpus_publications a ON a.document_id=d.id
      JOIN index_publications p ON p.id=a.publication_id AND p.document_id=d.id
        AND p.revision_id=a.revision_id
      JOIN corpus_revisions r ON r.id=p.revision_id AND r.document_id=d.id
      JOIN indexing_jobs j ON j.id=p.job_id AND j.state='indexed'
        AND j.attempt=p.attempt AND j.projection_generation_id=p.projection_generation_id
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        AND c.parsed_revision_id=r.id AND r.parsed_at IS NOT NULL
        AND d.source_sha256=p.source_sha256 AND r.source_sha256=p.source_sha256
        AND r.parser_revision=p.parser_revision AND r.segment_count=p.segment_count
        AND p.segment_count=(SELECT COUNT(*) FROM index_publication_entries e WHERE e.publication_id=p.id)
      """;
  private static final String PUBLISHED_SOURCES =
      """
      FROM index_publication_entries e
      JOIN index_publications p ON p.id=e.publication_id
      JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
      JOIN documents d ON d.id=p.document_id
      JOIN document_acl acl ON acl.document_id=d.id
      JOIN corpus_segments s ON s.id=e.source_segment_id AND s.revision_id=p.revision_id
      JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
      WHERE d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor','reader')
        AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      """;
  private final SqliteAuthorityStore store;

  public EvidenceRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  /** ACL filtering precedes the 129th-row capacity sentinel; no silent full-library truncation. */
  public List<PublicationVersion> findActivePublications(Actor actor, DocumentSelection selection) {
    if (!selection.all() && selection.documentIds().isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>(List.of(actor.workspaceId(), actor.principalId()));
    String selected = "";
    if (!selection.all()) {
      selected = " AND d.id IN (" + placeholders(selection.documentIds().size()) + ")";
      args.addAll(selection.documentIds());
    }
    return store
        .rows(
            "SELECT p.* " + CURRENT_PUBLICATIONS + selected + " ORDER BY d.id LIMIT 129",
            args.toArray())
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  /** Historical metadata is used to authenticate a revoked snapshot, never to hydrate its body. */
  public List<PublicationVersion> findHistoricalPublications(
      String workspaceId, List<String> publicationIds) {
    if (publicationIds.isEmpty()) {
      return List.of();
    }
    var args = new ArrayList<Object>();
    args.add(workspaceId);
    args.addAll(publicationIds);
    return store
        .rows(
            "SELECT p.* FROM index_publications p JOIN documents d ON d.id=p.document_id"
                + " WHERE d.workspace_id=? AND p.id IN ("
                + placeholders(publicationIds.size())
                + ")",
            args.toArray())
        .stream()
        .map(EvidenceRepository::publication)
        .toList();
  }

  /** Counts unique authority page bytes without returning page bodies to the JVM. */
  public long publishedPageBytes(EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return 0;
    }
    return store.count(
        "SELECT COALESCE(SUM(page_bytes),0) FROM ("
            + "SELECT DISTINCT p.id,pg.page_number,LENGTH(CAST(pg.text AS BLOB)) AS page_bytes "
            + sourceFilter(scope, physicalIds)
            + ")",
        sourceArguments(scope, physicalIds));
  }

  public List<PublishedEvidence> findPublishedEvidence(
      EvidenceScope scope, List<String> physicalIds) {
    if (physicalIds.isEmpty() || scope.publications().isEmpty()) {
      return List.of();
    }
    String filter = sourceFilter(scope, physicalIds);
    Object[] args = sourceArguments(scope, physicalIds);
    var segments =
        store.rows(
            """
        SELECT p.*, d.filename, e.physical_segment_id, e.entry_sha256,
          s.id AS source_segment_id,s.ordinal,s.page_number,s.start_offset,s.end_offset,
          s.text AS segment_text,s.text_sha256 AS segment_sha256,
          pg.text_sha256 AS page_sha256
        """
                + filter,
            args);
    var pages = new HashMap<PageKey, TextPage>();
    // One page body per immutable source page, even when many candidates share that page.
    for (var row :
        store.rows(
            "SELECT DISTINCT pg.revision_id,pg.page_number,pg.text AS page_text " + filter, args)) {
      pages.put(
          new PageKey(text(row, "revision_id"), integer(row, "page_number")),
          new TextPage(integer(row, "page_number"), text(row, "page_text")));
    }
    return segments.stream()
        .map(
            row ->
                new PublishedEvidence(
                    publication(row),
                    text(row, "physical_segment_id"),
                    text(row, "entry_sha256"),
                    new IndexSegment(
                        text(row, "source_segment_id"),
                        integer(row, "ordinal"),
                        integer(row, "page_number"),
                        integer(row, "start_offset"),
                        integer(row, "end_offset"),
                        text(row, "segment_text"),
                        text(row, "segment_sha256")),
                    pages.get(new PageKey(text(row, "revision_id"), integer(row, "page_number"))),
                    text(row, "page_sha256"),
                    text(row, "filename")))
        .toList();
  }

  private static String sourceFilter(EvidenceScope scope, List<String> physicalIds) {
    return PUBLISHED_SOURCES
        + " AND p.id IN ("
        + placeholders(scope.publications().size())
        + ")"
        + " AND e.physical_segment_id IN ("
        + placeholders(physicalIds.size())
        + ")";
  }

  private static Object[] sourceArguments(EvidenceScope scope, List<String> physicalIds) {
    var args =
        new ArrayList<Object>(List.of(scope.actor().workspaceId(), scope.actor().principalId()));
    args.addAll(scope.publications().stream().map(PublicationVersion::publicationId).toList());
    args.addAll(physicalIds);
    return args.toArray();
  }

  private record PageKey(String revisionId, int pageNumber) {}

  public void insertTrace(
      String traceId,
      EvidenceScope scope,
      TraceDraft draft,
      List<TraceCitationEntity> citations,
      String createdAt) {
    for (int index = 0; index < scope.publications().size(); index++) {
      store.execute(
          "INSERT INTO query_trace_documents(trace_id,ordinal,publication_id) VALUES(?,?,?)",
          traceId,
          index,
          scope.publications().get(index).publicationId());
    }
    for (var citation : citations) {
      var locator = citation.evidence();
      store.execute(
          """
          INSERT INTO query_trace_evidence(trace_id,citation_ordinal,publication_id,source_segment_id,
            physical_segment_id,page_number,start_offset,end_offset,text_sha256,page_sha256,quote_sha256,
            retrieval_score,rerank_score,fact_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          traceId,
          locator.citationOrdinal(),
          citation.publicationId(),
          citation.sourceSegmentId(),
          locator.physicalSegmentId(),
          citation.page(),
          locator.start(),
          locator.end(),
          citation.textSha256(),
          citation.pageSha256(),
          citation.quoteSha256(),
          locator.retrievalScore(),
          locator.rerankScore(),
          String.join(",", locator.factSha256()));
    }
    // Header seals all children. Deferred foreign keys make partial traces impossible to commit.
    store.execute(
        """
        INSERT INTO query_traces(id,workspace_id,actor_id,selection_all,scope_count,citation_count,
          question_sha256,answer_sha256,outcome,reason_code,model_revision,prompt_revision,policy_revision,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        traceId,
        scope.actor().workspaceId(),
        scope.actor().principalId(),
        scope.selection().all() ? 1 : 0,
        scope.publications().size(),
        citations.size(),
        draft.questionSha256(),
        draft.answerSha256(),
        draft.outcome(),
        draft.reasonCode(),
        draft.modelRevision(),
        draft.promptRevision(),
        draft.policyRevision(),
        createdAt);
  }

  /**
   * Only the trace's original actor may recover its frozen metadata. Current ACL is checked by
   * Service.
   */
  public EvidenceScope findTraceScope(Actor actor, String traceId) {
    var headers =
        store.rows(
            "SELECT selection_all FROM query_traces WHERE id=? AND workspace_id=? AND actor_id=? AND outcome='answered'",
            traceId,
            actor.workspaceId(),
            actor.principalId());
    if (headers.isEmpty()) {
      return null;
    }
    var publications =
        store
            .rows(
                "SELECT p.* FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id WHERE q.trace_id=? ORDER BY q.ordinal",
                traceId)
            .stream()
            .map(EvidenceRepository::publication)
            .toList();
    var selection =
        integer(headers.getFirst(), "selection_all") == 1
            ? DocumentSelection.allDocuments()
            : DocumentSelection.selected(
                publications.stream().map(PublicationVersion::documentId).toList());
    return new EvidenceScope(actor, selection, publications);
  }

  public TraceCitationEntity findTraceCitation(Actor actor, String traceId, int citationOrdinal) {
    var rows =
        store.rows(
            """
        SELECT e.* FROM query_trace_evidence e JOIN query_traces t ON t.id=e.trace_id
        WHERE t.id=? AND t.workspace_id=? AND t.actor_id=? AND t.outcome='answered' AND e.citation_ordinal=?
        """,
            traceId,
            actor.workspaceId(),
            actor.principalId(),
            citationOrdinal);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.getFirst();
    return new TraceCitationEntity(
        new TraceEvidence(
            integer(row, "citation_ordinal"),
            text(row, "physical_segment_id"),
            integer(row, "start_offset"),
            integer(row, "end_offset"),
            ((Number) row.get("retrieval_score")).doubleValue(),
            ((Number) row.get("rerank_score")).doubleValue(),
            List.of(text(row, "fact_sha256").split(","))),
        text(row, "publication_id"),
        text(row, "source_segment_id"),
        integer(row, "page_number"),
        text(row, "text_sha256"),
        text(row, "page_sha256"),
        text(row, "quote_sha256"));
  }

  private static PublicationVersion publication(Map<String, Object> row) {
    return new PublicationVersion(
        text(row, "document_id"),
        text(row, "id"),
        text(row, "revision_id"),
        text(row, "projection_generation_id"),
        text(row, "source_sha256"),
        text(row, "parser_revision"),
        new IndexTarget(
            text(row, "embedding_identity"),
            text(row, "projection_identity"),
            text(row, "model_revision"),
            integer(row, "dimensions")),
        text(row, "manifest_sha256"),
        integer(row, "segment_count"));
  }

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }
}
