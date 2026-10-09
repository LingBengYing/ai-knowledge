package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.entity.WikiCatalogDocument;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** File discovery reads complete published original text, never vector candidates or summaries. */
public final class WikiCatalogRepository {
  private final SqliteAuthorityStore store;

  public WikiCatalogRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public List<WikiCatalogDocument> documents(Actor actor, String kind) {
    return store
        .rows(
            """
        SELECT d.*,COALESCE(i.state,j.state,'ready') AS processing_state
        FROM documents d
        LEFT JOIN ingestion_jobs j ON j.document_id=d.id AND j.revision_id=d.active_revision_id
        LEFT JOIN indexing_jobs i ON i.document_id=d.id AND i.revision_id=d.active_revision_id
        WHERE d.workspace_id=? AND (?='' OR d.document_type=?)
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        ORDER BY d.updated_at DESC,d.id
        """,
            actor.workspaceId(),
            kind,
            kind)
        .stream()
        .map(
            row ->
                new WikiCatalogDocument(
                    AuthorityRows.text(row, "id"), AuthorityRows.text(row, "filename"),
                    AuthorityRows.text(row, "display_name"), AuthorityRows.text(row, "mime_type"),
                    AuthorityRows.text(row, "document_type"),
                        AuthorityRows.text(row, "processing_state"),
                    AuthorityRows.text(row, "active_revision_id"),
                        AuthorityRows.text(row, "source_sha256")))
        .toList();
  }

  /** Page/frame text avoids duplicate overlapping chunks and preserves cross-chunk keywords. */
  public List<String> publishedText(Actor actor, PublicationVersion publication) {
    return store
        .rows(
            """
        WITH current AS (
          SELECT p.* FROM index_publications p
          JOIN active_corpus_publications a ON a.publication_id=p.id AND a.document_id=p.document_id AND a.revision_id=p.revision_id
          JOIN documents d ON d.id=p.document_id AND d.source_sha256=p.source_sha256
          WHERE p.id=? AND p.revision_id=? AND p.source_sha256=? AND d.workspace_id=?
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        )
        SELECT * FROM (
          SELECT 0 AS kind_order,s.page_number AS first_order,0 AS second_order,s.text,s.text_sha256
          FROM corpus_pages s JOIN current p ON p.revision_id=s.revision_id
          WHERE EXISTS(SELECT 1 FROM index_publication_entries e JOIN corpus_segments c ON c.id=e.source_segment_id
            WHERE e.publication_id=p.id AND c.revision_id=s.revision_id AND c.page_number=s.page_number)
          UNION ALL
          SELECT 1,s.ordinal,0,s.text,s.text_sha256 FROM audio_spans s
          JOIN current p ON p.revision_id=s.revision_id
          JOIN audio_publication_entries e ON e.publication_id=p.id AND e.audio_span_id=s.id
          UNION ALL
          SELECT 2,s.ordinal,0,s.text,s.text_sha256 FROM video_transcript_spans s
          JOIN current p ON p.revision_id=s.revision_id
          JOIN video_transcript_publication_entries e ON e.publication_id=p.id AND e.video_transcript_span_id=s.id
          UNION ALL
          SELECT 3,s.ordinal,0,s.text,s.text_sha256 FROM video_frame_ocr s
          JOIN current p ON p.revision_id=s.revision_id
          WHERE EXISTS(SELECT 1 FROM video_ocr_publication_entries e JOIN video_ocr_segments c ON c.id=e.video_ocr_segment_id
            WHERE e.publication_id=p.id AND c.revision_id=s.revision_id AND c.frame_id=s.frame_id)
          UNION ALL
          SELECT 4,s.stream_index,s.ordinal,s.text,s.text_sha256 FROM video_subtitle_cues s
          JOIN current p ON p.revision_id=s.revision_id
          JOIN video_subtitle_publication_entries e ON e.publication_id=p.id AND e.video_subtitle_cue_id=s.id
        ) ORDER BY kind_order,first_order,second_order
        """,
            publication.publicationId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            actor.workspaceId())
        .stream()
        .map(
            row -> {
              String text = AuthorityRows.text(row, "text");
              if (!ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8))
                  .equals(AuthorityRows.text(row, "text_sha256"))) {
                throw ModelValues.notFound();
              }
              return text;
            })
        .toList();
  }
}
