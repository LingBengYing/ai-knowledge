package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.bounded;
import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.label;
import static com.evidence.rag.model.domain.ModelValues.notFound;
import static com.evidence.rag.model.domain.ModelValues.tags;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.dto.AuditEventResult;
import com.evidence.rag.model.dto.DocumentActionCommand;
import com.evidence.rag.model.dto.DocumentActionResult;
import com.evidence.rag.model.dto.DocumentPageResult;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.model.dto.FolderRemovalResult;
import com.evidence.rag.model.dto.FolderResult;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.DocumentEntity;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Metadata use cases and current permissions. Every authority view and edit has one transaction.
 */
public final class ManagementService {
  private static final Set<String> ROLES = Set.of("reader", "editor", "owner");
  private static final Set<String> TYPES = Set.of("document", "image", "audio", "video");
  private static final Set<String> STATUS =
      Set.of("ready", "queued", "processing", "parsed", "failed", "cancelled", "deleting");
  private final SqliteAuthorityStore store;
  private final ManagementRepository management;
  private final IngestionRepository ingestion;
  private final IndexingRepository indexing;
  private final DocumentPermissionPolicy permissions;

  public ManagementService(
      SqliteAuthorityStore store,
      ManagementRepository management,
      IngestionRepository ingestion,
      IndexingRepository indexing,
      DocumentPermissionPolicy permissions) {
    this.store = Objects.requireNonNull(store);
    this.management = Objects.requireNonNull(management);
    this.ingestion = Objects.requireNonNull(ingestion);
    this.indexing = Objects.requireNonNull(indexing);
    this.permissions = Objects.requireNonNull(permissions);
  }

  public void registerSyntheticDocument(
      Actor owner, SyntheticDocument document, Map<String, String> grants) {
    if (document == null || grants == null) {
      throw invalid();
    }
    identifier(document.documentId(), 100);
    bounded(document.filename(), 255);
    identifier(document.revisionId(), 100);
    label(document.mimeType(), 100);
    if (document.filename().isBlank()
        || !TYPES.contains(document.type())
        || document.sha256() == null
        || !document.sha256().matches("[a-f0-9]{64}")
        || document.sizeBytes() < 0) {
      throw invalid();
    }
    for (var grant : grants.entrySet()) {
      new Actor(owner.workspaceId(), grant.getKey());
      if (!ROLES.contains(grant.getValue())
          || (grant.getKey().equals(owner.principalId()) && !"owner".equals(grant.getValue()))) {
        throw invalid();
      }
    }
    store.transaction(
        () -> {
          if (management.documentExists(document.documentId())) {
            throw new ApplicationException(FailureKind.CONFLICT, "document_conflict", "合成资料标识已存在。");
          }
          management.insertDocument(owner, document, Instant.now().toString());
          var acl = new TreeMap<>(grants);
          acl.put(owner.principalId(), "owner");
          acl.forEach(
              (principal, role) -> management.insertGrant(document.documentId(), principal, role));
          return null;
        });
  }

  public DocumentPageResult listDocuments(Actor actor, DocumentQuery query) {
    validateQuery(query);
    return store.transaction(
        () -> {
          long total = management.countDocuments(actor, query);
          var items =
              management.findDocuments(actor, query).stream().map(this::documentResult).toList();
          return new DocumentPageResult(
              items,
              total,
              query.page(),
              query.pageSize(),
              (total + query.pageSize() - 1) / query.pageSize());
        });
  }

  private static void validateQuery(DocumentQuery query) {
    if (query == null
        || query.page() < 1
        || query.page() > 1_000_000
        || query.pageSize() < 1
        || query.pageSize() > 100) {
      throw invalid();
    }
    bounded(query.q(), 200);
    if ((query.type() != null && !TYPES.contains(query.type()))
        || (query.status() != null && !STATUS.contains(query.status()))
        || query.sort() == null
        || !Set.of("updated_desc", "updated_asc", "name_asc", "name_desc").contains(query.sort())) {
      throw invalid();
    }
    if (query.folderId() != null) {
      bounded(query.folderId(), 100);
    }
    if (query.tag() != null) {
      bounded(query.tag(), 40);
    }
  }

  public DocumentResult updateDocument(Actor actor, String id, DocumentPatchCommand command) {
    var patch = validatePatch(command);
    return store.transaction(() -> patchDocument(actor, id, patch, false));
  }

  public List<FolderResult> listFolders(Actor actor) {
    return store.transaction(() -> folderResults(actor));
  }

