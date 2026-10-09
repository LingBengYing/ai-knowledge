package com.evidence.rag.repository;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.model.entity.ModelRebuildEntity;
import com.evidence.rag.model.entity.ModelRebuildItemEntity;
import com.evidence.rag.model.entity.TextRuntimeSelectionEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Whole-corpus candidate publications and one atomic publication/configuration selection. */
public final class ModelRebuildRepository {
  private final SqliteAuthorityStore store;

  public ModelRebuildRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<ModelRebuildEntity> current(String workspace) {
    return store
        .rows(
            "SELECT * FROM model_rebuilds WHERE workspace_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1",
            workspace)
        .stream()
        .findFirst()
        .map(this::entity);
  }

  public Optional<ModelRebuildEntity> find(String id) {
    return store.rows("SELECT * FROM model_rebuilds WHERE id=?", id).stream()
        .findFirst()
        .map(this::entity);
  }

  public List<ModelRebuildItemEntity> items(String batchId) {
    return store
        .rows("SELECT * FROM model_rebuild_items WHERE batch_id=? ORDER BY ordinal", batchId)
        .stream()
        .map(ModelRebuildRepository::item)
        .toList();
  }

  public boolean hasPending(String workspace) {
    return store.count(
            "SELECT COUNT(*) FROM model_rebuilds WHERE workspace_id=? AND state IN ('queued','running','applying')",
            workspace)
        != 0;
  }

  public boolean hasPending() {
    return store.count(
            "SELECT COUNT(*) FROM model_rebuilds WHERE state IN ('queued','running','applying')")
        != 0;
  }

  public boolean registeredTarget(QualifiedProjectionTarget target) {
    return target != null
        && store.count(
                "SELECT COUNT(*) FROM model_rebuilds WHERE workspace_id=? AND embedding_identity=? AND projection_identity=? AND dimensions=?",
                target.workspaceId(),
                target.embeddingIdentity(),
                target.projectionIdentity(),
                target.dimensions())
            != 0;
  }

