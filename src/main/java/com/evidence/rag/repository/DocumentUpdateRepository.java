package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.entity.DocumentReplacementEntity;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Immutable original history and a single staged replacement. All methods use the caller's TX. */
public final class DocumentUpdateRepository {
  private final SqliteAuthorityStore store;

  public DocumentUpdateRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<DocumentReplacementEntity> current(String documentId) {
    return store.rows("SELECT * FROM document_replacements WHERE document_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1", documentId)
        .stream().findFirst().map(DocumentUpdateRepository::entity);
  }

  public Optional<DocumentReplacementEntity> findCurrent(String documentId) {
    return current(documentId);
  }

  public Optional<DocumentReplacementEntity> find(String id) {
    return store.rows("SELECT * FROM document_replacements WHERE id=?", id)
        .stream().findFirst().map(DocumentUpdateRepository::entity);
  }

  public Optional<DocumentReplacementEntity> findByRevision(String documentId, String revisionId) {
    return store.rows("SELECT * FROM document_replacements WHERE document_id=? AND candidate_revision_id=?", documentId, revisionId)
        .stream().findFirst().map(DocumentUpdateRepository::entity);
  }

  public Optional<String> replacementIdForIndexJob(String jobId) {
    return store.rows("SELECT replacement_id FROM indexing_jobs WHERE id=?", jobId)
        .stream().map(r -> AuthorityRows.text(r, "replacement_id")).filter(Objects::nonNull).findFirst();
  }

  public String pipeline(String documentId) {
    var rows = store.rows("SELECT CASE WHEN EXISTS(SELECT 1 FROM sound_originals WHERE document_id=d.id) THEN 'sound' WHEN EXISTS(SELECT 1 FROM video_av_originals WHERE document_id=d.id) THEN 'video_av' WHEN EXISTS(SELECT 1 FROM corpus_documents WHERE document_id=d.id) THEN 'corpus' END pipeline FROM documents d WHERE d.id=?", documentId);
    return rows.isEmpty() ? null : AuthorityRows.text(rows.getFirst(), "pipeline");
  }

  public boolean canPrepare(String documentId) {
    return pipeline(documentId) != null && store.count("SELECT COUNT(*) FROM documents d WHERE d.id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id) AND NOT EXISTS(SELECT 1 FROM document_cleanups WHERE document_id=d.id) AND NOT EXISTS(SELECT 1 FROM document_replacements WHERE document_id=d.id AND state IN ('stored','queued','processing','parsed','indexing')) AND NOT EXISTS(SELECT 1 FROM ingestion_jobs WHERE document_id=d.id AND state IN ('queued','processing')) AND NOT EXISTS(SELECT 1 FROM indexing_jobs WHERE document_id=d.id AND state IN ('queued','processing'))", documentId) == 1;
  }

  public Optional<String> activePublicationId(String documentId) {
    return currentPublicationId(documentId);
  }

  public Optional<String> currentPublicationId(String documentId) {
    String pipeline = pipeline(documentId);
    String sql = switch (pipeline == null ? "" : pipeline) {
      case "corpus" -> "SELECT publication_id AS id FROM active_corpus_publications WHERE document_id=?";
      case "sound" -> "SELECT p.id FROM sound_publications p JOIN documents d ON d.id=p.document_id AND d.active_revision_id=p.source_revision_id WHERE d.id=? ORDER BY p.created_at DESC,p.id DESC LIMIT 1";
      case "video_av" -> "SELECT p.id FROM video_av_publications p JOIN documents d ON d.id=p.document_id AND d.active_revision_id=p.source_revision_id WHERE d.id=? ORDER BY p.created_at_ms DESC,p.id LIMIT 1";
      default -> "SELECT id FROM documents WHERE 0 AND id=?";
    };
    return store.rows(sql, documentId).stream().findFirst().map(r -> AuthorityRows.text(r, "id"));
  }

  public Optional<DocumentOriginal> original(String documentId, String revisionId) {
    var current = store.rows("SELECT d.id document_id,d.active_revision_id revision_id,d.filename,d.document_type,d.mime_type media_type,d.source_sha256,d.size_bytes,CASE WHEN c.document_id IS NOT NULL THEN c.original_blob WHEN s.document_id IS NOT NULL THEN s.original_blob ELSE v.original_blob END original_blob FROM documents d LEFT JOIN corpus_documents c ON c.document_id=d.id AND c.initial_revision_id=d.active_revision_id LEFT JOIN sound_originals s ON s.document_id=d.id AND s.source_revision_id=d.active_revision_id LEFT JOIN video_av_originals v ON v.document_id=d.id AND v.source_revision_id=d.active_revision_id WHERE d.id=? AND d.active_revision_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)", documentId, revisionId);
    if (!current.isEmpty()) {
      return Optional.of(original(current.getFirst(), "revision_id", "media_type"));
    }
    var archived = store.rows("SELECT * FROM document_original_revisions WHERE document_id=? AND revision_id=? AND payload_purged=0", documentId, revisionId);
    return archived.stream().findFirst().map(row -> original(row,"revision_id","media_type"));
  }

