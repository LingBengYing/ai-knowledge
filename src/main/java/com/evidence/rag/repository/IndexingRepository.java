package com.evidence.rag.repository;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioVectorBinding;
import com.evidence.rag.model.domain.ImageEvidence;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageVectorBinding;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexSegment;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionItem;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.model.domain.VectorBindingIdentity;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoOcrSegmentEvidence;
import com.evidence.rag.model.domain.VideoTranscriptEvidence;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.model.entity.RevisionEntity;
import com.evidence.rag.model.entity.TaskEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQL for index attempts, immutable publication entries and active pointers. */
public final class IndexingRepository {
  private static final String PROJECTION_COUNT =
      "(r.segment_count+(SELECT COUNT(*) FROM image_evidence i WHERE i.revision_id=r.id)"
          + "+(SELECT COUNT(*) FROM audio_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)"
          + "+(SELECT COUNT(*) FROM video_frames f WHERE f.revision_id=r.id AND f.recall_text IS NOT NULL)"
          + "+(SELECT COUNT(*) FROM video_transcript_spans s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL)"
          + "+(SELECT COUNT(*) FROM video_ocr_segments s WHERE s.revision_id=r.id)"
          + "+(SELECT COUNT(*) FROM video_subtitle_cues s WHERE s.revision_id=r.id AND s.index_ordinal IS NOT NULL))";
  private final SqliteAuthorityStore store;

