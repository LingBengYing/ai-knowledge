package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentReplacementResult;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.DocumentReplacementEntity;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Candidate originals remain separate from the current document until complete publication. */
public final class DocumentReplacementService {
  private boolean automaticIndexingEnabled = true;

  public void setAutomaticIndexingEnabled(boolean enabled) {
    automaticIndexingEnabled = enabled;
  }

  private final SqliteAuthorityStore store;
  private final DocumentUpdateRepository updates;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final IngestionService ingestion;
  private final Supplier<IndexingTaskProcessor> indexing;
  private final SoundLibraryService sounds;
  private final VideoAvLibraryService videos;
  private final boolean corpusIngestionEnabled;

  public DocumentReplacementService(
      SqliteAuthorityStore store,
      DocumentUpdateRepository updates,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      IngestionService ingestion,
      Supplier<IndexingTaskProcessor> indexing,
      SoundLibraryService sounds,
      VideoAvLibraryService videos,
      boolean corpusIngestionEnabled) {
    this.store = Objects.requireNonNull(store);
    this.updates = Objects.requireNonNull(updates);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
    this.ingestion = Objects.requireNonNull(ingestion);
    this.indexing = Objects.requireNonNull(indexing);
    this.sounds = sounds;
    this.videos = videos;
    this.corpusIngestionEnabled = corpusIngestionEnabled;
    store.transaction(
        () -> {
          updates.recoverStandaloneIndexing();
          return null;
        });
  }

  public DocumentReplacementResult get(Actor actor, String documentId) {
    require(actor, documentId);
    try (var operation = store.operationGate().enter()) {
      boolean textReady = textReady();
      return store.transaction(() -> result(actor, documentId, textReady));
    }
  }

  public String prepareUpload(
      Actor actor, String documentId, String baseRevisionId, String filename, String contentType) {
    require(actor, documentId);
    ModelValues.identifier(baseRevisionId, 100);
    return store.transaction(
        () -> {
          var current = authorizedOriginal(actor, documentId, true);
          String pipeline = updates.pipeline(documentId);
          if (!current.revisionId().equals(baseRevisionId)
              || !enabled(pipeline)
              || !updates.canPrepare(documentId)) {
            throw conflict();
          }
          String mime = canonicalMime(pipeline, filename, contentType);
          if (!current.documentType().equals(type(mime))) {
            throw new ApplicationException(
                FailureKind.INVALID_INPUT, "unsupported_document", "更新文件必须与当前资料属于同一类别。");
          }
          return mime;
        });
  }

  public DocumentReplacementResult upload(
      Actor actor,
      String documentId,
      String baseRevisionId,
      String filename,
      String mime,
      byte[] content) {
    require(actor, documentId);
    ModelValues.identifier(baseRevisionId, 100);
    if (content == null || content.length == 0 || content.length > TextParser.MAX_BYTES) {
      throw ModelValues.invalid();
    }
    byte[] original = content.clone();
    try (var operation = store.operationGate().enter()) {
      boolean textReady = textReady();
      return store.transaction(
          () -> {
            var previous = authorizedOriginal(actor, documentId, true);
            String pipeline = updates.pipeline(documentId);
            if (!previous.revisionId().equals(baseRevisionId)
                || !enabled(pipeline)
                || !updates.canPrepare(documentId)) {
              throw conflict();
            }
            validateEnvelope(pipeline, filename, mime, original);
            if (!previous.documentType().equals(type(mime))) {
              throw conflict();
            }
            if (new IngestionRepository(store).storedBytes(actor.workspaceId())
                > 256L * 1024 * 1024 - original.length) {
              throw new ApplicationException(
                  FailureKind.CONFLICT, "ingestion_quota_exceeded", "当前组织的原文件存储已达到开发配额。");
            }
            String now = Instant.now().toString();
            var candidate =
                new DocumentOriginal(
                    documentId,
                    UUID.randomUUID().toString(),
                    filename,
                    previous.documentType(),
                    mime,
                    ModelValues.sha256(original),
                    original.length,
                    original);
            String basePublication = updates.currentPublicationId(documentId).orElse(null);
            var replacement =
                new DocumentReplacementEntity(
                    UUID.randomUUID().toString(),
                    documentId,
                    baseRevisionId,
                    basePublication,
                    candidate.revisionId(),
                    pipeline,
                    "stored",
                    actor.principalId(),
                    null,
                    null,
                    null,
                    now,
                    now);
            updates.insert(replacement, previous, candidate);
            if (automaticIndexingEnabled) {
              new ImportIndexRepository(store)
                  .insert(
                      actor,
                      documentId,
                      candidate.revisionId(),
                      pipeline,
                      replacement.id(),
                      baseRevisionId);
            }
            if ("corpus".equals(pipeline)) {
              ingestion.enqueueReplacementInTransaction(actor, replacement.id(), candidate);
            }
            return result(actor, documentId, textReady);
          });
    }
  }