  public void insertReplacement(DocumentReplacementEntity value, DocumentOriginal previous, DocumentOriginal candidate, String now) {
    if (!canPrepare(value.documentId()) || !value.documentId().equals(previous.documentId())
        || !value.documentId().equals(candidate.documentId()) || !value.baseRevisionId().equals(previous.revisionId())
        || !value.candidateRevisionId().equals(candidate.revisionId()) || !value.pipeline().equals(pipeline(value.documentId()))
        || !previous.documentType().equals(candidate.documentType()) || !Objects.equals(value.basePublicationId(), currentPublicationId(value.documentId()).orElse(null))) {
      throw ModelValues.invalid();
    }
    var actual = original(previous.documentId(), previous.revisionId()).orElseThrow(ModelValues::invalid);
    if (!actual.sourceSha256().equals(previous.sourceSha256()) || !actual.filename().equals(previous.filename())
        || !actual.mediaType().equals(previous.mediaType()) || actual.sizeBytes() != previous.sizeBytes()) {
      throw ModelValues.invalid();
    }
    String workspace = AuthorityRows.text(store.rows("SELECT workspace_id FROM documents WHERE id=?", value.documentId()).getFirst(), "workspace_id");
    long added = candidate.sizeBytes() + (store.count("SELECT COUNT(*) FROM document_original_revisions WHERE revision_id=?", previous.revisionId()) == 0 ? previous.sizeBytes() : 0);
    if (new IngestionRepository(store).storedBytes(workspace) > 256L * 1024 * 1024 - added) {
      throw new ApplicationException(FailureKind.CONFLICT, "ingestion_quota_exceeded", "当前组织的原文件存储已达到开发配额。");
    }
    archive(previous, now);
    archive(candidate, now);
    store.execute("INSERT INTO document_replacements(id,document_id,base_revision_id,base_publication_id,candidate_revision_id,pipeline,state,created_by,ingestion_job_id,index_job_id,publication_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?,?,?,?,?)", value.id(), value.documentId(), value.baseRevisionId(), value.basePublicationId(), value.candidateRevisionId(), value.pipeline(), value.state(), value.createdBy(), value.ingestionJobId(), value.indexJobId(), value.publicationId(), value.createdAt(), value.updatedAt());
  }

  public void insert(DocumentReplacementEntity value, DocumentOriginal previous, DocumentOriginal candidate) {
    insertReplacement(value, previous, candidate, value.createdAt());
  }

  private void archive(DocumentOriginal original, String now) {
    if (store.count("SELECT COUNT(*) FROM document_original_revisions WHERE revision_id=?", original.revisionId()) != 0) {
      var prior = original(original.documentId(), original.revisionId()).orElseThrow(ModelValues::invalid);
      if (!prior.sourceSha256().equals(original.sourceSha256()) || prior.sizeBytes() != original.sizeBytes()) {
        throw ModelValues.invalid();
      }
      return;
    }
    store.execute("INSERT INTO document_original_revisions(revision_id,document_id,filename,document_type,media_type,source_sha256,size_bytes,original_blob,created_at) VALUES(?,?,?,?,?,?,?,?,?)", original.revisionId(), original.documentId(), original.filename(), original.documentType(), original.mediaType(), original.sourceSha256(), original.sizeBytes(), original.content(), now);
  }

  public boolean sourceCurrent(String id) {
    var found = find(id);
    if (found.isEmpty()) {
      return false;
    }
    var candidate = found.get();
    if (!current(candidate.documentId()).map(value -> value.id().equals(id)).orElse(false)) {
      return false;
    }
    if (store.count("SELECT COUNT(*) FROM documents d WHERE d.id=? AND (d.active_revision_id=? OR (java_replacement_authorized(d.id)=1 AND d.active_revision_id=?)) AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id) AND NOT EXISTS(SELECT 1 FROM document_cleanups WHERE document_id=d.id)", candidate.documentId(), candidate.baseRevisionId(), candidate.candidateRevisionId()) != 1
        || "published".equals(candidate.state())) {
      return false;
    }
    if (!store.replacementAuthorized(candidate.documentId()) && !Objects.equals(candidate.basePublicationId(), currentPublicationId(candidate.documentId()).orElse(null))) {
      return false;
    }
    try {
      original(candidate.documentId(), candidate.baseRevisionId()).orElseThrow(ModelValues::invalid);
      original(candidate.documentId(), candidate.candidateRevisionId()).orElseThrow(ModelValues::invalid);
      return true;
    } catch (ApplicationException invalid) {
      return false;
    }
  }

