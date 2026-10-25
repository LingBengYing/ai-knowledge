package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchPlacement;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.entity.AuditEventEntity;
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

  void validatePath(BatchPlacement placement, String relativePath) {
    String name = directoryName(placement, relativePath);
    if (name != null && (name.codePointCount(0, name.length()) > 80 || !name.equals(name.strip())))
      throw new ApplicationException(
          FailureKind.INVALID_REQUEST, "batch_folder_name_invalid", "保留路径后的目录名需在80字以内，且不能带首尾空格。");
  }

  void place(Actor actor, String documentId, BatchPlacement placement, String relativePath) {
    if (placement.targetFolderId() != null)
      management
          .findVisibleFolder(actor, placement.targetFolderId(), false)
          .orElseThrow(ModelValues::notFound);
    validatePath(placement, relativePath);
    String name = directoryName(placement, relativePath);
    String folderId = placement.targetFolderId();
    String relativeFolder = folder(relativePath);
    if (placement.preservePaths() && !relativeFolder.isEmpty()) {
      String key = fold(name);
      var existing = management.findVisibleFolderByNameKey(actor, key);
      if (existing.isPresent()) folderId = existing.orElseThrow().id();
      else {
        folderId = UUID.randomUUID().toString();
        management.insertFolder(actor, folderId, name, key, Instant.now().toString());
        management.insertAudit(
            AuditEventEntity.create(
                actor, folderId, "folder_created", null, Map.of("name", name), Set.of("name")));
      }
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