  public FolderResult createFolder(Actor actor, String inputName) {
    String name = label(inputName, 80);
    return store.transaction(
        () -> {
          checkFolderConflict(actor, name, "");
          String id = UUID.randomUUID().toString();
          management.insertFolder(actor, id, name, fold(name), Instant.now().toString());
          audit(actor, id, "folder_created", null, values("name", name), Set.of("name"));
          return new FolderResult(id, name, 0L, true);
        });
  }

  public FolderResult renameFolder(Actor actor, String id, String inputName) {
    String name = label(inputName, 80);
    return store.transaction(
        () -> {
          var previous =
              management.findVisibleFolder(actor, id, true).orElseThrow(() -> notFound());
          checkFolderConflict(actor, name, id);
          management.renameFolder(id, name, fold(name), Instant.now().toString());
          audit(
              actor,
              id,
              "folder_renamed",
              values("name", previous.name()),
              values("name", name),
              Set.of("name"));
          return folderResults(actor).stream()
              .filter(item -> item.folderId().equals(id))
              .findFirst()
              .orElseThrow();
        });
  }

  public FolderRemovalResult removeFolder(Actor actor, String id) {
    return store.transaction(
        () -> {
          var previous =
              management.findVisibleFolder(actor, id, true).orElseThrow(() -> notFound());
          if (management.folderDocumentCount(id) != 0) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "folder_not_empty", "目录仍有资料，请先移出资料再删除目录。");
          }
          management.deleteFolder(id);
          audit(
              actor,
              id,
              "folder_deleted",
              values("name", previous.name()),
              values("name", null),
              Set.of("name"));
          return new FolderRemovalResult(id, "removed");
        });
  }

  public List<String> listTags(Actor actor) {
    return store.transaction(() -> management.findTags(actor));
  }

  /**
   * Deliberately one transaction per item: a rejected item never rolls back successful siblings.
   */
  public List<DocumentActionResult> documentActions(Actor actor, DocumentActionCommand command) {
    if (command == null
        || command.documentIds() == null
        || command.documentIds().isEmpty()
        || command.documentIds().size() > 100) {
      throw invalid();
    }
    var ids = new LinkedHashSet<String>();
    for (String id : command.documentIds()) {
      if (!ids.add(identifier(id, 100))) {
        throw invalid();
      }
    }
    String action = command.action();
    if (action == null) {
      throw invalid();
    }
    if (action.equals("delete") || action.equals("reindex")) {
      throw new ApplicationException(
          FailureKind.NOT_IMPLEMENTED, "migration_incomplete", "Java 版尚未移植资料生命周期操作。");
    }
    DocumentPatchCommand patch;
    if (action.equals("move")) {
      if (!command.folderIdPresent() || command.tags() != null) {
        throw invalid();
      }
      patch = new DocumentPatchCommand(false, null, true, command.folderId(), false, null);
    } else if (action.equals("tag")) {
      if (command.folderId() != null) {
        throw invalid();
      }
      var normalized = tags(command.tags());
      if (normalized.isEmpty()) {
        throw invalid();
      }
      patch = new DocumentPatchCommand(false, null, false, null, true, normalized);
    } else {
      throw invalid();
    }
    var validated = validatePatch(patch);
    var results = new ArrayList<DocumentActionResult>();
    for (String id : ids) {
      try {
        store.transaction(() -> patchDocument(actor, id, validated, action.equals("tag")));
        results.add(new DocumentActionResult(id, true, null, null));
      } catch (ApplicationException failure) {
        results.add(new DocumentActionResult(id, false, failure.code(), failure.getMessage()));
      }
    }
    return List.copyOf(results);
  }

  public List<AuditEventResult> auditEvents(Actor actor) {
    return store.transaction(
        () ->
            management.findAudit(actor).stream()
                .map(
                    event ->
                        new AuditEventResult(
                            event.id(),
                            event.workspaceId(),
                            event.actorId(),
                            event.entityId(),
                            event.action(),
                            event.fieldsJson(),
                            event.beforeSha256(),
                            event.afterSha256(),
                            event.createdAt()))
                .toList());
  }

  private DocumentResult patchDocument(
      Actor actor, String id, DocumentPatchCommand patch, boolean append) {
    var document = management.findAuthorizedDocument(actor, id, true).orElseThrow(() -> notFound());
    permissions.require(document.currentRole(), true);
    var previousTags = management.documentTags(id);
    var previous =
        values(
            "display_name",
            document.displayName(),
            "folder_id",
            document.folderId(),
            "tags",
            previousTags);
    String displayName = patch.displayNamePresent() ? patch.displayName() : document.displayName();
    String folderId = patch.folderIdPresent() ? patch.folderId() : document.folderId();
    List<String> nextTags = patch.tagsPresent() ? patch.tags() : previousTags;
    if (folderId != null) {
      management.findVisibleFolder(actor, folderId, false).orElseThrow(() -> notFound());
    }
    if (append) {
      var merged = new LinkedHashSet<>(previousTags);
      merged.addAll(nextTags);
      if (merged.size() > 20) {
        throw new ApplicationException(FailureKind.CONFLICT, "tag_limit_reached", "每份资料最多保留20个标签。");
      }
      nextTags = new ArrayList<>(merged);
    }
    String now = Instant.now().toString();
    management.updateMetadata(actor, id, displayName, folderId, now);
    if (patch.tagsPresent()) {
      management.replaceTags(id, nextTags);
    }
    var fields = new LinkedHashSet<String>();
    if (patch.displayNamePresent()) {
      fields.add("display_name");
    }
    if (patch.folderIdPresent()) {
      fields.add("folder_id");
    }
    if (patch.tagsPresent()) {
      fields.add("tags");
    }
    audit(
        actor,
        id,
        "document_updated",
        previous,
        values("display_name", displayName, "folder_id", folderId, "tags", nextTags),
        fields);
    return documentResult(
        management.findAuthorizedDocument(actor, id, true).orElseThrow(() -> notFound()));
  }

  private DocumentResult documentResult(DocumentEntity document) {
    permissions.require(document.currentRole(), false);
    var evidence = management.evidence(document.id()).orElse(null);
    boolean synthetic = evidence == null;
    TaskResult parseTask = null, indexTask = null;
    if (!synthetic) {
      var ingestionTask = ingestion.findInternalTask(evidence.jobId()).orElseThrow();
      String creatorRole =
          management.currentRole(
              new Actor(ingestionTask.workspaceId(), ingestionTask.createdBy()), document.id());
      parseTask =
          TaskResults.ingestion(
              ingestionTask,
              permissions.canEdit(document.currentRole()),
              permissions.canEdit(creatorRole));
      var indexId = indexing.documentJobId(document.id()).orElse(null);
      if (indexId != null) {
        indexTask =
            TaskResults.from(
                indexing.findInternalTask(indexId).orElseThrow(),
                permissions.canEdit(document.currentRole()),
                true,
                indexing.publicationId(indexId));
      }
    }
    String active = synthetic ? null : evidence.activeRevisionId();
    return new DocumentResult(
        document.id(),
        document.filename(),
        synthetic ? "ready" : evidence.state(),
        active,
        synthetic ? document.registrationRevisionId() : null,
        synthetic ? 0 : evidence.segmentCount(),
        document.updatedAt(),
        document.mimeType(),
        document.sizeBytes(),
        document.sourceSha256(),
        document.displayName(),
        document.folderId(),
        document.folderName(),
        management.documentTags(document.id()),
        document.currentRole(),
        permissions.canEdit(document.currentRole()),
        indexTask == null ? "not_indexed" : indexTask.state(),
        indexTask,
        synthetic ? null : evidence.publicationId(),
        !synthetic
            && "parsed".equals(evidence.state())
            && indexTask == null
            && active == null
            && permissions.canEdit(document.currentRole()),
        parseTask,
        synthetic,
        document.documentType());
  }

  private List<FolderResult> folderResults(Actor actor) {
    return management.findFolders(actor).stream()
        .map(
            folder ->
                new FolderResult(
                    folder.id(),
                    folder.name(),
                    folder.documentCount(),
                    actor.principalId().equals(folder.ownerId())))
        .toList();
  }

  private void checkFolderConflict(Actor actor, String name, String except) {
    if (management.folderNameExists(actor, fold(name), except)) {
      throw new ApplicationException(FailureKind.CONFLICT, "folder_name_conflict", "你已创建同名目录。");
    }
  }

  private static String fold(String value) {
    return value.toLowerCase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ");
  }

  private static DocumentPatchCommand validatePatch(DocumentPatchCommand patch) {
    if (patch == null
        || !(patch.displayNamePresent() || patch.folderIdPresent() || patch.tagsPresent())) {
      throw invalid();
    }
    return new DocumentPatchCommand(
        patch.displayNamePresent(),
        patch.displayNamePresent() ? label(patch.displayName(), 255) : null,
        patch.folderIdPresent(),
        patch.folderIdPresent() && patch.folderId() != null
            ? identifier(patch.folderId(), 100)
            : null,
        patch.tagsPresent(),
        patch.tagsPresent() ? tags(patch.tags()) : null);
  }

  private void audit(
      Actor actor, String id, String action, Object before, Object after, Set<String> fields) {
    management.insertAudit(AuditEventEntity.create(actor, id, action, before, after, fields));
  }

  private static Map<String, Object> values(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }
}