  public boolean replacementCurrent(String jobId) {
    var rows = store.rows("SELECT replacement_id FROM ingestion_jobs WHERE id=?", jobId);
    if (rows.size() != 1) {
      return false;
    }
    String replacement = AuthorityRows.text(rows.getFirst(),"replacement_id");
    if (replacement != null) {
      return sourceCurrent(replacement);
    }
    return store.count("SELECT COUNT(*) FROM ingestion_jobs j JOIN corpus_documents c ON c.document_id=j.document_id AND c.initial_revision_id=j.revision_id JOIN documents d ON d.id=c.document_id AND d.active_revision_id=j.revision_id WHERE j.id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)",jobId) == 1;
  }

  public void attachIngestionJob(String id, String jobId, String now) {
    store.execute("UPDATE document_replacements SET ingestion_job_id=?,state='queued',updated_at=? WHERE id=? AND state IN ('stored','failed','cancelled')", jobId, now, id);
  }

  public void attachIndexJob(String id, String jobId, String now) {
    store.execute("UPDATE document_replacements SET index_job_id=?,state='indexing',updated_at=? WHERE id=? AND state IN ('parsed','failed','cancelled')", jobId, now, id);
  }

  public void recoverStandaloneIndexing() {
    store.execute("UPDATE document_replacements SET state='failed',updated_at=? WHERE pipeline IN ('sound','video_av') AND state='indexing'",java.time.Instant.now().toString());
  }

  public void markParsed(String id, String now) {
    state(id, "parsed", now);
  }

  public void markIndexing(String id, String now) {
    state(id, "indexing", now);
  }

  public void markFailed(String id, String now) {
    state(id, "failed", now);
  }

  public void markFailed(String id, String safeCode, String now) {
    markFailed(id, now);
  }

  public void markCancelled(String id, String now) {
    state(id, "cancelled", now);
  }

  void state(String id, String state, String now) {
    store.execute("UPDATE document_replacements SET state=?,updated_at=? WHERE id=? AND state!='published'", state, now, id);
  }

  /** Temporary aliases exist only under the caller's locked transaction; no provider runs here. */
  public <T> T withCandidateSource(String id, Supplier<T> persist) {
    var value = find(id).orElseThrow(ModelValues::invalid);
    if (!sourceCurrent(id)) {
      throw ModelValues.invalid();
    }
    return store.replacementScope(id, value.documentId(), () -> {
      var metadata = store.rows("SELECT * FROM documents WHERE id=?", value.documentId()).getFirst();
      var corpus = store.rows("SELECT * FROM corpus_documents WHERE document_id=?", value.documentId());
      var sound = store.rows("SELECT * FROM sound_originals WHERE document_id=?", value.documentId());
      var video = store.rows("SELECT * FROM video_av_originals WHERE document_id=?", value.documentId());
      DocumentOriginal candidate = original(value.documentId(), value.candidateRevisionId()).orElseThrow(ModelValues::invalid);
      switchAliases(candidate, value.pipeline(), null);
      try {
        return persist.get();
      } finally {
        if (!"published".equals(find(id).orElseThrow(ModelValues::invalid).state())) {
          restore(metadata, corpus, sound, video);
        }
      }
    });
  }

  public void activate(String id, String publicationId, String now) {
    var value = find(id).orElseThrow(ModelValues::invalid);
    if (!store.replacementAuthorized(value.documentId()) || store.count("SELECT COUNT(*) FROM documents WHERE id=? AND active_revision_id=?", value.documentId(), value.candidateRevisionId()) != 1) {
      throw ModelValues.invalid();
    }
    String table = switch (value.pipeline()) {
      case "corpus" -> "index_publications";
      case "sound" -> "sound_publications";
      case "video_av" -> "video_av_publications";
      default -> throw ModelValues.invalid();
    };
    String revision = "corpus".equals(value.pipeline()) ? "revision_id" : "source_revision_id";
    if (store.count("SELECT COUNT(*) FROM " + table + " WHERE id=? AND document_id=? AND " + revision + "=? AND source_sha256=(SELECT source_sha256 FROM document_original_revisions WHERE revision_id=?)", publicationId, value.documentId(), value.candidateRevisionId(), value.candidateRevisionId()) != 1) {
      throw ModelValues.invalid();
    }
    if ("corpus".equals(value.pipeline()) && store.count("SELECT COUNT(*) FROM active_corpus_publications WHERE document_id=? AND publication_id=? AND revision_id=?", value.documentId(), publicationId, value.candidateRevisionId()) != 1) {
      throw ModelValues.invalid();
    }
    store.execute("UPDATE document_replacements SET state='published',publication_id=?,updated_at=? WHERE id=?", publicationId, now, id);
    store.execute("UPDATE documents SET updated_at=? WHERE id=?", now, value.documentId());
  }

