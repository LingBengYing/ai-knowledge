package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.CleanupClaim;
import com.evidence.rag.model.domain.CleanupPage;
import com.evidence.rag.model.domain.CleanupPayload;
import com.evidence.rag.model.domain.CleanupPlan;
import com.evidence.rag.model.domain.CleanupResource;
import com.evidence.rag.model.domain.DocumentCleanupState;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.ProjectionInventory;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Typed cleanup ledger and closed body inventory; every method uses its caller's authority
 * transaction.
 */
public final class DocumentCleanupRepository {
  private final SqliteAuthorityStore store;

  public DocumentCleanupRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public Optional<DocumentCleanupState> find(Actor actor, String documentId) {
    var rows =
        store.rows(
            """
        SELECT t.requested_at FROM document_tombstones t JOIN documents d ON d.id=t.document_id
        JOIN document_acl a ON a.document_id=d.id
        WHERE d.id=? AND d.workspace_id=? AND a.principal_id=? AND a.role IN ('owner','editor')
        """,
            documentId,
            actor.workspaceId(),
            actor.principalId());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    var jobs = store.rows("SELECT * FROM document_cleanups WHERE document_id=?", documentId);
    if (jobs.isEmpty()) {
      String time = text(rows.getFirst(), "requested_at");
      return Optional.of(
          new DocumentCleanupState(
              documentId, null, "deleting", "not_requested", time, time, null, null, List.of()));
    }
    return Optional.of(state(jobs.getFirst()));
  }

  public CleanupPage list(Actor actor, int page, int pageSize) {
    if (actor == null || page < 1 || pageSize < 1 || pageSize > 100) {
      throw ModelValues.invalid();
    }
    String scope =
        " FROM document_tombstones t JOIN documents d ON d.id=t.document_id JOIN document_acl a ON a.document_id=d.id WHERE d.workspace_id=? AND a.principal_id=? AND a.role IN ('owner','editor')";
    long total = store.count("SELECT COUNT(*)" + scope, actor.workspaceId(), actor.principalId());
    var items = new ArrayList<DocumentCleanupState>();
    for (var row :
        store.rows(
            "SELECT d.id" + scope + " ORDER BY t.requested_at DESC,d.id LIMIT ? OFFSET ?",
            actor.workspaceId(),
            actor.principalId(),
            pageSize,
            ((long) page - 1) * pageSize)) {
      items.add(find(actor, text(row, "id")).orElseThrow(ModelValues::notFound));
    }
    return new CleanupPage(items, total, page, pageSize);
  }

  public boolean idle(String documentId) {
    for (String table : List.of("ingestion_jobs", "indexing_jobs", "synopsis_tasks")) {
      if (store.count(
              "SELECT COUNT(*) FROM "
                  + table
                  + " WHERE document_id=? AND state IN ('queued','processing')",
              documentId)
          != 0) {
        return false;
      }
    }
    return true;
  }

  public DocumentCleanupState create(Actor actor, String documentId, String now) {
    var existing = find(actor, documentId).orElseThrow(ModelValues::notFound);
    if (existing.cleanupId() != null) {
      return existing;
    }
    if (!idle(documentId)) {
      throw ModelValues.invalid();
    }
    String id = UUID.randomUUID().toString();
    store.execute(
        """
        INSERT INTO document_cleanups(id,document_id,workspace_id,source_sha256,requested_by,requested_at,updated_at,state)
        SELECT ?,d.id,d.workspace_id,d.source_sha256,?,?,?,'pending' FROM documents d WHERE d.id=?
        """,
        id,
        actor.principalId(),
        now,
        now,
        documentId);
    for (String kind : CleanupResource.KINDS) {
      store.execute(
          "INSERT INTO cleanup_resources(cleanup_id,kind,status) VALUES(?,?,'pending')", id, kind);
    }
    return find(actor, documentId).orElseThrow(ModelValues::notFound);
  }

