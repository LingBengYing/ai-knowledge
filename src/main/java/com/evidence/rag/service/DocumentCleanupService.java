package com.evidence.rag.service;

import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.CleanupBatch;
import com.evidence.rag.model.domain.CleanupBatchItem;
import com.evidence.rag.model.domain.CleanupClaim;
import com.evidence.rag.model.domain.CleanupPage;
import com.evidence.rag.model.domain.DocumentCleanupState;
import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.ManagedBackupFile;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.DocumentRemovalEntity;
import com.evidence.rag.repository.DocumentCleanupRepository;
import com.evidence.rag.repository.DocumentLifecycleRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.worker.OwnedTemporaryResources;
import com.evidence.rag.worker.cleanup.CleanupRestoreJournal;
import com.evidence.rag.worker.indexing.ProjectionCleanupExecutor;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Controlled cleanup of real payloads; remote logical absence never proves physical reclamation.
 */
public final class DocumentCleanupService {
  private final SqliteAuthorityStore store;
  private final DocumentCleanupRepository cleanup;
  private final DocumentLifecycleRepository lifecycle;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final ProjectionCleanup projections;

  public DocumentCleanupService(
      SqliteAuthorityStore store,
      DocumentCleanupRepository cleanup,
      DocumentLifecycleRepository lifecycle,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ProjectionCleanup projections) {
    this.store = Objects.requireNonNull(store);
    this.cleanup = Objects.requireNonNull(cleanup);
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
    this.projections = Objects.requireNonNull(projections);
  }

  public DocumentCleanupState request(Actor actor, String documentId) {
    validate(actor, documentId);
    var existing =
        store.transaction(
            () -> {
              var document =
                  lifecycle
                      .findWritableDocument(actor, documentId)
                      .orElseThrow(ModelValues::notFound);
              permissions.require(document.currentRole(), true);
              return cleanup.find(actor, documentId).filter(state -> state.cleanupId() != null);
            });
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    try (var maintenance =
        store.operationGate().tryMaintenance().orElseThrow(DocumentCleanupService::busy)) {
      return store.transaction(
          () -> {
            var document =
                lifecycle
                    .findWritableDocument(actor, documentId)
                    .orElseThrow(ModelValues::notFound);
            permissions.require(document.currentRole(), true);
            if (!cleanup.idle(documentId)) {
              throw busy();
            }
            String now = Instant.now().toString();
            if (lifecycle.findRemoval(actor, documentId).isEmpty()) {
              if (document.folderId() != null) {
                management.updateMetadata(actor, documentId, document.displayName(), null, now);
                management.insertAudit(
                    AuditEventEntity.create(
                        actor,
                        documentId,
                        "document_updated",
                        Map.of("folder_id", document.folderId()),
                        Collections.singletonMap("folder_id", null),
                        Set.of("folder_id")));
              }
              lifecycle.insertRemoval(
                  new DocumentRemovalEntity(
                      documentId, actor.workspaceId(), actor.principalId(), now));
              management.insertAudit(
                  AuditEventEntity.create(
                      actor,
                      documentId,
                      "document_removal_requested",
                      null,
                      Map.of(
                          "status", "deleting", "cleanup_status", "pending", "requested_at", now),
                      Set.of("status", "cleanup_status", "requested_at")));
            }
            return cleanup.create(actor, documentId, now);
          });
    }
  }

  public DocumentCleanupState status(Actor actor, String documentId) {
    validate(actor, documentId);
    return store.transaction(
        () -> cleanup.find(actor, documentId).orElseThrow(ModelValues::notFound));
  }