  private void switchAliases(DocumentOriginal value, String pipeline, String parsedRevision) {
    store.execute("UPDATE documents SET filename=?,document_type=?,mime_type=?,active_revision_id=?,source_sha256=?,size_bytes=? WHERE id=?", value.filename(), value.documentType(), value.mediaType(), value.revisionId(), value.sourceSha256(), value.sizeBytes(), value.documentId());
    if ("corpus".equals(pipeline)) {
      if (parsedRevision == null && store.count("SELECT COUNT(*) FROM corpus_revisions WHERE id=? AND parsed_at IS NOT NULL", value.revisionId()) == 1) {
        parsedRevision = value.revisionId();
      }
      store.execute("UPDATE corpus_documents SET original_blob=?,initial_revision_id=?,parsed_revision_id=? WHERE document_id=?", value.content(), value.revisionId(), parsedRevision, value.documentId());
    } else {
      String table = "sound".equals(pipeline) ? "sound_originals" : "video_av_originals";
      store.execute("UPDATE " + table + " SET source_revision_id=?,source_sha256=?,filename=?,media_type=?,size_bytes=?,original_blob=? WHERE document_id=?", value.revisionId(), value.sourceSha256(), value.filename(), value.mediaType(), value.sizeBytes(), value.content(), value.documentId());
    }
  }

  private void restore(Map<String, Object> d, java.util.List<Map<String, Object>> corpus, java.util.List<Map<String, Object>> sound, java.util.List<Map<String, Object>> video) {
    store.execute("UPDATE documents SET filename=?,document_type=?,mime_type=?,active_revision_id=?,source_sha256=?,size_bytes=?,updated_at=? WHERE id=?", d.get("filename"),d.get("document_type"),d.get("mime_type"),d.get("active_revision_id"),d.get("source_sha256"),d.get("size_bytes"),d.get("updated_at"),d.get("id"));
    if (!corpus.isEmpty()) {
      var c = corpus.getFirst();
      store.execute("UPDATE corpus_documents SET original_blob=?,initial_revision_id=?,parsed_revision_id=? WHERE document_id=?", c.get("original_blob"),c.get("initial_revision_id"),c.get("parsed_revision_id"),d.get("id"));
    }
    for (String table : java.util.List.of("sound_originals", "video_av_originals")) {
      var rows = "sound_originals".equals(table) ? sound : video;
      if (!rows.isEmpty()) {
        var row = rows.getFirst();
        store.execute("UPDATE " + table + " SET source_revision_id=?,source_sha256=?,filename=?,media_type=?,size_bytes=?,original_blob=? WHERE document_id=?",row.get("source_revision_id"),row.get("source_sha256"),row.get("filename"),row.get("media_type"),row.get("size_bytes"),row.get("original_blob"),d.get("id"));
      }
    }
  }

  private static DocumentReplacementEntity entity(Map<String,Object> row) {
    return new DocumentReplacementEntity(AuthorityRows.text(row,"id"),AuthorityRows.text(row,"document_id"),AuthorityRows.text(row,"base_revision_id"),AuthorityRows.text(row,"base_publication_id"),AuthorityRows.text(row,"candidate_revision_id"),AuthorityRows.text(row,"pipeline"),AuthorityRows.text(row,"state"),AuthorityRows.text(row,"created_by"),AuthorityRows.text(row,"ingestion_job_id"),AuthorityRows.text(row,"index_job_id"),AuthorityRows.text(row,"publication_id"),AuthorityRows.text(row,"created_at"),AuthorityRows.text(row,"updated_at"));
  }

  private static DocumentOriginal original(Map<String,Object> row,String revision,String media) {
    return new DocumentOriginal(AuthorityRows.text(row,"document_id"),AuthorityRows.text(row,revision),AuthorityRows.text(row,"filename"),AuthorityRows.text(row,"document_type"),AuthorityRows.text(row,media),AuthorityRows.text(row,"source_sha256"),((Number)row.get("size_bytes")).longValue(),(byte[])row.get("original_blob"));
  }
}
