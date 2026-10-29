package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.WikiMaintenanceSnapshot;
import com.evidence.rag.model.domain.WikiSourcePreview;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL aggregation and pagination keep maintenance independent from vector/model availability. */
public final class WikiMaintenanceRepository {
  private static final String OBSERVATIONS =
      """
      WITH live AS (
        SELECT d.* FROM documents d WHERE d.workspace_id=?
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
      ), duplicates AS (
        SELECT source_sha256,MIN(id) AS first_id FROM live
        GROUP BY source_sha256 HAVING COUNT(*)>1
      ), parsing AS (
        SELECT j.*,ROW_NUMBER() OVER(PARTITION BY j.document_id ORDER BY j.created_at DESC,j.id DESC) AS latest
        FROM ingestion_jobs j JOIN live d ON d.id=j.document_id
      ), indexing AS (
        SELECT j.*,ROW_NUMBER() OVER(PARTITION BY j.document_id ORDER BY j.created_at DESC,j.rebuild_sequence DESC,j.id DESC) AS latest
        FROM indexing_jobs j JOIN live d ON d.id=j.document_id
      ), current_pages AS (
        SELECT p.* FROM wiki_page_revisions p WHERE p.workspace_id=?
          AND p.version=(SELECT MAX(v.version) FROM wiki_page_revisions v
            WHERE v.workspace_id=p.workspace_id AND v.page_id=p.page_id)
          AND COALESCE((SELECT l.state FROM wiki_page_lifecycle l
            WHERE l.workspace_id=p.workspace_id AND l.page_id=p.page_id
            ORDER BY l.lifecycle_version DESC LIMIT 1),'active')='active'
      ), cited AS (
        SELECT DISTINCT json_extract(source.value,'$.publication.documentId') AS document_id
        FROM current_pages p, json_each(p.content_json,'$.sections') section,
          json_each(section.value,'$.sources') source
      ), current_publications AS (
        SELECT d.id AS document_id,p.id AS publication_id
      """
          + EvidenceRepository.CURRENT_PUBLICATIONS
          + """
          AND d.id IN (SELECT document_id FROM cited)
      ), observations AS (
        SELECT 'parse_failed' AS kind,'document' AS target_type,d.id AS target_id,d.display_name AS title,
          j.revision_id,j.error_code,NULL AS related_document_id
        FROM live d JOIN parsing j ON j.document_id=d.id AND j.latest=1 WHERE j.state='failed'
        UNION ALL
        SELECT 'index_failed','document',d.id,d.display_name,j.revision_id,j.error_code,NULL
        FROM live d JOIN indexing j ON j.document_id=d.id AND j.latest=1 WHERE j.state='failed'
        UNION ALL
        SELECT 'empty_text','document',d.id,d.display_name,j.revision_id,NULL,NULL
        FROM live d JOIN parsing j ON j.document_id=d.id AND j.latest=1
        WHERE d.document_type='document' AND j.state='parsed'
          AND NOT EXISTS(SELECT 1 FROM corpus_pages p WHERE p.revision_id=j.revision_id
            AND length(trim(p.text,char(9)||char(10)||char(13)||' '))>0)
        UNION ALL
        SELECT 'duplicate_source','document',d.id,d.display_name,d.active_revision_id,NULL,duplicate.first_id
        FROM live d JOIN duplicates duplicate ON duplicate.source_sha256=d.source_sha256
          AND d.id!=duplicate.first_id
        UNION ALL
        SELECT 'stale_knowledge','page',p.page_id,json_extract(p.content_json,'$.title'),NULL,NULL,NULL
        FROM current_pages p WHERE EXISTS(
          SELECT 1 FROM json_each(p.content_json,'$.sections') section,
            json_each(section.value,'$.sources') source
          LEFT JOIN current_publications a
            ON a.document_id=json_extract(source.value,'$.publication.documentId')
          WHERE a.publication_id IS NULL
            OR a.publication_id!=json_extract(source.value,'$.publication.publicationId'))
      )
      """;
  private final SqliteAuthorityStore store;

