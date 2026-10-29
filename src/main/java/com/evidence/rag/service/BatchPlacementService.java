package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchPlacement;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.FolderEntity;
import com.evidence.rag.repository.ManagementRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Import placement uses existing flat directories; it never rewrites a source or moves a duplicate.
 */
final class BatchPlacementService {
  private final ManagementRepository management;

  BatchPlacementService(ManagementRepository management) {
    this.management = management;
  }

  BatchPlacement prepare(Actor actor, String targetId, boolean preservePaths) {
    if (targetId == null) return new BatchPlacement(null, null, preservePaths);
    ModelValues.identifier(targetId, 100);
    var target =
        management.findVisibleFolder(actor, targetId, false).orElseThrow(ModelValues::notFound);
    return new BatchPlacement(targetId, target.name(), preservePaths);
  }

  /**
   * The stored placement with its target's current name, so a rename after registration is
   * followed. A target removed since registration keeps its id with a null name.
   */
  BatchPlacement current(Actor actor, BatchPlacement stored) {
    if (stored.targetFolderId() == null) return stored;
    return new BatchPlacement(
        stored.targetFolderId(),
        management
            .findVisibleFolder(actor, stored.targetFolderId(), false)
            .map(FolderEntity::name)
            .orElse(null),
        stored.preservePaths());
  }

  /** Rejects, before anything is registered, a preserved path no flat folder name can hold. */
  void validatePath(BatchPlacement placement, String relativePath) {
    if (removed(placement)) return;
    if (!representable(directoryName(placement, relativePath)))
      throw new ApplicationException(
          FailureKind.INVALID_INPUT, "batch_folder_name_invalid", "保留路径后的目录名需在80字以内，且不能带首尾空格。");
  }

  /**
   * Files one received document. Placement never fails an upload: a target removed after
   * registration leaves the file unfiled, and a target renamed so that the preserved path no longer
   * fits files it directly in the target. Either fallback is recorded in the audit log.
   */
  void place(Actor actor, String documentId, BatchPlacement stored, String relativePath) {
    var placement = current(actor, stored);
    if (removed(placement)) {
      skipped(actor, documentId, placement.targetFolderId(), "target_removed");
      return;
    }
    String folderId = placement.targetFolderId();
    if (placement.preservePaths() && !folder(relativePath).isEmpty()) {
      String name = directoryName(placement, relativePath);
      if (representable(name)) folderId = folderNamed(actor, name);
      else skipped(actor, documentId, placement.targetFolderId(), "path_unrepresentable");
    }
    if (folderId != null) {
      var document =
          management
              .findAuthorizedDocument(actor, documentId, true)
              .orElseThrow(ModelValues::notFound);
      management.updateMetadata(
          actor, documentId, document.displayName(), folderId, Instant.now().toString());
      management.insertAudit(
          AuditEventEntity.create(
              actor,
              documentId,
              "document_updated",
              null,
              Map.of("folder_id", folderId),
              Set.of("folder_id")));
    }
  }

  private String folderNamed(Actor actor, String name) {
    String key = fold(name);
    var existing = management.findVisibleFolderByNameKey(actor, key);
    if (existing.isPresent()) return existing.orElseThrow().id();
    String folderId = UUID.randomUUID().toString();
    management.insertFolder(actor, folderId, name, key, Instant.now().toString());
    management.insertAudit(
        AuditEventEntity.create(
            actor, folderId, "folder_created", null, Map.of("name", name), Set.of("name")));
    return folderId;
  }

  private void skipped(Actor actor, String documentId, String targetId, String reason) {
    var intended = new java.util.HashMap<String, Object>();
    intended.put("folder_id", targetId);
    intended.put("reason", reason);
    management.insertAudit(
        AuditEventEntity.create(
            actor,
            documentId,
            "batch_placement_skipped",
            intended,
            null,
            Set.of("folder_id", "reason")));
  }

  private static boolean removed(BatchPlacement placement) {
    return placement.targetFolderId() != null && placement.targetFolderName() == null;
  }

  private static boolean representable(String name) {
    return name == null || name.codePointCount(0, name.length()) <= 80 && name.equals(name.strip());
  }

  static String folder(String relativePath) {
    return relativePath == null || relativePath.indexOf('/') < 0
        ? ""
        : relativePath.substring(0, relativePath.lastIndexOf('/'));
  }

  private static String directoryName(BatchPlacement placement, String path) {
    String relative = placement.preservePaths() ? folder(path) : "";
    if (relative.isEmpty()) return placement.targetFolderName();
    return placement.targetFolderName() == null
        ? relative
        : placement.targetFolderName() + "/" + relative;
  }

  private static String fold(String value) {
    return value.toLowerCase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ");
  }
}