  public Optional<CleanupClaim> claimNext(String now) {
    var pending =
        store.rows(
            "SELECT * FROM document_cleanups WHERE state='pending' ORDER BY requested_at,id LIMIT 1");
    if (pending.isEmpty()) {
      return Optional.empty();
    }
    var row = pending.getFirst();
    String token = UUID.randomUUID().toString();
    store.execute(
        "UPDATE document_cleanups SET state='running',claim_sha256=?,updated_at=?,error_code=NULL WHERE id=? AND state='pending'",
        hash(token),
        now,
        text(row, "id"));
    return Optional.of(
        new CleanupClaim(
            text(row, "id"), text(row, "document_id"), text(row, "workspace_id"), token));
  }

  public boolean current(CleanupClaim claim) {
    return claim != null
        && store.count(
                """
        SELECT COUNT(*) FROM document_cleanups c JOIN document_tombstones t ON t.document_id=c.document_id
        JOIN documents d ON d.id=c.document_id WHERE c.id=? AND c.document_id=? AND c.workspace_id=?
        AND c.claim_sha256=? AND c.state='running' AND d.workspace_id=c.workspace_id AND d.source_sha256=c.source_sha256
        """,
                claim.cleanupId(),
                claim.documentId(),
                claim.workspaceId(),
                hash(claim.claimToken()))
            == 1;
  }

  public CleanupPlan sealPlan(CleanupClaim claim) {
    requireCurrent(claim);
    var old = plan(claim.cleanupId());
    if (old.isPresent()) {
      return old.orElseThrow();
    }
    var rows = inventory(claim.documentId());
    String source =
        text(
            store
                .rows("SELECT source_sha256 FROM documents WHERE id=?", claim.documentId())
                .getFirst(),
            "source_sha256");
    String manifest =
        CleanupPlan.fingerprint(
            claim.cleanupId(), claim.documentId(), claim.workspaceId(), source, rows);
    for (var row : rows) {
      store.execute(
          "INSERT INTO cleanup_payloads(cleanup_id,table_name,row_key,body_column,size_bytes,body_sha256) VALUES(?,?,?,?,?,?)",
          claim.cleanupId(),
          row.tableName(),
          row.rowKey(),
          row.bodyColumn(),
          row.sizeBytes(),
          row.sha256());
    }
    store.execute(
        "INSERT INTO cleanup_plans(cleanup_id,document_id,workspace_id,source_sha256,manifest_sha256,payload_count) VALUES(?,?,?,?,?,?)",
        claim.cleanupId(),
        claim.documentId(),
        claim.workspaceId(),
        source,
        manifest,
        rows.size());
    return new CleanupPlan(
        claim.cleanupId(), claim.documentId(), claim.workspaceId(), source, manifest, rows);
  }

  public void setResource(
      CleanupClaim claim, String kind, String status, String safeCode, String now) {
    requireCurrent(claim);
    new CleanupResource(kind, status);
    if (safeCode != null && !safeCode.matches("[a-z][a-z0-9_]{0,63}")) {
      throw ModelValues.invalid();
    }
    if (Set.of("blocked", "failed").contains(status) && safeCode == null) {
      throw ModelValues.invalid();
    }
    store.execute(
        "UPDATE cleanup_resources SET status=? WHERE cleanup_id=? AND kind=?",
        status,
        claim.cleanupId(),
        kind);
    store.execute(
        "UPDATE document_cleanups SET updated_at=?,error_code=COALESCE(?,error_code) WHERE id=?",
        now,
        safeCode,
        claim.cleanupId());
  }