  public DocumentReplacementResult index(
      Actor actor, String documentId, String candidateRevisionId, String baseRevisionId) {
    require(actor, documentId);
    ModelValues.identifier(candidateRevisionId, 100);
    ModelValues.identifier(baseRevisionId, 100);
    try (var operation = store.operationGate().enter()) {
      var candidate =
          store.transaction(
              () -> {
                authorizedOriginal(actor, documentId, true);
                var value =
                    updates.current(documentId).orElseThrow(DocumentReplacementService::conflict);
                if (!candidateRevisionId.equals(value.candidateRevisionId())
                    || !baseRevisionId.equals(value.baseRevisionId())
                    || !updates.sourceCurrent(value.id())) {
                  throw conflict();
                }
                return value;
              });
      switch (candidate.pipeline()) {
        case "corpus" -> {
          var processor = indexing.get();
          if (processor == null) {
            throw new ApplicationException(
                FailureKind.UNAVAILABLE, "text_configuration_required", "请先完成并应用文字模型配置。");
          }
          if (candidate.indexJobId() == null) {
            processor.replace(actor, documentId, candidateRevisionId, baseRevisionId);
          } else {
            processor.retry(actor, candidate.indexJobId());
          }
        }
        case "sound" -> {
          if (sounds == null) {
            throw unavailable();
          }
          sounds.buildReplacement(actor, documentId, candidate.id());
        }
        case "video_av" -> {
          if (videos == null) {
            throw unavailable();
          }
          videos.buildReplacement(actor, documentId, candidate.id());
        }
        default -> throw conflict();
      }
      boolean textReady = textReady();
      return store.transaction(() -> result(actor, documentId, textReady));
    }
  }

  private DocumentReplacementResult result(Actor actor, String documentId, boolean textReady) {
    var original = authorizedOriginal(actor, documentId, false);
    boolean writer = permissions.canEdit(management.currentRole(actor, documentId));
    String pipeline = updates.pipeline(documentId);
    boolean canUpload = writer && enabled(pipeline) && updates.canPrepare(documentId);
    var value = updates.current(documentId).orElse(null);
    if (value == null) {
      return new DocumentReplacementResult(
          documentId,
          original.revisionId(),
          updates.currentPublicationId(documentId).orElse(null),
          null,
          pipeline,
          "none",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          canUpload,
          false,
          null);
    }
    var candidate =
        updates
            .original(documentId, value.candidateRevisionId())
            .orElseThrow(ModelValues::notFound);
    TaskResult ingestionTask = ingestionTask(actor, value.ingestionJobId());
    TaskResult indexTask = indexTask(actor, value.indexJobId());
    String state = value.state();
    if (!"published".equals(state)) {
      if (indexTask != null) {
        state =
            Set.of("queued", "processing").contains(indexTask.state())
                ? "indexing"
                : indexTask.state();
      } else if (ingestionTask != null) {
        state = ingestionTask.state();
      }
    }
    boolean canIndex =
        writer
            && updates.sourceCurrent(value.id())
            && ("corpus".equals(pipeline)
                ? textReady
                    && (("parsed".equals(state) && indexTask == null)
                        || (indexTask != null
                            && indexTask.canRetry()
                            && retryCreatorCanWrite(actor, value.indexJobId())))
                : enabled(pipeline) && Set.of("stored", "parsed", "failed").contains(state));
    return new DocumentReplacementResult(
        documentId,
        value.baseRevisionId(),
        value.basePublicationId(),
        value.candidateRevisionId(),
        value.pipeline(),
        state,
        candidate.filename(),
        candidate.documentType(),
        candidate.mediaType(),
        candidate.sourceSha256(),
        candidate.sizeBytes(),
        ingestionTask,
        indexTask,
        canUpload,
        canIndex,
        value.publicationId());
  }