  public CleanupPage list(Actor actor, int page, int pageSize) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    return store.transaction(() -> cleanup.list(actor, page, pageSize));
  }

  public CleanupBatch batch(Actor actor, List<String> documentIds) {
    if (actor == null
        || documentIds == null
        || documentIds.isEmpty()
        || documentIds.size() > 100
        || new HashSet<>(documentIds).size() != documentIds.size()) {
      throw ModelValues.invalid();
    }
    for (String id : documentIds) {
      ModelValues.identifier(id, 100);
    }
    var items = new ArrayList<CleanupBatchItem>();
    for (String id : documentIds) {
      try {
        items.add(new CleanupBatchItem(id, "accepted", request(actor, id), null));
      } catch (ApplicationException error) {
        if (error.kind() == FailureKind.NOT_FOUND) {
          items.add(new CleanupBatchItem(id, "not_found", null, "not_found"));
        } else if (error.code().equals("document_busy")) {
          items.add(new CleanupBatchItem(id, "busy", null, "document_busy"));
        } else {
          throw error;
        }
      }
    }
    return new CleanupBatch(items, items.size());
  }

  /** One resumable claim, without waiting for active work or opening a transaction around I/O. */
  public boolean runOnce() {
    // An idle scheduler tick must not exclude API reads while waiting for authority access.
    // This is only a hint: the actual claim is rechecked in the maintenance transaction below.
    if (!store.transaction(cleanup::hasPending)) {
      return false;
    }
    var admission = store.operationGate().tryMaintenance();
    if (admission.isEmpty()) {
      return false;
    }
    try (var maintenance = admission.orElseThrow()) {
      var next = store.transaction(() -> cleanup.claimNext(Instant.now().toString()));
      if (next.isEmpty()) {
        return false;
      }
      CleanupClaim claim = next.orElseThrow();
      String phase = "managed_temporaries";
      try {
        if (!OwnedTemporaryResources.verifyIdle(store.operationGate().managedRoot(), maintenance)) {
          resource(claim, "managed_temporaries", "blocked", "cleanup_temporaries_unverified");
          finish(claim);
          return true;
        }
        phase = "restore_barrier";
        var plan = store.transaction(() -> cleanup.sealPlan(claim));
        resource(claim, "restore_barrier", "running", null);
        CleanupRestoreJournal.intent(
            store.libraryPath().getParent(), store.libraryIdentity(), plan);

        phase = "database_payload";
        resource(claim, "database_payload", "running", null);
        store.purge(claim, plan, maintenance);
        resource(claim, "restore_barrier", "completed", null);
        boolean retainedWikiContent = store.transaction(() -> cleanup.retainsWikiContent(claim));
        resource(
            claim,
            "database_payload",
            retainedWikiContent ? "blocked" : "completed",
            retainedWikiContent ? "cleanup_wiki_content_retained" : null);
        phase = "database_file";
        resource(claim, "database_file", "running", null);
        store.compact(claim, maintenance);
        resource(claim, "database_file", "completed", null);

        phase = "managed_backups";
        cleanBackups(claim, maintenance);
        phase = "managed_temporaries";
        resource(claim, "managed_temporaries", "running", null);
        boolean temporaries =
            OwnedTemporaryResources.sweep(store.operationGate().managedRoot(), maintenance);
        resource(
            claim,
            "managed_temporaries",
            temporaries ? "completed" : "blocked",
            temporaries ? null : "cleanup_temporaries_unverified");
        phase = "remote_logical_rows";
        cleanProjections(claim);
      } catch (IOException | RuntimeException failure) {
        // Exception messages, source payloads and provider responses never enter the ledger.
        if (store.transaction(() -> cleanup.current(claim))) {
          resource(claim, phase, "failed", "cleanup_execution_failed");
          if (phase.equals("database_payload")) {
            resource(claim, "restore_barrier", "failed", "cleanup_execution_failed");
          }
        }
      }
      finish(claim);
      return true;
    }
  }

  private void finish(CleanupClaim claim) {
    store.transaction(
        () -> {
          var result = cleanup.finish(claim, Instant.now().toString());
          management.insertAudit(
              AuditEventEntity.create(
                  new Actor(claim.workspaceId(), "system:cleanup"),
                  claim.documentId(),
                  "document_cleanup_" + result.cleanupStatus(),
                  null,
                  Map.of(
                      "cleanup_id",
                      result.cleanupId(),
                      "cleanup_status",
                      result.cleanupStatus(),
                      "status",
                      result.status()),
                  Set.of("cleanup_id", "cleanup_status", "status")));
          return null;
        });
  }

  private void cleanProjections(CleanupClaim claim) {
    var inventory = store.transaction(() -> cleanup.projectionInventory(claim));
    if (!inventory.known()) {
      for (String kind :
          List.of(
              "remote_inventory",
              "remote_logical_rows",
              "remote_write_terminal",
              "remote_physical_storage")) {
        resource(claim, kind, "blocked", "cleanup_inventory_unknown");
      }
      return;
    }
    resource(claim, "remote_inventory", "completed", null);
    if (inventory.attempts().isEmpty()) {
      for (String kind :
          List.of("remote_logical_rows", "remote_write_terminal", "remote_physical_storage")) {
        resource(claim, kind, "not_applicable", null);
      }
      return;
    }
    var groups = new LinkedHashMap<QualifiedProjectionTarget, List<ProjectionAttempt>>();
    for (var attempt : inventory.attempts()) {
      groups.computeIfAbsent(attempt.target(), ignored -> new ArrayList<>()).add(attempt);
    }
    String logical = "completed";
    String terminal = "completed";
    String physical = "completed";
    String error = null;
    for (var group : groups.values()) {
      var result = ProjectionCleanupExecutor.clean(projections, List.copyOf(group));
      logical = combine(logical, result.logicalRows());
      terminal = combine(terminal, result.writeTerminal());
      physical = combine(physical, result.physicalStorage());
      if (result.errorCode() != null) {
        error = result.errorCode();
      }
    }
    for (var entry :
        Map.of(
                "remote_logical_rows",
                logical,
                "remote_write_terminal",
                terminal,
                "remote_physical_storage",
                physical)
            .entrySet()) {
      resource(
          claim,
          entry.getKey(),
          entry.getValue(),
          Set.of("blocked", "failed").contains(entry.getValue())
              ? (error == null ? "cleanup_remote_unverified" : error)
              : null);
    }
  }

  private void cleanBackups(CleanupClaim claim, LibraryOperationGate.MaintenanceLease lease)
      throws IOException {
    resource(claim, "managed_backups", "running", null);
    var inventory = store.managedBackups();
    if (!inventory.known()) {
      resource(claim, "managed_backups", "blocked", "cleanup_backups_unknown");
      return;
    }
    Path root = store.libraryPath().getParent();
    if (inventory.files().isEmpty()) {
      resource(claim, "managed_backups", "completed", null);
      return;
    }
    if (Files.isSymbolicLink(root)
        || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        || !root.toRealPath().equals(root.toAbsolutePath().normalize())) {
      throw new IOException("Unsafe managed backup root");
    }
    // Validate the complete registered inventory before replacing any file.
    for (var file : inventory.files()) {
      verifyBackup(root.resolve(file.relativePath()), file);
    }
    for (var file : inventory.files()) {
      Path destination = root.resolve(file.relativePath());
      Path staging =
          root.resolve(
              ".cleanup-backup-" + claim.cleanupId() + "-" + UUID.randomUUID() + ".partial");
      try {
        store.writeManagedSnapshot(staging, claim, lease);
        try (var channel =
            FileChannel.open(staging, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
          channel.force(true);
        }
        String digest = digest(staging);
        long size = Files.size(staging);
        Files.move(
            staging,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        OwnedTemporaryResources.syncDirectory(root);
        store.recordManagedBackup(file.relativePath(), digest, size, lease);
        verifyBackup(destination, new ManagedBackupFile(file.relativePath(), digest, size));
      } finally {
        Files.deleteIfExists(staging);
      }
    }
    resource(claim, "managed_backups", "completed", null);
  }

  private static void verifyBackup(Path path, ManagedBackupFile file) throws IOException {
    if (Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.size(path) != file.sizeBytes()
        || !digest(path).equals(file.sha256())) {
      throw new IOException("Managed backup identity changed");
    }
  }

  private static String digest(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          digest.update(buffer, 0, count);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private void resource(CleanupClaim claim, String kind, String status, String error) {
    store.transaction(
        () -> {
          cleanup.setResource(claim, kind, status, error, Instant.now().toString());
          return null;
        });
  }

  private static String combine(String current, String next) {
    if (current.equals("failed") || next.equals("failed")) {
      return "failed";
    }
    if (current.equals("blocked") || next.equals("blocked")) {
      return "blocked";
    }
    return "completed";
  }

  private static void validate(Actor actor, String documentId) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(documentId, 100);
  }

  private static ApplicationException busy() {
    return new ApplicationException(FailureKind.CONFLICT, "document_busy", "资料库仍有运行或维护任务。");
  }
}