  public DocumentCleanupState finish(CleanupClaim claim, String now) {
    requireCurrent(claim);
    var resources = resources(claim.cleanupId());
    String outcome =
        resources.stream().anyMatch(r -> r.status().equals("failed"))
            ? "failed"
            : resources.stream().anyMatch(r -> r.status().equals("blocked"))
                ? "blocked"
                : resources.stream()
                        .allMatch(r -> Set.of("completed", "not_applicable").contains(r.status()))
                    ? "completed"
                    : "pending";
    String error =
        textOrNull(
            store
                .rows("SELECT error_code FROM document_cleanups WHERE id=?", claim.cleanupId())
                .getFirst(),
            "error_code");
    if (Set.of("blocked", "failed").contains(outcome) && error == null) {
      error = "cleanup_incomplete";
    }
    if (outcome.equals("completed")) {
      if (plan(claim.cleanupId()).isEmpty()) {
        throw ModelValues.invalid();
      }
      verifyPurged(claim.documentId(), claim.cleanupId());
      error = null;
    }
    store.execute(
        "UPDATE document_cleanups SET state=?,updated_at=?,completed_at=?,error_code=?,claim_sha256=NULL WHERE id=?",
        outcome,
        now,
        outcome.equals("completed") ? now : null,
        error,
        claim.cleanupId());
    return state(
        store.rows("SELECT * FROM document_cleanups WHERE id=?", claim.cleanupId()).getFirst());
  }

  public void registerProjectionAttempt(ProjectionAttempt attempt) {
    var existing =
        store.rows(
            "SELECT * FROM cleanup_projection_attempts WHERE generation_id=? AND route=?",
            attempt.generationId(),
            attempt.route());
    if (!existing.isEmpty()) {
      var prior = projection(existing.getFirst());
      var expected =
          new ProjectionAttempt(
              attempt.documentId(),
              attempt.workspaceId(),
              attempt.sourceRevisionId(),
              attempt.sourceSha256(),
              attempt.generationId(),
              attempt.route(),
              attempt.target(),
              prior.writeIssued());
      if (!prior.equals(expected)) {
        throw ModelValues.invalid();
      }
      return;
    }
    if (attempt.writeIssued()) {
      throw ModelValues.invalid();
    }
    var target = attempt.target();
    store.execute(
        """
        INSERT INTO cleanup_projection_attempts(document_id,workspace_id,source_revision_id,source_sha256,generation_id,route,
          endpoint,database_name,collection_name,embedding_identity,dimensions,projection_identity,write_issued)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0)
        """,
        attempt.documentId(),
        attempt.workspaceId(),
        attempt.sourceRevisionId(),
        attempt.sourceSha256(),
        attempt.generationId(),
        attempt.route(),
        target.endpoint(),
        target.database(),
        target.collection(),
        target.embeddingIdentity(),
        target.dimensions(),
        target.projectionIdentity());
  }

  public void markProjectionWriteIssued(String generationId, String route) {
    if (store.count(
            "SELECT COUNT(*) FROM cleanup_projection_attempts WHERE generation_id=? AND route=?",
            generationId,
            route)
        != 1) {
      throw ModelValues.invalid();
    }
    store.execute(
        "UPDATE cleanup_projection_attempts SET write_issued=1 WHERE generation_id=? AND route=?",
        generationId,
        route);
  }