  public WikiMaintenanceRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  public WikiMaintenanceSnapshot list(Actor actor, int offset, int limit, String kind) {
    var counts = new LinkedHashMap<String, Long>();
    for (String name :
        List.of(
            "parse_failed", "index_failed", "empty_text", "duplicate_source", "stale_knowledge")) {
      counts.put(name, 0L);
    }
    for (var row :
        store.rows(
            OBSERVATIONS + "SELECT kind,COUNT(*) AS total FROM observations GROUP BY kind",
            actor.workspaceId(),
            actor.workspaceId(),
            actor.workspaceId())) {
      counts.put(AuthorityRows.text(row, "kind"), AuthorityRows.number(row, "total"));
    }
    var items =
        store
            .rows(
                OBSERVATIONS
                    + "SELECT * FROM observations WHERE (?='all' AND kind!='stale_knowledge') OR kind=? ORDER BY kind,target_id LIMIT ? OFFSET ?",
                actor.workspaceId(),
                actor.workspaceId(),
                actor.workspaceId(),
                kind,
                kind,
                limit,
                offset)
            .stream()
            .map(
                row ->
                    new WikiMaintenanceSnapshot.Observation(
                        AuthorityRows.text(row, "kind"),
                        AuthorityRows.text(row, "target_type"),
                        AuthorityRows.text(row, "target_id"),
                        AuthorityRows.text(row, "title"),
                        nullable(row, "revision_id"),
                        nullable(row, "error_code"),
                        nullable(row, "related_document_id")))
            .toList();
    return new WikiMaintenanceSnapshot(items, counts);
  }

  public String displayName(Actor actor, String documentId) {
    return store
        .rows(
            "SELECT display_name FROM documents WHERE workspace_id=? AND id=?",
            actor.workspaceId(),
            documentId)
        .stream()
        .map(row -> AuthorityRows.text(row, "display_name"))
        .findFirst()
        .orElse("来源资料不可用");
  }

  /** Only requested page sources are read, and only a bounded preview crosses this boundary. */
  public WikiSourcePreview preview(Actor actor, PublicationVersion publication) {
    var rows =
        store.rows(
            """
        WITH original AS (
          SELECT p.revision_id FROM index_publications p JOIN documents d ON d.id=p.document_id
          WHERE p.id=? AND p.document_id=? AND p.revision_id=? AND p.source_sha256=?
            AND d.workspace_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        ), parts AS (
          SELECT 0 AS kind,page_number AS ordinal,text FROM corpus_pages WHERE revision_id IN (SELECT revision_id FROM original)
          UNION ALL SELECT 1,ordinal,text FROM audio_spans WHERE revision_id IN (SELECT revision_id FROM original)
          UNION ALL SELECT 2,ordinal,text FROM video_transcript_spans WHERE revision_id IN (SELECT revision_id FROM original)
          UNION ALL SELECT 3,ordinal,text FROM video_frame_ocr WHERE revision_id IN (SELECT revision_id FROM original)
          UNION ALL SELECT 4,ordinal,text FROM video_subtitle_cues WHERE revision_id IN (SELECT revision_id FROM original)
        )
        SELECT COALESCE(SUM(length(text)),0) AS characters,COUNT(*) AS parts,
          COALESCE(MAX(length(text)),0) AS longest,
          COALESCE((SELECT group_concat(preview,char(10)) FROM
            (SELECT substr(text,1,400) AS preview FROM parts ORDER BY kind,ordinal LIMIT 3)),'') AS excerpt
        FROM parts
        """,
            publication.publicationId(),
            publication.documentId(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            actor.workspaceId());
    var row = rows.getFirst();
    String excerpt = AuthorityRows.text(row, "excerpt");
    long characters = AuthorityRows.number(row, "characters");
    long parts = AuthorityRows.number(row, "parts");
    return new WikiSourcePreview(
        characters, parts, excerpt, parts > 3 || AuthorityRows.number(row, "longest") > 400);
  }

  private static String nullable(Map<String, Object> row, String name) {
    return row.get(name) == null ? null : AuthorityRows.text(row, name);
  }
}