  public IndexingRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<RevisionEntity> parsedRevision(String documentId) {
    return store
        .rows(
            "SELECT r.* FROM corpus_documents c JOIN corpus_revisions r ON r.id=c.parsed_revision_id AND r.document_id=c.document_id JOIN ingestion_jobs p ON p.document_id=c.document_id AND p.revision_id=r.id WHERE c.document_id=? AND r.parsed_at IS NOT NULL AND "
                + PROJECTION_COUNT
                + " BETWEEN 1 AND 4096 AND p.state='parsed' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=c.document_id)",
            documentId)
        .stream()
        .findFirst()
        .map(
            row ->
                new RevisionEntity(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "source_sha256"),
                    AuthorityRows.text(row, "parser_revision"),
                    AuthorityRows.integer(row, "segment_count")));
  }

  public Optional<RevisionEntity> parsedRevision(String documentId, String revisionId) {
    return store
        .rows(
            "SELECT r.* FROM corpus_revisions r JOIN ingestion_jobs j ON j.document_id=r.document_id AND j.revision_id=r.id WHERE r.document_id=? AND r.id=? AND r.parsed_at IS NOT NULL AND j.state='parsed' AND "
                + PROJECTION_COUNT
                + " BETWEEN 1 AND 4096",
            documentId,
            revisionId)
        .stream()
        .findFirst()
        .map(
            row ->
                new RevisionEntity(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "source_sha256"),
                    AuthorityRows.text(row, "parser_revision"),
                    AuthorityRows.integer(row, "segment_count")));
  }

  public void insertReplacementJob(
      String jobId,
      String documentId,
      RevisionEntity revision,
      IndexTarget target,
      String creator,
      String basePublicationId,
      int sequence,
      String now,
      String replacementId) {
    store.execute(
        "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at,rebuild_sequence,base_publication_id,replacement_id) VALUES(?,?,?,?,?,?,?,?,?,'queued',1,?,?,?,?,?,?)",
        jobId,
        documentId,
        revision.id(),
        revision.sourceSha256(),
        revision.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        creator,
        now,
        now,
        sequence,
        basePublicationId,
        replacementId);
    new DocumentUpdateRepository(store).attachIndexJob(replacementId, jobId, now);
  }

  public void insertModelRebuildJob(
      String jobId,
      String documentId,
      RevisionEntity revision,
      IndexTarget target,
      String creator,
      String basePublicationId,
      int sequence,
      String now,
      String batchId) {
    var item =
        new ModelRebuildRepository(store).findItemForJob(jobId).orElseThrow(ModelValues::invalid);
    if (!item.batchId().equals(batchId)
        || !item.documentId().equals(documentId)
        || !item.revisionId().equals(revision.id())) {
      throw ModelValues.invalid();
    }
    store.execute(
        "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at,rebuild_sequence,base_publication_id,base_vector_set_sha256,model_rebuild_id) VALUES(?,?,?,?,?,?,?,?,?,'queued',1,?,?,?,?,?,?,?)",
        jobId,
        documentId,
        revision.id(),
        revision.sourceSha256(),
        revision.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        creator,
        now,
        now,
        sequence,
        basePublicationId,
        basePublicationId == null ? null : item.baseVectorSetSha256(),
        batchId);
  }

  public Optional<String> modelRebuildIdForJob(String jobId) {
    return store.rows("SELECT model_rebuild_id FROM indexing_jobs WHERE id=?", jobId).stream()
        .map(row -> AuthorityRows.text(row, "model_rebuild_id"))
        .filter(Objects::nonNull)
        .findFirst();
  }

  public List<String> queuedModelRebuildIds(String workspace, String batchId) {
    return store
        .rows(
            "SELECT j.id FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.model_rebuild_id=? AND j.state='queued' ORDER BY j.created_at,j.id",
            workspace,
            batchId)
        .stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }

  public void sealModelRebuildPublication(String jobId, String publicationId, String now) {
    new ModelRebuildRepository(store).seal(jobId, publicationId, now);
  }

  public boolean jobExists(String documentId) {
    return store.count("SELECT COUNT(*) FROM indexing_jobs WHERE document_id=?", documentId) != 0;
  }

  public boolean activePublicationExists(String documentId) {
    return store.count(
            "SELECT COUNT(*) FROM active_corpus_publications WHERE document_id=?", documentId)
        != 0;
  }

  public void insertJob(
      String jobId,
      String documentId,
      RevisionEntity revision,
      IndexTarget target,
      String creator,
      String now) {
    store.execute(
        "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'queued',1,?,?,?)",
        jobId,
        documentId,
        revision.id(),
        revision.sourceSha256(),
        revision.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        creator,
        now,
        now);
  }

  /** Current active metadata, never inferred from whichever task happens to be latest. */
  public Optional<IndexPublicationEntity> activePublication(String documentId) {
    return store
        .rows(
            "SELECT p.* FROM active_corpus_publications a JOIN index_publications p ON p.id=a.publication_id AND p.document_id=a.document_id AND p.revision_id=a.revision_id WHERE a.document_id=?",
            documentId)
        .stream()
        .findFirst()
        .map(
            row ->
                new IndexPublicationEntity(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "job_id"),
                    AuthorityRows.text(row, "document_id"),
                    AuthorityRows.text(row, "revision_id"),
                    AuthorityRows.integer(row, "attempt"),
                    AuthorityRows.text(row, "projection_generation_id"),
                    AuthorityRows.text(row, "source_sha256"),
                    AuthorityRows.text(row, "parser_revision"),
                    new IndexTarget(
                        AuthorityRows.text(row, "embedding_identity"),
                        AuthorityRows.text(row, "projection_identity"),
                        AuthorityRows.text(row, "model_revision"),
                        AuthorityRows.integer(row, "dimensions")),
                    AuthorityRows.text(row, "manifest_sha256"),
                    AuthorityRows.integer(row, "segment_count"),
                    AuthorityRows.text(row, "created_at")));
  }

  /** Exact active original receipt set for a same-source analyzer migration, not a SQL rewrite. */
  public RetrievalProjection.RevisionManifest migrationManifest(
      IndexClaim claim, String sourceProjection) {
    var publication = activePublication(claim.documentId()).orElseThrow(ModelValues::invalid);
    if (!basePublicationId(claim.jobId()).orElse("").equals(publication.id())
        || !publication.revisionId().equals(claim.revisionId())
        || !publication.sourceSha256().equals(claim.sourceSha256())
        || !publication.parserRevision().equals(claim.parserRevision())
        || !publication.target().projectionIdentity().equals(sourceProjection)
        || !publication.target().embeddingIdentity().equals(claim.target().embeddingIdentity())
        || publication.target().dimensions() != claim.target().dimensions()
        || publication.segmentCount() != claim.items().size()) {
      throw ModelValues.invalid();
    }
    var digests = new java.util.TreeMap<String, String>();
    for (String table :
        List.of(
            "index_publication_entries",
            "image_publication_entries",
            "audio_publication_entries",
            "video_frame_publication_entries",
            "video_transcript_publication_entries",
            "video_ocr_publication_entries",
            "video_subtitle_publication_entries")) {
      for (var row :
          store.rows(
              "SELECT physical_segment_id,entry_sha256 FROM " + table + " WHERE publication_id=?",
              publication.id())) {
        if (digests.put(
                AuthorityRows.text(row, "physical_segment_id"),
                AuthorityRows.text(row, "entry_sha256"))
            != null) {
          throw ModelValues.invalid();
        }
      }
    }
    var expected =
        claim.items().stream()
            .map(
                item ->
                    RetrievalProjection.physicalSegmentId(
                        publication.projectionGenerationId(), item.evidenceId()))
            .collect(java.util.stream.Collectors.toSet());
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.workspaceId(), claim.documentId(), publication.projectionGenerationId(), digests);
    if (!expected.equals(digests.keySet())
        || !manifest.sha256().equals(publication.manifestSha256())) {
      throw ModelValues.invalid();
    }
    return manifest;
  }

  /** Authority-only eligibility. Actor permission, capability and runtime target are separate. */
  public boolean canReindex(String documentId) {
    try {
      var publication = activePublication(documentId);
      return publication.isPresent()
          && !hasPending(documentId)
          && !hasBaseVectorReceipt(publication.get().id())
          && publicationCurrent(publication.get());
    } catch (ApplicationException corrupt) {
      return false;
    }
  }

  private boolean publicationCurrent(IndexPublicationEntity publication) {
    try {
      if (store.count(
              "SELECT COUNT(*) FROM indexing_jobs j WHERE j.id=? AND j.document_id=? AND j.revision_id=? AND j.state='indexed' AND j.attempt=? AND j.projection_generation_id=? AND j.source_sha256=? AND j.parser_revision=? AND j.embedding_identity=? AND j.projection_identity=? AND j.model_revision=? AND j.dimensions=?",
              publication.jobId(),
              publication.documentId(),
              publication.revisionId(),
              publication.attempt(),
              publication.projectionGenerationId(),
              publication.sourceSha256(),
              publication.parserRevision(),
              publication.target().embeddingIdentity(),
              publication.target().projectionIdentity(),
              publication.target().modelRevision(),
              publication.target().dimensions())
          != 1) {
        return false;
      }
      var revision = parsedRevision(publication.documentId());
      if (revision.isEmpty()
          || !revision.get().id().equals(publication.revisionId())
          || !revision.get().sourceSha256().equals(publication.sourceSha256())
          || !revision.get().parserRevision().equals(publication.parserRevision())
          || publication.segmentCount() < 1
          || publication.segmentCount() > 4096) {
        return false;
      }
      var original =
          store.rows(
              "SELECT c.original_blob,d.size_bytes,d.source_sha256 FROM corpus_documents c JOIN documents d ON d.id=c.document_id WHERE c.document_id=?",
              publication.documentId());
      if (original.size() != 1
          || !(original.getFirst().get("original_blob") instanceof byte[] content)
          || content.length < 1
          || content.length > 20 * 1024 * 1024
          || AuthorityRows.number(original.getFirst(), "size_bytes") != content.length
          || !publication
              .sourceSha256()
              .equals(AuthorityRows.text(original.getFirst(), "source_sha256"))
          || !publication.sourceSha256().equals(ModelValues.sha256(content))) {
        return false;
      }
      if (projectionItems(publication.revisionId()).size() != publication.segmentCount()) {
        return false;
      }
      return store.count(
              "SELECT (SELECT COUNT(*) FROM index_publication_entries WHERE publication_id=?)+(SELECT COUNT(*) FROM image_publication_entries WHERE publication_id=?)+(SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=?)+(SELECT COUNT(*) FROM video_frame_publication_entries WHERE publication_id=?)+(SELECT COUNT(*) FROM video_transcript_publication_entries WHERE publication_id=?)+(SELECT COUNT(*) FROM video_ocr_publication_entries WHERE publication_id=?)+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=?)",
              publication.id(),
              publication.id(),
              publication.id(),
              publication.id(),
              publication.id(),
              publication.id(),
              publication.id())
          == publication.segmentCount();
    } catch (ApplicationException corrupt) {
      return false;
    }
  }

  public boolean hasPending(String documentId) {
    return store.count(
            "SELECT COUNT(*) FROM indexing_jobs WHERE document_id=? AND state IN ('queued','processing','prepared')",
            documentId)
        != 0;
  }

  public int nextRebuildSequence(String documentId) {
    long sequence =
        store.count(
            "SELECT COALESCE(MAX(rebuild_sequence),0)+1 FROM indexing_jobs WHERE document_id=?",
            documentId);
    if (sequence < 1 || sequence > Integer.MAX_VALUE) {
      throw ModelValues.invalid();
    }
    return (int) sequence;
  }

  public Optional<String> basePublicationId(String jobId) {
    return store.rows("SELECT base_publication_id FROM indexing_jobs WHERE id=?", jobId).stream()
        .map(row -> AuthorityRows.text(row, "base_publication_id"))
        .filter(Objects::nonNull)
        .findFirst();
  }

  public boolean hasBaseVectorReceipt(String publicationId) {
    return store.count(
            "SELECT (SELECT COUNT(*) FROM image_vector_publications WHERE publication_id=?)+(SELECT COUNT(*) FROM audio_vector_publications WHERE publication_id=?)+(SELECT COUNT(*) FROM image_vector_bindings WHERE publication_id=?)+(SELECT COUNT(*) FROM audio_vector_bindings WHERE publication_id=?)",
            publicationId,
            publicationId,
            publicationId,
            publicationId)
        > 0;
  }

  public void insertRebuildJob(
      String jobId,
      String documentId,
      RevisionEntity revision,
      IndexTarget target,
      String creator,
      String basePublicationId,
      int sequence,
      String now) {
    insertRebuildJob(
        jobId, documentId, revision, target, creator, basePublicationId, sequence, now, null);
  }

  public void insertRebuildJob(
      String jobId,
      String documentId,
      RevisionEntity revision,
      IndexTarget target,
      String creator,
      String basePublicationId,
      int sequence,
      String now,
      String baseVectorSetSha256) {
    if (baseVectorSetSha256 != null && !baseVectorSetSha256.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    store.execute(
        "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at,rebuild_sequence,base_publication_id,base_vector_set_sha256) VALUES(?,?,?,?,?,?,?,?,?,'queued',1,?,?,?,?,?,?)",
        jobId,
        documentId,
        revision.id(),
        revision.sourceSha256(),
        revision.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        creator,
        now,
        now,
        sequence,
        basePublicationId,
        baseVectorSetSha256);
  }

  /** Full local authority qualification; runtime target configuration is checked by the caller. */
  public boolean canReindexWithVectors(String documentId) {
    try {
      var base = activePublication(documentId);
      if (base.isEmpty() || hasPending(documentId) || !publicationCurrent(base.get())) {
        return false;
      }
      var rows = store.rows("SELECT workspace_id FROM documents WHERE id=?", documentId);
      if (rows.size() != 1) {
        return false;
      }
      vectorPlanForBase(
          "qualification", AuthorityRows.text(rows.getFirst(), "workspace_id"), base.get().id());
      return true;
    } catch (ApplicationException corrupt) {
      return false;
    }
  }

  public ReindexVectorPlan vectorPlanForBase(
      String jobId, String workspaceId, String basePublicationId) {
    var base = VectorBindingRows.publication(store, workspaceId, basePublicationId);
    var active = activePublication(base.documentId());
    if (active.isEmpty()
        || !active.get().id().equals(basePublicationId)
        || !publicationCurrent(active.get())) {
      throw ModelValues.invalid();
    }
    var images = new ImageVectorRepository(store).allBindings(workspaceId, base);
    var audios = new AudioVectorRepository(store).allBindings(workspaceId, base);
    return new ReindexVectorPlan(
        jobId,
        workspaceId,
        base,
        VectorBindingIdentity.setSha256(workspaceId, base, images, audios),
        images,
        audios);
  }

  public Optional<String> baseVectorSetSha256(String jobId) {
    return store.rows("SELECT base_vector_set_sha256 FROM indexing_jobs WHERE id=?", jobId).stream()
        .map(row -> AuthorityRows.text(row, "base_vector_set_sha256"))
        .filter(Objects::nonNull)
        .findFirst();
  }

  public ReindexVectorPlan freezeVectorPlan(String jobId) {
    if (modelRebuildIdForJob(jobId).isPresent()) {
      return new ModelRebuildRepository(store).plan(jobId).orElseThrow(ModelValues::invalid);
    }

    var job = findInternalTask(jobId).orElseThrow(ModelValues::invalid);
    String base = basePublicationId(jobId).orElseThrow(ModelValues::invalid);
    String expected = baseVectorSetSha256(jobId).orElseThrow(ModelValues::invalid);
    var plan = vectorPlanForBase(jobId, job.workspaceId(), base);
    if (!expected.equals(plan.setSha256())
        || !job.target().equals(plan.basePublication().target())
        || !job.revisionId().equals(plan.basePublication().sourceRevisionId())) {
      throw ModelValues.invalid();
    }
    return plan;
  }

  public void insertModelRebuildBindings(
      String jobId, PublicationVersion publication, ReindexVectorPlan plan) {
    if (modelRebuildIdForJob(jobId).isEmpty() || !jobId.equals(plan.jobId())) {
      throw ModelValues.invalid();
    }
    insertInheritedBindings(publication, plan);
  }

  /** Caller owns the same transaction as text publication and active-pointer CAS. */
  public void insertInheritedBindings(PublicationVersion publication, ReindexVectorPlan plan) {
    var actual =
        VectorBindingRows.publication(store, plan.workspaceId(), publication.publicationId());
    var rows =
        store.rows("SELECT job_id FROM index_publications WHERE id=?", publication.publicationId());
    if (!actual.equals(publication)
        || rows.size() != 1
        || !plan.jobId().equals(AuthorityRows.text(rows.getFirst(), "job_id"))
        || !freezeVectorPlan(plan.jobId()).equals(plan)) {
      throw ModelValues.invalid();
    }
    String now = Instant.now().toString();
    String batch = modelRebuildIdForJob(plan.jobId()).orElse(null);
    var images = new ImageVectorRepository(store);
    for (var binding : plan.images()) {
      String physical =
          VectorBindingRows.physical(store, publication, binding.origin().imageEvidenceId(), true);
      String from = plan.basePublication().publicationId();
      images.insertBinding(
          new ImageVectorBinding(
              publication,
              binding.origin(),
              physical,
              from,
              VectorBindingIdentity.imageSha256(
                  publication,
                  binding.origin(),
                  physical,
                  from,
                  batch == null ? binding.modelRebuildId() : batch),
              batch == null ? binding.modelRebuildId() : batch),
          now);
    }
    var audios = new AudioVectorRepository(store);
    for (var binding : plan.audios()) {
      var physical = new ArrayList<String>();
      for (var entry : binding.origin().entries()) {
        physical.add(
            VectorBindingRows.physical(store, publication, entry.audioEvidenceId(), false));
      }
      String from = plan.basePublication().publicationId();
      audios.insertBinding(
          new AudioVectorBinding(
              publication,
              binding.origin(),
              physical,
              from,
              VectorBindingIdentity.audioSha256(
                  publication,
                  binding.origin(),
                  physical,
                  from,
                  batch == null ? binding.modelRebuildId() : batch),
              batch == null ? binding.modelRebuildId() : batch),
          now);
    }
  }

  public boolean hasProcessing() {
    return store.count(
            "SELECT COUNT(*) FROM indexing_jobs j WHERE state='processing' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=j.document_id)")
        != 0;
  }

  public List<String> queuedIds(String workspaceId) {
    return store
        .rows(
            "SELECT j.id FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state='queued' AND j.model_rebuild_id IS NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id) ORDER BY j.created_at,j.id",
            workspaceId)
        .stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }

  public Optional<TaskEntity> findInternalTask(String jobId) {
    return store
        .rows(
            "SELECT j.*,d.workspace_id,COALESCE(o.filename,d.filename) filename FROM indexing_jobs j JOIN documents d ON d.id=j.document_id LEFT JOIN document_original_revisions o ON o.revision_id=j.revision_id AND o.document_id=d.id WHERE j.id=?",
            jobId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, true));
  }

  public Optional<TaskEntity> findAuthorizedTask(Actor actor, String jobId, boolean edit) {
    return store
        .rows(
            "SELECT j.*,COALESCE(o.filename,d.filename) filename,'member' AS current_role FROM indexing_jobs j JOIN documents d ON d.id=j.document_id LEFT JOIN document_original_revisions o ON o.revision_id=j.revision_id AND o.document_id=d.id WHERE j.id=? AND d.workspace_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
            jobId,
            actor.workspaceId())
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, true));
  }

  public boolean sourceCurrent(TaskEntity job) {
    if (modelRebuildIdForJob(job.id()).isPresent()) {
      return new ModelRebuildRepository(store).currentSource(job.id());
    }
    var replacement = new DocumentUpdateRepository(store).replacementIdForIndexJob(job.id());
    if (replacement.isPresent()) {
      var updates = new DocumentUpdateRepository(store);
      if (!updates.sourceCurrent(replacement.get())) {
        return false;
      }
      var candidate = updates.find(replacement.get()).orElseThrow(ModelValues::invalid);
      var parsed = parsedRevision(job.documentId(), job.revisionId());
      if (!candidate.candidateRevisionId().equals(job.revisionId())
          || parsed.isEmpty()
          || !parsed.get().sourceSha256().equals(job.sourceSha256())
          || !parsed.get().parserRevision().equals(job.parserRevision())) {
        return false;
      }
      var base = activePublication(job.documentId());
      if (candidate.basePublicationId() == null) {
        return base.isEmpty();
      }
      return base.isPresent()
          && base.get().id().equals(candidate.basePublicationId())
          && base.get().target().equals(job.target());
    }

    boolean metadataCurrent =
        store.count(
                "SELECT COUNT(*) FROM corpus_documents c JOIN corpus_revisions r ON r.document_id=c.document_id AND r.id=c.parsed_revision_id JOIN documents d ON d.id=c.document_id JOIN ingestion_jobs p ON p.document_id=d.id AND p.revision_id=r.id WHERE c.document_id=? AND r.id=? AND r.source_sha256=? AND d.source_sha256=r.source_sha256 AND r.parser_revision=? AND r.parsed_at IS NOT NULL AND p.state='parsed' AND "
                    + PROJECTION_COUNT
                    + " BETWEEN 1 AND 4096 AND r.segment_count=(SELECT COUNT(*) FROM corpus_segments s WHERE s.revision_id=r.id) AND EXISTS(SELECT 1 FROM indexing_jobs j WHERE j.id=? AND j.document_id=c.document_id AND ((j.rebuild_sequence=0 AND j.base_publication_id IS NULL AND NOT EXISTS(SELECT 1 FROM active_corpus_publications a WHERE a.document_id=d.id)) OR (j.rebuild_sequence>0 AND EXISTS(SELECT 1 FROM active_corpus_publications a JOIN index_publications base ON base.id=a.publication_id WHERE a.document_id=d.id AND a.publication_id=j.base_publication_id AND a.revision_id=r.id AND base.source_sha256=r.source_sha256 AND base.parser_revision=r.parser_revision AND base.embedding_identity=j.embedding_identity AND base.projection_identity=j.projection_identity AND base.model_revision=j.model_revision AND base.dimensions=j.dimensions AND (j.base_vector_set_sha256 IS NOT NULL OR (NOT EXISTS(SELECT 1 FROM image_vector_publications WHERE publication_id=a.publication_id) AND NOT EXISTS(SELECT 1 FROM audio_vector_publications WHERE publication_id=a.publication_id) AND NOT EXISTS(SELECT 1 FROM image_vector_bindings WHERE publication_id=a.publication_id) AND NOT EXISTS(SELECT 1 FROM audio_vector_bindings WHERE publication_id=a.publication_id))))))) AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
                job.documentId(),
                job.revisionId(),
                job.sourceSha256(),
                job.parserRevision(),
                job.id())
            == 1;
    if (!metadataCurrent) {
      return false;
    }
    var base = basePublicationId(job.id());
    if (base.isEmpty()) {
      return true;
    }
    var publication = activePublication(job.documentId());
    if (publication.isEmpty()
        || !publication.get().id().equals(base.get())
        || !publication.get().target().equals(job.target())
        || !publicationCurrent(publication.get())) {
      return false;
    }
    if (baseVectorSetSha256(job.id()).isPresent()) {
      try {
        freezeVectorPlan(job.id());
      } catch (ApplicationException corrupt) {
        return false;
      }
    }
    return true;
  }

  /** Complete recall snapshot; text locations are validated before leaving their source type. */
  public List<ProjectionItem> projectionItems(String revisionId) {
    var items = new ArrayList<ProjectionItem>();
    for (var segment : segments(revisionId)) {
      items.add(ProjectionItem.fromText(segment));
    }
    for (var row :
        store.rows("SELECT * FROM image_evidence WHERE revision_id=? ORDER BY id", revisionId)) {
      var image =
          new ImageEvidence(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "revision_id"),
              AuthorityRows.integer(row, "width"),
              AuthorityRows.integer(row, "height"),
              AuthorityRows.text(row, "recall_text"),
              AuthorityRows.text(row, "recall_sha256"),
              AuthorityRows.text(row, "description_revision"));
      items.add(
          new ProjectionItem(image.id(), items.size(), image.recallText(), image.recallSha256()));
    }
    for (var row :
        store.rows(
            "SELECT * FROM audio_spans WHERE revision_id=? AND index_ordinal IS NOT NULL ORDER BY index_ordinal",
            revisionId)) {
      var audio =
          new AudioEvidence(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "revision_id"),
              AuthorityRows.integer(row, "ordinal"),
              AuthorityRows.integer(row, "start_ms"),
              AuthorityRows.integer(row, "end_ms"),
              AuthorityRows.text(row, "text"),
              AuthorityRows.text(row, "text_sha256"),
              AuthorityRows.integer(row, "index_ordinal"));
      items.add(new ProjectionItem(audio.id(), items.size(), audio.text(), audio.textSha256()));
    }
    // Recall does not need the sealed original frame bytes; those remain in video authority.
    for (var row :
        store.rows(
            "SELECT id,revision_id,ordinal,recall_text,recall_sha256,description_revision FROM video_frames WHERE revision_id=? AND recall_text IS NOT NULL ORDER BY ordinal",
            revisionId)) {
      String id = AuthorityRows.text(row, "id");
      if (!VideoEvidence.frameIdentity(
              AuthorityRows.text(row, "revision_id"), AuthorityRows.integer(row, "ordinal"))
          .equals(id)) {
        throw ModelValues.invalid();
      }
      var recall =
          new ImageRecall(
              AuthorityRows.text(row, "recall_text"),
              AuthorityRows.text(row, "description_revision"));
      items.add(
          new ProjectionItem(
              id, items.size(), recall.recallText(), AuthorityRows.text(row, "recall_sha256")));
    }
    for (var row :
        store.rows(
            "SELECT * FROM video_transcript_spans WHERE revision_id=? AND index_ordinal IS NOT NULL ORDER BY index_ordinal",
            revisionId)) {
      var transcript =
          new VideoTranscriptEvidence(
              AuthorityRows.text(row, "id"),
              AuthorityRows.text(row, "revision_id"),
              new AudioTranscriptSpan(
                  AuthorityRows.integer(row, "ordinal"),
                  AuthorityRows.integer(row, "start_ms"),
                  AuthorityRows.integer(row, "end_ms"),
                  AuthorityRows.text(row, "text")),
              AuthorityRows.integer(row, "index_ordinal"));
      items.add(
          new ProjectionItem(
              transcript.id(),
              items.size(),
              transcript.span().text(),
              AuthorityRows.text(row, "text_sha256")));
    }
    for (var row :
        store.rows(
            "SELECT s.* FROM video_ocr_segments s JOIN video_frame_ocr f ON f.frame_id=s.frame_id WHERE s.revision_id=? ORDER BY f.ordinal,s.ordinal",
            revisionId)) {
      var segment =
          new VideoOcrSegment(
              AuthorityRows.integer(row, "ordinal"),
              AuthorityRows.integer(row, "start_offset"),
              AuthorityRows.integer(row, "end_offset"),
              AuthorityRows.text(row, "text"));
      var evidence =
          new VideoOcrSegmentEvidence(
              AuthorityRows.text(row, "id"),
              revisionId,
              AuthorityRows.text(row, "frame_id"),
              segment);
      items.add(
          new ProjectionItem(
              evidence.id(), items.size(), segment.text(), AuthorityRows.text(row, "text_sha256")));
    }
    if (store.count(
            "SELECT COUNT(*) FROM video_subtitle_compilations WHERE revision_id=?", revisionId)
        != 0) {
      var subtitles =
          IngestionRepository.readVideoSubtitleEvidence(store, revisionId)
              .orElseThrow(ModelValues::invalid);
      for (var track : subtitles.tracks()) {
        for (var cue : track.cues()) {
          if (cue.indexOrdinal() != null) {
            items.add(
                new ProjectionItem(cue.id(), items.size(), cue.cue().text(), cue.textSha256()));
          }
        }
      }
    }
    return List.copyOf(items);
  }

  private List<IndexSegment> segments(String revisionId) {
    return store
        .rows("SELECT * FROM corpus_segments WHERE revision_id=? ORDER BY ordinal", revisionId)
        .stream()
        .map(
            row ->
                new IndexSegment(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.integer(row, "ordinal"),
                    AuthorityRows.integer(row, "page_number"),
                    AuthorityRows.integer(row, "start_offset"),
                    AuthorityRows.integer(row, "end_offset"),
                    AuthorityRows.text(row, "text"),
                    AuthorityRows.text(row, "text_sha256")))
        .toList();
  }

  public void insertAttempt(String jobId, int attempt, String generation, String now) {
    store.execute(
        "INSERT INTO indexing_attempts(job_id,attempt,projection_generation_id,started_at) VALUES(?,?,?,?)",
        jobId,
        attempt,
        generation,
        now);
  }

  public void markProcessing(String jobId, String tokenHash, String generation, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='processing',claim_token_sha256=?,projection_generation_id=?,updated_at=? WHERE id=? AND state='queued'",
        tokenHash,
        generation,
        now,
        jobId);
  }

  public boolean attemptExists(String jobId, int attempt, String generation) {
    return store.count(
            "SELECT COUNT(*) FROM indexing_attempts WHERE job_id=? AND attempt=? AND projection_generation_id=?",
            jobId,
            attempt,
            generation)
        == 1;
  }

  public void insertPublication(IndexPublicationEntity publication) {
    var target = publication.target();
    store.execute(
        "INSERT INTO index_publications(id,job_id,document_id,revision_id,attempt,projection_generation_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,manifest_sha256,segment_count,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        publication.id(),
        publication.jobId(),
        publication.documentId(),
        publication.revisionId(),
        publication.attempt(),
        publication.projectionGenerationId(),
        publication.sourceSha256(),
        publication.parserRevision(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        publication.manifestSha256(),
        publication.segmentCount(),
        publication.createdAt());
  }

  public void insertPublicationEntry(
      String publicationId, String evidenceId, String physicalSegmentId, String digest) {
    if (store.count(
            "SELECT COUNT(*) FROM image_evidence i JOIN index_publications p ON p.revision_id=i.revision_id WHERE p.id=? AND i.id=?",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO image_publication_entries(publication_id,image_evidence_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM audio_spans s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=? AND s.index_ordinal IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO audio_publication_entries(publication_id,audio_span_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_frames f JOIN index_publications p ON p.revision_id=f.revision_id WHERE p.id=? AND f.id=? AND f.recall_text IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_frame_publication_entries(publication_id,video_frame_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_transcript_spans s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=? AND s.index_ordinal IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_transcript_publication_entries(publication_id,video_transcript_span_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_ocr_segments s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=?",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_ocr_publication_entries(publication_id,video_ocr_segment_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else if (store.count(
            "SELECT COUNT(*) FROM video_subtitle_cues s JOIN index_publications p ON p.revision_id=s.revision_id WHERE p.id=? AND s.id=? AND s.index_ordinal IS NOT NULL",
            publicationId,
            evidenceId)
        == 1) {
      store.execute(
          "INSERT INTO video_subtitle_publication_entries(publication_id,video_subtitle_cue_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    } else {
      store.execute(
          "INSERT INTO index_publication_entries(publication_id,source_segment_id,physical_segment_id,entry_sha256) VALUES(?,?,?,?)",
          publicationId,
          evidenceId,
          physicalSegmentId,
          digest);
    }
  }

  public void activatePublication(String documentId, String publicationId, String revisionId) {
    var replacement =
        store.rows(
            "SELECT u.* FROM document_replacements u JOIN indexing_jobs j ON j.replacement_id=u.id JOIN index_publications p ON p.job_id=j.id WHERE p.id=? AND p.document_id=? AND p.revision_id=?",
            publicationId,
            documentId,
            revisionId);
    if (!replacement.isEmpty()) {
      var row = replacement.getFirst();
      String previous = AuthorityRows.text(row, "base_publication_id");
      if (previous == null) {
        store.execute(
            "INSERT INTO active_corpus_publications(document_id,publication_id,revision_id) VALUES(?,?,?)",
            documentId,
            publicationId,
            revisionId);
      } else {
        store.execute(
            "UPDATE active_corpus_publications SET publication_id=?,revision_id=? WHERE document_id=? AND publication_id=? AND revision_id=?",
            publicationId,
            revisionId,
            documentId,
            previous,
            AuthorityRows.text(row, "base_revision_id"));
        if (store.count("SELECT changes()") != 1) {
          throw ModelValues.invalid();
        }
        store.execute(
            "UPDATE synopsis_tasks SET state='unavailable',error_code='source_changed',claim_token_sha256=NULL,updated_at=? WHERE publication_id=? AND document_id=? AND state IN ('queued','processing')",
            Instant.now().toString(),
            previous,
            documentId);
      }
      new DocumentUpdateRepository(store)
          .activate(AuthorityRows.text(row, "id"), publicationId, Instant.now().toString());
      return;
    }

    var base =
        store.rows(
            "SELECT j.base_publication_id FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id WHERE p.id=? AND p.document_id=? AND p.revision_id=?",
            publicationId,
            documentId,
            revisionId);
    if (base.size() != 1) {
      throw ModelValues.invalid();
    }
    String previous = AuthorityRows.text(base.getFirst(), "base_publication_id");
    if (previous == null) {
      store.execute(
          "INSERT INTO active_corpus_publications(document_id,publication_id,revision_id) VALUES(?,?,?)",
          documentId,
          publicationId,
          revisionId);
    } else {
      store.execute(
          "UPDATE active_corpus_publications SET publication_id=? WHERE document_id=? AND publication_id=? AND revision_id=?",
          publicationId,
          documentId,
          previous,
          revisionId);
      if (store.count("SELECT changes()") != 1) {
        throw ModelValues.invalid();
      }
      store.execute(
          "UPDATE synopsis_tasks SET state='unavailable',error_code='source_changed',claim_token_sha256=NULL,updated_at=? WHERE publication_id=? AND document_id=? AND state IN ('queued','processing')",
          Instant.now().toString(),
          previous,
          documentId);
    }
  }

  private void replacementState(String jobId, String state, String now) {
    new DocumentUpdateRepository(store)
        .replacementIdForIndexJob(jobId)
        .ifPresent(id -> new DocumentUpdateRepository(store).state(id, state, now));
  }

  public void markIndexed(String jobId, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='indexed',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
  }

  public void markFailed(String jobId, String code, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='failed',claim_token_sha256=NULL,error_code=?,updated_at=? WHERE id=?",
        code,
        now,
        jobId);
    replacementState(jobId, "failed", now);
  }

  /** Pending non-rebuild index jobs of every revision of a document, including replacements. */
  public List<String> pendingJobIds(String documentId) {
    return store
        .rows(
            "SELECT id FROM indexing_jobs WHERE document_id=? AND model_rebuild_id IS NULL AND state IN ('queued','processing') ORDER BY id",
            documentId)
        .stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }

  public void markCancelled(String jobId, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='cancelled',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
    replacementState(jobId, "cancelled", now);
  }

  public void markQueued(String jobId, int attempt, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='queued',attempt=?,claim_token_sha256=NULL,projection_generation_id=NULL,error_code=NULL,updated_at=? WHERE id=?",
        attempt,
        now,
        jobId);
    replacementState(jobId, "indexing", now);
  }

  public String publicationId(String jobId) {
    var rows = store.rows("SELECT id FROM index_publications WHERE job_id=?", jobId);
    return rows.isEmpty() ? null : AuthorityRows.text(rows.getFirst(), "id");
  }

  public Optional<String> documentJobId(String documentId) {
    return store
        .rows(
            "SELECT j.id FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE j.document_id=? AND j.revision_id=d.active_revision_id AND (j.model_rebuild_id IS NULL OR j.state='indexed') ORDER BY j.rebuild_sequence DESC,j.id DESC LIMIT 1",
            documentId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "id"));
  }

  public List<String> processingIds() {
    return store.rows("SELECT id FROM indexing_jobs WHERE state='processing'").stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }
}