  public ProjectionInventory projectionInventory(CleanupClaim claim) {
    requireCurrent(claim);
    boolean known =
        store.count(
                "SELECT COUNT(*) FROM cleanup_document_provenance WHERE document_id=? AND inventory_known=1",
                claim.documentId())
            == 1;
    var attempts =
        store
            .rows(
                "SELECT * FROM cleanup_projection_attempts WHERE document_id=? ORDER BY generation_id,route",
                claim.documentId())
            .stream()
            .map(DocumentCleanupRepository::projection)
            .toList();
    // Every successful old projection without an exact registered attempt keeps the inventory
    // unknown.
    for (String query :
        List.of(
            "SELECT COUNT(*) FROM indexing_attempts a JOIN indexing_jobs j ON j.id=a.job_id WHERE j.document_id=? AND NOT EXISTS(SELECT 1 FROM cleanup_projection_attempts c WHERE c.document_id=j.document_id AND c.generation_id=a.projection_generation_id AND c.route='legacy' AND c.source_revision_id=j.revision_id AND c.source_sha256=j.source_sha256 AND c.projection_identity=j.projection_identity AND c.embedding_identity=j.embedding_identity AND c.dimensions=j.dimensions)",
            "SELECT COUNT(*) FROM image_vector_publications p WHERE p.document_id=? AND NOT EXISTS(SELECT 1 FROM cleanup_projection_attempts c WHERE c.document_id=p.document_id AND c.generation_id=p.vector_generation_id AND c.route='image' AND c.source_revision_id=p.source_revision_id AND c.source_sha256=p.source_sha256 AND c.projection_identity=p.projection_identity AND c.embedding_identity=p.embedding_identity AND c.dimensions=p.dimensions AND c.write_issued=1)",
            "SELECT COUNT(*) FROM audio_vector_publications p WHERE p.document_id=? AND NOT EXISTS(SELECT 1 FROM cleanup_projection_attempts c WHERE c.document_id=p.document_id AND c.generation_id=p.vector_generation_id AND c.route='audio' AND c.source_revision_id=p.source_revision_id AND c.source_sha256=p.source_sha256 AND c.projection_identity=p.projection_identity AND c.embedding_identity=p.embedding_identity AND c.dimensions=p.dimensions AND c.write_issued=1)",
            "SELECT COUNT(*) FROM sound_publications p WHERE p.document_id=? AND NOT EXISTS(SELECT 1 FROM cleanup_projection_attempts c WHERE c.document_id=p.document_id AND c.generation_id=p.generation_id AND c.route='sound' AND c.source_revision_id=p.source_revision_id AND c.source_sha256=p.source_sha256 AND c.projection_identity=p.projection_identity AND c.embedding_identity=p.embedding_identity AND c.dimensions=p.dimensions AND c.write_issued=1)",
            "SELECT COUNT(*) FROM video_av_publications p WHERE p.document_id=? AND p.visual_entry_count>0 AND NOT EXISTS(SELECT 1 FROM cleanup_projection_attempts c WHERE c.document_id=p.document_id AND c.generation_id=p.id AND c.route='video_av_visual' AND c.source_revision_id=p.source_revision_id AND c.source_sha256=p.source_sha256 AND c.projection_identity=p.visual_projection_identity AND c.embedding_identity=p.visual_embedding_identity AND c.dimensions=p.visual_dimensions AND c.write_issued=1)",
            "SELECT COUNT(*) FROM video_av_publications p WHERE p.document_id=? AND p.audio_entry_count>0 AND NOT EXISTS(SELECT 1 FROM cleanup_projection_attempts c WHERE c.document_id=p.document_id AND c.generation_id=p.id AND c.route='video_av_audio' AND c.source_revision_id=p.source_revision_id AND c.source_sha256=p.source_sha256 AND c.projection_identity=p.audio_projection_identity AND c.embedding_identity=p.audio_embedding_identity AND c.dimensions=p.audio_dimensions AND c.write_issued=1)")) {
      known &= store.count(query, claim.documentId()) == 0;
    }
    return new ProjectionInventory(known, attempts);
  }

  Optional<CleanupPlan> plan(String cleanupId) {
    var headers = store.rows("SELECT * FROM cleanup_plans WHERE cleanup_id=?", cleanupId);
    if (headers.isEmpty()) {
      return Optional.empty();
    }
    var head = headers.getFirst();
    var rows =
        store
            .rows(
                "SELECT * FROM cleanup_payloads WHERE cleanup_id=? ORDER BY table_name,row_key,body_column",
                cleanupId)
            .stream()
            .map(
                r ->
                    new CleanupPayload(
                        text(r, "table_name"),
                        text(r, "row_key"),
                        text(r, "body_column"),
                        number(r, "size_bytes"),
                        text(r, "body_sha256")))
            .toList();
    if (rows.size() != number(head, "payload_count")) {
      throw ModelValues.invalid();
    }
    return Optional.of(
        new CleanupPlan(
            cleanupId,
            text(head, "document_id"),
            text(head, "workspace_id"),
            text(head, "source_sha256"),
            text(head, "manifest_sha256"),
            rows));
  }