  public boolean canStart(String workspace) {
    if (hasPending(workspace)
        || store.count(
                "SELECT (SELECT COUNT(*) FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state IN ('queued','processing'))+(SELECT COUNT(*) FROM indexing_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state IN ('queued','processing','prepared'))+(SELECT COUNT(*) FROM document_replacements u JOIN documents d ON d.id=u.document_id WHERE d.workspace_id=? AND u.state IN ('stored','queued','processing','parsed','indexing'))+(SELECT COUNT(*) FROM document_cleanups WHERE workspace_id=? AND state!='completed')",
                workspace,
                workspace,
                workspace,
                workspace)
            != 0) {
      return false;
    }
    try {
      snapshot(workspace);
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public List<ModelRebuildItemEntity> snapshot(String workspace) {
    var indexing = new IndexingRepository(store);
    var originals = new DocumentUpdateRepository(store);
    var result = new ArrayList<ModelRebuildItemEntity>();
    for (var row :
        store.rows(
            "SELECT d.id,c.parsed_revision_id FROM documents d JOIN corpus_documents c ON c.document_id=d.id WHERE d.workspace_id=? AND c.parsed_revision_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id) ORDER BY d.id",
            workspace)) {
      String document = AuthorityRows.text(row, "id");
      String revisionId = AuthorityRows.text(row, "parsed_revision_id");
      var parsed = indexing.parsedRevision(document);
      if (parsed.isEmpty()) {
        continue;
      }
      var revision = parsed.get();
      var original = originals.original(document, revisionId).orElseThrow(ModelValues::invalid);
      if (!revision.id().equals(revisionId)
          || !original.sourceSha256().equals(revision.sourceSha256())
          || indexing.projectionItems(revisionId).isEmpty()) {
        throw ModelValues.invalid();
      }
      var base = indexing.activePublication(document).orElse(null);
      String vectorHash =
          ModelValues.sha256(
              "java-model-rebuild-empty-vectors-v1".getBytes(StandardCharsets.UTF_8));
      if (base != null) {
        vectorHash = indexing.vectorPlanForBase("snapshot", workspace, base.id()).setSha256();
      }
      result.add(
          new ModelRebuildItemEntity(
              "snapshot",
              result.size(),
              document,
              revisionId,
              revision.sourceSha256(),
              revision.parserRevision(),
              base == null ? null : base.id(),
              vectorHash,
              "snapshot",
              null,
              "queued"));
    }
    long active =
        store.count(
            "SELECT COUNT(*) FROM active_corpus_publications a JOIN documents d ON d.id=a.document_id WHERE d.workspace_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones WHERE document_id=d.id)",
            workspace);
    if (active != result.stream().filter(item -> item.basePublicationId() != null).count()) {
      throw ModelValues.invalid();
    }
    return List.copyOf(result);
  }

  public void insert(ModelRebuildEntity batch, List<ModelRebuildItemEntity> items) {
    if (!canStart(batch.workspaceId())
        || !batch.baseSelection().equals(new TextRuntimeSelectionRepository(store).read())
        || !"queued".equals(batch.state())
        || batch.totalDocuments() != items.size()
        || batch.completedDocuments() != 0
        || !sameMaterials(snapshot(batch.workspaceId()), items)) {
      throw ModelValues.invalid();
    }
    var base = batch.baseSelection();
    var target = batch.target();
    store.execute(
        "INSERT INTO model_rebuilds(id,workspace_id,created_by,base_active_version,base_configuration_sha256,base_anchor_sha256,base_batch_id,base_selected_at,target_version,configuration_sha256,anchor_sha256,embedding_identity,projection_identity,model_revision,dimensions,state,total_documents,error_code,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'queued',?,NULL,?,?)",
        batch.id(),
        batch.workspaceId(),
        batch.createdBy(),
        base.activeVersion(),
        base.configurationSha256(),
        base.anchorSha256(),
        base.batchId(),
        base.updatedAt(),
        batch.targetVersion(),
        batch.configurationSha256(),
        batch.anchorSha256(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        items.size(),
        batch.createdAt(),
        batch.updatedAt());
    for (var item : items) {
      if (!batch.id().equals(item.batchId())
          || item.publicationId() != null
          || !"queued".equals(item.state())) {
        throw ModelValues.invalid();
      }
      store.execute(
          "INSERT INTO model_rebuild_items(batch_id,ordinal,document_id,revision_id,source_sha256,parser_revision,base_publication_id,base_vector_set_sha256,job_id,state) VALUES(?,?,?,?,?,?,?,?,?,'queued')",
          item.batchId(),
          item.ordinal(),
          item.documentId(),
          item.revisionId(),
          item.sourceSha256(),
          item.parserRevision(),
          item.basePublicationId(),
          item.baseVectorSetSha256(),
          item.jobId());
    }
  }

  public void markRunning(String id, String now) {
    store.execute(
        "UPDATE model_rebuilds SET state='running',updated_at=? WHERE id=? AND state='queued'",
        now,
        id);
  }

  public Optional<ReindexVectorPlan> plan(String jobId) {
    var found = findItemForJob(jobId).orElseThrow(ModelValues::invalid);
    if (!currentSource(jobId)) {
      throw ModelValues.invalid();
    }
    if (found.basePublicationId() == null) {
      return Optional.empty();
    }
    String workspace = find(found.batchId()).orElseThrow(ModelValues::invalid).workspaceId();
    var plan =
        new IndexingRepository(store)
            .vectorPlanForBase(jobId, workspace, found.basePublicationId());
    if (!plan.setSha256().equals(found.baseVectorSetSha256())) {
      throw ModelValues.invalid();
    }
    return Optional.of(plan);
  }

  public Optional<ModelRebuildItemEntity> findItemForJob(String jobId) {
    return store.rows("SELECT * FROM model_rebuild_items WHERE job_id=?", jobId).stream()
        .findFirst()
        .map(ModelRebuildRepository::item);
  }

  public boolean currentSource(String jobId) {
    var found = findItemForJob(jobId);
    if (found.isEmpty()) {
      return false;
    }
    var item = found.get();
    var batch = find(item.batchId()).orElseThrow(ModelValues::invalid);
    if (!List.of("queued", "running", "applying").contains(batch.state())
        || !batch.baseSelection().equals(new TextRuntimeSelectionRepository(store).read())) {
      return false;
    }
    var indexing = new IndexingRepository(store);
    var revision = indexing.parsedRevision(item.documentId());
    var base = indexing.activePublication(item.documentId());
    if (revision.isEmpty()
        || !revision.get().id().equals(item.revisionId())
        || !revision.get().sourceSha256().equals(item.sourceSha256())
        || !revision.get().parserRevision().equals(item.parserRevision())
        || !Objects.equals(item.basePublicationId(), base.map(value -> value.id()).orElse(null))) {
      return false;
    }
    try {
      var original =
          new DocumentUpdateRepository(store)
              .original(item.documentId(), item.revisionId())
              .orElseThrow(ModelValues::invalid);
      if (!original.sourceSha256().equals(item.sourceSha256())) {
        return false;
      }
      if (item.basePublicationId() != null
          && !indexing
              .vectorPlanForBase(jobId, batch.workspaceId(), item.basePublicationId())
              .setSha256()
              .equals(item.baseVectorSetSha256())) {
        return false;
      }
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  public void seal(String jobId, String publicationId, String now) {
    var item = findItemForJob(jobId).orElseThrow(ModelValues::invalid);
    var batch = find(item.batchId()).orElseThrow(ModelValues::invalid);
    if (!currentSource(jobId)
        || store.count(
                "SELECT COUNT(*) FROM index_publications p JOIN indexing_jobs j ON j.id=p.job_id WHERE p.id=? AND p.job_id=? AND p.document_id=? AND p.revision_id=? AND j.state='processing' AND p.embedding_identity=? AND p.projection_identity=? AND p.model_revision=? AND p.dimensions=?",
                publicationId,
                jobId,
                item.documentId(),
                item.revisionId(),
                batch.target().embeddingIdentity(),
                batch.target().projectionIdentity(),
                batch.target().modelRevision(),
                batch.target().dimensions())
            != 1) {
      throw ModelValues.invalid();
    }
    requireCompleteBindings(item, batch, publicationId);
    store.execute(
        "UPDATE model_rebuild_items SET publication_id=?,state='prepared' WHERE job_id=? AND state='queued'",
        publicationId,
        jobId);
    store.execute(
        "UPDATE indexing_jobs SET state='prepared',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=? AND state='processing'",
        now,
        jobId);
    store.execute("UPDATE model_rebuilds SET updated_at=? WHERE id=?", now, item.batchId());
  }

  public void fail(String id, String code, String now) {
    store.execute(
        "UPDATE indexing_jobs SET state='failed',claim_token_sha256=NULL,error_code='indexing_failed',updated_at=? WHERE model_rebuild_id=? AND state IN ('queued','processing','prepared')",
        now,
        id);
    store.execute(
        "UPDATE model_rebuild_items SET state='failed' WHERE batch_id=? AND state IN ('queued','prepared')",
        id);
    store.execute(
        "UPDATE model_rebuilds SET state='failed',error_code=?,updated_at=? WHERE id=? AND state IN ('queued','running','applying')",
        code,
        now,
        id);
  }

  public void recoverInterrupted(String now) {
    for (var row :
        store.rows(
            "SELECT id FROM model_rebuilds WHERE state IN ('queued','running','applying')")) {
      fail(AuthorityRows.text(row, "id"), "worker_interrupted", now);
    }
  }

  public void publishAll(String id, LibraryOperationGate.MaintenanceLease lease, String now) {
    var batch = find(id).orElseThrow(ModelValues::invalid);
    if (lease == null
        || !lease.belongsTo(store.operationGate())
        || !lease.isHeld()
        || !lease.isOwnerThread()
        || !List.of("queued", "running").contains(batch.state())
        || !batch.baseSelection().equals(new TextRuntimeSelectionRepository(store).read())) {
      throw ModelValues.invalid();
    }
    var planned = items(id);
    if (planned.size() != batch.totalDocuments()
        || planned.stream().anyMatch(item -> !"prepared".equals(item.state()))
        || !sameMaterials(snapshot(batch.workspaceId()), planned)) {
      throw ModelValues.invalid();
    }
    for (var item : planned) {
      requireCompleteBindings(item, batch, item.publicationId());
      if (!currentSource(item.jobId())
          || store.count(
                  "SELECT COUNT(*) FROM indexing_jobs WHERE id=? AND state='prepared'",
                  item.jobId())
              != 1) {
        throw ModelValues.invalid();
      }
    }
    store.modelRebuildScope(
        id,
        planned.stream().map(ModelRebuildItemEntity::documentId).toList(),
        () -> {
          store.execute(
              "UPDATE model_rebuilds SET state='applying',updated_at=? WHERE id=?", now, id);
          for (var item : planned) {
            if (item.basePublicationId() == null) {
              store.execute(
                  "INSERT INTO active_corpus_publications(document_id,publication_id,revision_id) VALUES(?,?,?)",
                  item.documentId(),
                  item.publicationId(),
                  item.revisionId());
            } else {
              store.execute(
                  "UPDATE active_corpus_publications SET publication_id=? WHERE document_id=? AND publication_id=? AND revision_id=?",
                  item.publicationId(),
                  item.documentId(),
                  item.basePublicationId(),
                  item.revisionId());
              if (store.count("SELECT changes()") != 1) {
                throw ModelValues.invalid();
              }
              store.execute(
                  "UPDATE synopsis_tasks SET state='unavailable',error_code='source_changed',claim_token_sha256=NULL,updated_at=? WHERE publication_id=? AND state IN ('queued','processing')",
                  now,
                  item.basePublicationId());
            }
            store.execute(
                "UPDATE indexing_jobs SET state='indexed',updated_at=? WHERE id=? AND state='prepared'",
                now,
                item.jobId());
            store.execute(
                "UPDATE model_rebuild_items SET state='published' WHERE job_id=?", item.jobId());
          }
          new TextRuntimeSelectionRepository(store)
              .select(
                  batch.baseSelection(),
                  batch.targetVersion(),
                  batch.configurationSha256(),
                  batch.anchorSha256(),
                  id,
                  now);
          store.execute(
              "UPDATE model_rebuilds SET state='completed',updated_at=? WHERE id=?", now, id);
          return null;
        });
  }

  private void requireCompleteBindings(
      ModelRebuildItemEntity item, ModelRebuildEntity batch, String publicationId) {
    var publication = VectorBindingRows.publication(store, batch.workspaceId(), publicationId);
    var images = new ImageVectorRepository(store).allBindings(batch.workspaceId(), publication);
    var audios = new AudioVectorRepository(store).allBindings(batch.workspaceId(), publication);
    var previous = plan(item.jobId()).orElse(null);
    if (previous == null) {
      if (!images.isEmpty() || !audios.isEmpty()) {
        throw ModelValues.invalid();
      }
      return;
    }
    if (images.size() != previous.images().size()
        || audios.size() != previous.audios().size()
        || images.stream()
            .anyMatch(
                binding ->
                    !batch.id().equals(binding.modelRebuildId())
                        || !item.basePublicationId().equals(binding.inheritedFromPublicationId())
                        || previous.images().stream()
                            .noneMatch(old -> old.origin().equals(binding.origin())))
        || audios.stream()
            .anyMatch(
                binding ->
                    !batch.id().equals(binding.modelRebuildId())
                        || !item.basePublicationId().equals(binding.inheritedFromPublicationId())
                        || previous.audios().stream()
                            .noneMatch(old -> old.origin().equals(binding.origin())))) {
      throw ModelValues.invalid();
    }
  }

  private static boolean sameMaterials(
      List<ModelRebuildItemEntity> current, List<ModelRebuildItemEntity> frozen) {
    if (current.size() != frozen.size()) {
      return false;
    }
    for (int i = 0; i < current.size(); i++) {
      var left = current.get(i);
      var right = frozen.get(i);
      if (left.ordinal() != right.ordinal()
          || !left.documentId().equals(right.documentId())
          || !left.revisionId().equals(right.revisionId())
          || !left.sourceSha256().equals(right.sourceSha256())
          || !left.parserRevision().equals(right.parserRevision())
          || !Objects.equals(left.basePublicationId(), right.basePublicationId())
          || !left.baseVectorSetSha256().equals(right.baseVectorSetSha256())) {
        return false;
      }
    }
    return true;
  }

  private ModelRebuildEntity entity(Map<String, Object> row) {
    String id = AuthorityRows.text(row, "id");
    int complete =
        (int)
            store.count(
                "SELECT COUNT(*) FROM model_rebuild_items WHERE batch_id=? AND state IN ('prepared','published')",
                id);
    var base =
        new TextRuntimeSelectionEntity(
            true,
            row.get("base_active_version") == null
                ? null
                : AuthorityRows.number(row, "base_active_version"),
            AuthorityRows.text(row, "base_configuration_sha256"),
            AuthorityRows.text(row, "base_anchor_sha256"),
            AuthorityRows.text(row, "base_batch_id"),
            AuthorityRows.text(row, "base_selected_at"));
    return new ModelRebuildEntity(
        id,
        AuthorityRows.text(row, "workspace_id"),
        AuthorityRows.text(row, "created_by"),
        base,
        AuthorityRows.number(row, "target_version"),
        AuthorityRows.text(row, "configuration_sha256"),
        AuthorityRows.text(row, "anchor_sha256"),
        VectorBindingRows.target(row),
        AuthorityRows.text(row, "state"),
        AuthorityRows.integer(row, "total_documents"),
        complete,
        AuthorityRows.text(row, "error_code"),
        AuthorityRows.text(row, "created_at"),
        AuthorityRows.text(row, "updated_at"));
  }

  private static ModelRebuildItemEntity item(Map<String, Object> row) {
    return new ModelRebuildItemEntity(
        AuthorityRows.text(row, "batch_id"),
        AuthorityRows.integer(row, "ordinal"),
        AuthorityRows.text(row, "document_id"),
        AuthorityRows.text(row, "revision_id"),
        AuthorityRows.text(row, "source_sha256"),
        AuthorityRows.text(row, "parser_revision"),
        AuthorityRows.text(row, "base_publication_id"),
        AuthorityRows.text(row, "base_vector_set_sha256"),
        AuthorityRows.text(row, "job_id"),
        AuthorityRows.text(row, "publication_id"),
        AuthorityRows.text(row, "state"));
  }
}
