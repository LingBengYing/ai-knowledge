package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundManagedEvidence;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundSpan;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Complete sound authority. Every method participates in its caller's single transaction. */
public final class SoundRepository {
  private final SqliteAuthorityStore store;
  private final ManagementRepository management;

  public SoundRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
    management = new ManagementRepository(store);
  }

  public void insertOriginal(DocumentOriginal original, String createdAt) {
    if (!"audio".equals(original.documentType())) {
      throw ModelValues.invalid();
    }
    store.execute(
        """
        INSERT INTO sound_originals(document_id,source_revision_id,source_sha256,filename,
          media_type,size_bytes,original_blob,created_at) VALUES(?,?,?,?,?,?,?,?)
        """,
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.filename(),
        original.mediaType(),
        original.sizeBytes(),
        original.content(),
        createdAt);
  }

  public Optional<DocumentOriginal> findOriginal(Actor actor, String documentId) {
    return management
        .findDocumentOriginal(actor, documentId)
        .filter(value -> "audio".equals(value.documentType()));
  }

  public void insertPublication(SoundPublication publication) {
    validatePublication(publication);
    for (var span : publication.spans()) {
      store.execute(
          """
          INSERT INTO sound_spans(publication_id,id,ordinal,start_sample,end_sample,pcm_sha256,
            recall_text,physical_segment_id,entry_sha256) VALUES(?,?,?,?,?,?,?,?,?)
          """,
          publication.id(),
          span.id(),
          span.ordinal(),
          span.startSample(),
          span.endSample(),
          span.pcmSha256(),
          span.recallText(),
          span.physicalSegmentId(),
          span.entrySha256());
    }
    var target = publication.target();
    store.execute(
        """
        INSERT INTO sound_publications(id,workspace_id,document_id,source_revision_id,source_sha256,
          filename,media_type,size_bytes,generation_id,embedding_identity,projection_identity,
          embedding_model_revision,dimensions,sound_model_revision,decoder_revision,chunk_seconds,
          sample_count,span_count,manifest_sha256,profile_fingerprint,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        publication.id(),
        publication.workspaceId(),
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.filename(),
        publication.mediaType(),
        publication.sizeBytes(),
        publication.generationId(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        publication.soundModelRevision(),
        publication.decoderRevision(),
        publication.chunkSeconds(),
        publication.sampleCount(),
        publication.spans().size(),
        publication.manifestSha256(),
        publication.profileFingerprint(),
        publication.createdAt());
  }

  public Optional<SoundPublication> findPublication(
      DocumentOriginal original, IndexTarget target, String profile) {
    return publication(original.documentId(), target, profile)
        .filter(value -> matches(value, original));
  }

  public Optional<SoundManagedEvidence> managedEvidence(String documentId) {
    var rows =
        store.rows(
            """
        SELECT s.source_revision_id,
          (SELECT p.id FROM sound_publications p WHERE p.document_id=s.document_id
             AND p.source_revision_id=s.source_revision_id AND p.source_sha256=s.source_sha256
             ORDER BY p.created_at DESC,p.id DESC LIMIT 1) AS publication_id
        FROM sound_originals s JOIN documents d ON d.id=s.document_id
        WHERE s.document_id=? AND s.source_revision_id=d.active_revision_id
          AND s.source_sha256=d.source_sha256
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            documentId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    var row = rows.getFirst();
    String id = text(row, "publication_id");
    int count =
        id == null
            ? 0
            : Math.toIntExact(
                store.count("SELECT COUNT(*) FROM sound_spans WHERE publication_id=?", id));
    return Optional.of(new SoundManagedEvidence(text(row, "source_revision_id"), id, count));
  }

  private Optional<SoundPublication> publication(String id, IndexTarget target, String profile) {
    return store
        .rows(
            """
        SELECT * FROM sound_publications WHERE document_id=? AND embedding_identity=?
          AND projection_identity=? AND embedding_model_revision=? AND dimensions=? AND profile_fingerprint=?
        ORDER BY created_at DESC,id DESC LIMIT 1
        """,
            id,
            target.embeddingIdentity(),
            target.projectionIdentity(),
            target.modelRevision(),
            target.dimensions(),
            profile)
        .stream()
        .findFirst()
        .map(this::publication);
  }

  private SoundPublication publication(Map<String, Object> row) {
    var spans =
        store
            .rows(
                "SELECT * FROM sound_spans WHERE publication_id=? ORDER BY ordinal",
                text(row, "id"))
            .stream()
            .map(
                span ->
                    new SoundSpan(
                        text(span, "id"),
                        integer(span, "ordinal"),
                        number(span, "start_sample"),
                        number(span, "end_sample"),
                        text(span, "pcm_sha256"),
                        text(span, "recall_text"),
                        text(span, "physical_segment_id"),
                        text(span, "entry_sha256")))
            .toList();
    if (spans.size() != integer(row, "span_count")) {
      throw ModelValues.notFound();
    }
    var value =
        new SoundPublication(
            text(row, "id"),
            text(row, "workspace_id"),
            text(row, "document_id"),
            text(row, "source_revision_id"),
            text(row, "source_sha256"),
            text(row, "filename"),
            text(row, "media_type"),
            number(row, "size_bytes"),
            text(row, "generation_id"),
            new IndexTarget(
                text(row, "embedding_identity"),
                text(row, "projection_identity"),
                text(row, "embedding_model_revision"),
                integer(row, "dimensions")),
            text(row, "sound_model_revision"),
            text(row, "decoder_revision"),
            integer(row, "chunk_seconds"),
            number(row, "sample_count"),
            spans,
            text(row, "manifest_sha256"),
            text(row, "profile_fingerprint"),
            text(row, "created_at"));
    validatePublication(value);
    return value;
  }

  private static void validatePublication(SoundPublication value) {
    if (!SoundProfile.fingerprint(
            value.target(),
            value.soundModelRevision(),
            value.decoderRevision(),
            value.chunkSeconds())
        .equals(value.profileFingerprint())) {
      throw ModelValues.invalid();
    }
    for (var span : value.spans()) {
      if (!SoundProfile.physicalSegmentId(value.generationId(), span.id())
          .equals(span.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    if (!SoundProfile.manifestSha256(
            value.workspaceId(), value.documentId(), value.generationId(), value.spans())
        .equals(value.manifestSha256())) {
      throw ModelValues.invalid();
    }
  }

  private static boolean matches(SoundPublication value, DocumentOriginal original) {
    return value.documentId().equals(original.documentId())
        && value.sourceRevisionId().equals(original.revisionId())
        && value.sourceSha256().equals(original.sourceSha256())
        && value.filename().equals(original.filename())
        && value.mediaType().equals(original.mediaType())
        && value.sizeBytes() == original.sizeBytes();
  }

  private static boolean matches(SoundPublication value, ManagedMediaSources.Metadata metadata) {
    return value.documentId().equals(metadata.id())
        && value.workspaceId().equals(metadata.workspace())
        && value.sourceRevisionId().equals(metadata.revision())
        && value.sourceSha256().equals(metadata.sha())
        && value.filename().equals(metadata.filename())
        && value.mediaType().equals(metadata.mime())
        && value.sizeBytes() == metadata.bytes();
  }
}