  List<CleanupPayload> inventory(String documentId) {
    var payloads = new ArrayList<CleanupPayload>();
    for (var table : CleanupPayloadTables.TABLES) {
      for (var row :
          store.rows(
              "SELECT p.*,"
                  + table.rowKey("p")
                  + " AS cleanup_row_key FROM "
                  + table.name()
                  + " p WHERE "
                  + table.document("p")
                  + "=?",
              documentId)) {
        for (var body : table.bodies()) {
          Object value = row.get(body.name());
          byte[] bytes =
              value instanceof byte[] binary
                  ? binary
                  : ((String) value).getBytes(StandardCharsets.UTF_8);
          payloads.add(
              new CleanupPayload(
                  table.name(),
                  text(row, "cleanup_row_key"),
                  body.name(),
                  bytes.length,
                  ModelValues.sha256(bytes)));
        }
      }
    }
    return CleanupPlan.ordered(payloads);
  }

  Set<String> validatePurge(CleanupClaim claim, CleanupPlan offered) {
    requireCurrent(claim);
    CleanupPlan saved = plan(claim.cleanupId()).orElseThrow(ModelValues::invalid);
    if (!saved.equals(offered)
        || !saved.documentId().equals(claim.documentId())
        || !saved.workspaceId().equals(claim.workspaceId())) {
      throw ModelValues.invalid();
    }
    var keys = new HashSet<String>();
    var current = inventory(claim.documentId());
    if (current.size() != saved.payloadRows().size()) {
      throw ModelValues.invalid();
    }
    for (int i = 0; i < current.size(); i++) {
      var now = current.get(i);
      var before = saved.payloadRows().get(i);
      if (!now.tableName().equals(before.tableName())
          || !now.rowKey().equals(before.rowKey())
          || !now.bodyColumn().equals(before.bodyColumn())) {
        throw ModelValues.invalid();
      }
      var table =
          CleanupPayloadTables.TABLES.stream()
              .filter(t -> t.name().equals(now.tableName()))
              .findFirst()
              .orElseThrow();
      boolean purged =
          store.count(
                  "SELECT payload_purged FROM "
                      + table.name()
                      + " p WHERE "
                      + table.rowKey("p")
                      + "=?",
                  now.rowKey())
              == 1;
      if (!purged && !now.equals(before)) {
        throw ModelValues.invalid();
      }
      if (purged
          && store.count(
                  "SELECT COUNT(*) FROM "
                      + table.name()
                      + " p WHERE "
                      + table.rowKey("p")
                      + "=? AND "
                      + table.empty("p"),
                  now.rowKey())
              != 1) {
        throw ModelValues.invalid();
      }
      keys.add(now.tableName() + "\n" + now.rowKey());
    }
    return Set.copyOf(keys);
  }

  void purgeBodies(CleanupClaim claim) {
    requireCurrent(claim);
    for (var table : CleanupPayloadTables.TABLES) {
      String set =
          table.bodies().stream()
              .map(b -> b.name() + "=" + b.empty())
              .collect(java.util.stream.Collectors.joining(","));
      store.execute(
          "UPDATE "
              + table.name()
              + " AS p SET "
              + set
              + ",payload_purged=1 WHERE "
              + table.document("p")
              + "=? AND payload_purged=0",
          claim.documentId());
    }
    verifyPurged(claim.documentId(), claim.cleanupId());
  }

  void verifyPurged(String documentId, String cleanupId) {
    var sealed = plan(cleanupId).orElseThrow(ModelValues::invalid);
    var current = inventory(documentId);
    if (current.size() != sealed.payloadRows().size()) {
      throw ModelValues.invalid();
    }
    for (var table : CleanupPayloadTables.TABLES) {
      if (store.count(
              "SELECT COUNT(*) FROM "
                  + table.name()
                  + " p WHERE "
                  + table.document("p")
                  + "=? AND (payload_purged!=1 OR NOT ("
                  + table.empty("p")
                  + "))",
              documentId)
          != 0) {
        throw ModelValues.invalid();
      }
    }
  }