  private TaskResult ingestionTask(Actor actor, String taskId) {
    if (taskId == null) {
      return null;
    }
    var task =
        new IngestionRepository(store)
            .findAuthorizedTask(actor, taskId, false)
            .orElseThrow(ModelValues::notFound);
    return TaskResults.ingestion(
        task,
        permissions.canEdit(task.currentRole()),
        permissions.canEdit(
            management.currentRole(
                new Actor(actor.workspaceId(), task.createdBy()), task.documentId())));
  }

  private TaskResult indexTask(Actor actor, String taskId) {
    if (taskId == null) {
      return null;
    }
    var repository = new IndexingRepository(store);
    var task =
        repository.findAuthorizedTask(actor, taskId, false).orElseThrow(ModelValues::notFound);
    return TaskResults.from(
        task, permissions.canEdit(task.currentRole()), true, repository.publicationId(task.id()));
  }

  private boolean retryCreatorCanWrite(Actor actor, String taskId) {
    var task = new IndexingRepository(store).findInternalTask(taskId).orElse(null);
    return task != null
        && permissions.canEdit(
            management.currentRole(
                new Actor(actor.workspaceId(), task.createdBy()), task.documentId()));
  }

  private DocumentOriginal authorizedOriginal(Actor actor, String documentId, boolean edit) {
    permissions.require(management.currentRole(actor, documentId), edit);
    return management.findDocumentOriginal(actor, documentId).orElseThrow(ModelValues::notFound);
  }

  private boolean textReady() {
    try {
      return indexing.get() != null;
    } catch (ApplicationException unavailable) {
      if (unavailable.kind() != FailureKind.UNAVAILABLE) {
        throw unavailable;
      }
      return false;
    }
  }

  private boolean enabled(String pipeline) {
    return switch (pipeline) {
      case "corpus" -> corpusIngestionEnabled;
      case "sound" -> sounds != null && sounds.configurationCurrent();
      case "video_av" -> videos != null && videos.configurationCurrent();
      default -> false;
    };
  }

  private String canonicalMime(String pipeline, String filename, String contentType) {
    try {
      return switch (pipeline) {
        case "corpus" -> ingestion.prepareUpload(filename, contentType);
        case "sound" -> {
          AudioInput.validateMetadata(filename, contentType);
          yield AudioInput.canonicalMime(filename);
        }
        case "video_av" -> {
          VideoInput.validateMetadata(filename, contentType);
          yield VideoInput.canonicalMime(filename);
        }
        default -> throw conflict();
      };
    } catch (TextParser.Failure unsupported) {
      throw new ApplicationException(
          FailureKind.UNSUPPORTED_MEDIA, "unsupported_document", "文件格式不受当前资料通道支持。");
    }
  }

  private void validateEnvelope(String pipeline, String filename, String mime, byte[] content) {
    try {
      switch (pipeline) {
        case "corpus" -> ingestion.validateUploadEnvelope(filename, mime, content);
        case "sound" -> AudioInput.validateEnvelope(filename, mime, content);
        case "video_av" -> VideoInput.validateEnvelope(filename, mime, content);
        default -> throw conflict();
      }
    } catch (TextParser.Failure unsupported) {
      throw new ApplicationException(
          FailureKind.UNSUPPORTED_MEDIA, "unsupported_document", "文件内容不符合当前资料格式。");
    }
  }

  private static String type(String mime) {
    if (mime.startsWith("image/")) {
      return "image";
    }
    if (mime.startsWith("audio/")) {
      return "audio";
    }
    return mime.startsWith("video/") ? "video" : "document";
  }

  private static void require(Actor actor, String documentId) {
    if (actor == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(documentId, 100);
  }

  private static ApplicationException conflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "replacement_state_conflict", "当前资料或候选版本已变化，请刷新后继续。");
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "replacement_unavailable", "当前资料的更新通道未启用。");
  }
}