  void verifyPurgedRows() {
    for (var table : CleanupPayloadTables.TABLES) {
      for (var row :
          store.rows(
              "SELECT "
                  + table.document("p")
                  + " AS document_id,"
                  + table.rowKey("p")
                  + " AS row_key FROM "
                  + table.name()
                  + " p WHERE payload_purged=1")) {
        String doc = text(row, "document_id"), key = text(row, "row_key");
        var plans =
            store.rows(
                """
            SELECT p.cleanup_id FROM cleanup_plans p JOIN document_cleanups c ON c.id=p.cleanup_id
            JOIN document_tombstones t ON t.document_id=p.document_id JOIN documents d ON d.id=p.document_id
            WHERE p.document_id=? AND p.source_sha256=d.source_sha256 AND c.document_id=p.document_id AND c.source_sha256=p.source_sha256
            """,
                doc);
        if (plans.size() != 1) {
          throw ModelValues.invalid();
        }
        CleanupPlan saved =
            plan(text(plans.getFirst(), "cleanup_id")).orElseThrow(ModelValues::invalid);
        for (var body : table.bodies()) {
          if (saved.payloadRows().stream()
              .noneMatch(
                  p ->
                      p.tableName().equals(table.name())
                          && p.rowKey().equals(key)
                          && p.bodyColumn().equals(body.name()))) {
            throw ModelValues.invalid();
          }
        }
        if (store.count(
                "SELECT COUNT(*) FROM "
                    + table.name()
                    + " p WHERE "
                    + table.rowKey("p")
                    + "=? AND "
                    + table.empty("p"),
                key)
            != 1) {
          throw ModelValues.invalid();
        }
      }
    }
  }

  private void requireCurrent(CleanupClaim claim) {
    if (!current(claim)) {
      throw ModelValues.invalid();
    }
  }

  private List<CleanupResource> resources(String cleanupId) {
    var rows =
        store.rows("SELECT kind,status FROM cleanup_resources WHERE cleanup_id=?", cleanupId);
    var resources = new ArrayList<CleanupResource>();
    for (String kind : CleanupResource.KINDS) {
      var row =
          rows.stream()
              .filter(r -> kind.equals(r.get("kind")))
              .findFirst()
              .orElseThrow(ModelValues::invalid);
      resources.add(new CleanupResource(kind, text(row, "status")));
    }
    if (rows.size() != resources.size()) {
      throw ModelValues.invalid();
    }
    return List.copyOf(resources);
  }

  private DocumentCleanupState state(Map<String, Object> row) {
    String status = text(row, "state");
    return new DocumentCleanupState(
        text(row, "document_id"),
        text(row, "id"),
        status.equals("completed") ? "deleted" : "deleting",
        status,
        text(row, "requested_at"),
        text(row, "updated_at"),
        textOrNull(row, "completed_at"),
        textOrNull(row, "error_code"),
        resources(text(row, "id")));
  }

  private static ProjectionAttempt projection(Map<String, Object> row) {
    String workspace = text(row, "workspace_id");
    return new ProjectionAttempt(
        text(row, "document_id"),
        workspace,
        text(row, "source_revision_id"),
        text(row, "source_sha256"),
        text(row, "generation_id"),
        text(row, "route"),
        new QualifiedProjectionTarget(
            text(row, "endpoint"),
            text(row, "database_name"),
            text(row, "collection_name"),
            workspace,
            text(row, "embedding_identity"),
            Math.toIntExact(number(row, "dimensions")),
            text(row, "projection_identity")),
        number(row, "write_issued") == 1);
  }

  private static String textOrNull(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : text(row, key);
  }

  private static String hash(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
