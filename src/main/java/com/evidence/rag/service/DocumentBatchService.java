package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchImportFile;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchActionResult;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.repository.DocumentBatchRepository;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Manifest registration, idempotent file admission and authoritative batch progress. */
public final class DocumentBatchService {
  private static final String CANCELLED = "batch_cancelled";
  private final SqliteAuthorityStore store;
  private final DocumentBatchRepository batches;
  private final IngestionService ingestion;
  private final IndexingService indexing;
  private final int maxFiles;
  private final int uploadConcurrency;

  public DocumentBatchService(
      SqliteAuthorityStore store, IngestionService ingestion, int maxFiles) {
    this(store, ingestion, null, maxFiles, 2);
  }

  public DocumentBatchService(
      SqliteAuthorityStore store,
      IngestionService ingestion,
      IndexingService indexing,
      int maxFiles,
      int uploadConcurrency) {
    this.store = store;
    this.batches = new DocumentBatchRepository(store);
    this.ingestion = ingestion;
    this.indexing = indexing;
    this.maxFiles = maxFiles;
    this.uploadConcurrency = uploadConcurrency;
  }

  public int uploadConcurrency() {
    return uploadConcurrency;
  }

  public int maximumUploadBytes() {
    return ingestion.maximumUploadBytes();
  }

  public DocumentBatchResult create(Actor actor, DocumentBatchCommand command) {
    if (actor == null
        || command == null
        || command.files() == null
        || command.files().isEmpty()
        || command.files().size() > maxFiles) throw ModelValues.invalid();
    ModelValues.identifier(command.requestId(), 128);
    int budget =
        command.embeddingRequestLimit() == null ? 1000000 : command.embeddingRequestLimit();
    if (budget < 1 || budget > 1000000) throw ModelValues.invalid();
    boolean includeDuplicates = Boolean.TRUE.equals(command.includeDuplicates());
    long bytes = 0;
    var canonical = new StringBuilder().append(budget).append('\n');
    // Only an opt-out changes the digest, so manifests registered before v40 keep theirs.
    if (includeDuplicates) canonical.append("include_duplicates\n");
    for (var file : command.files()) {
      if (file == null
          || file.sizeBytes() < 1
          || file.sizeBytes() > ingestion.maximumUploadBytes()
          || file.sourceSha256() == null
          || !file.sourceSha256().matches("[a-f0-9]{64}")) throw ModelValues.invalid();
      ingestion.prepareUpload(file.filename(), "application/octet-stream");
      bytes = Math.addExact(bytes, file.sizeBytes());
      canonical
          .append(file.filename())
          .append('\t')
          .append(file.sizeBytes())
          .append('\t')
          .append(file.sourceSha256())
          .append('\n');
    }
    if (bytes > ingestion.maximumWorkspaceOriginalBytes()) throw ModelValues.invalid();
    String digest = ModelValues.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    String id =
        store.transaction(
            () -> {
              var existing = batches.byRequest(actor, command.requestId());
              if (existing.isPresent()) {
                if (!digest.equals(existing.orElseThrow().get("manifest_sha256"))) throw conflict();
                return (String) existing.orElseThrow().get("id");
              }
              String created = UUID.randomUUID().toString();
              var manifest =
                  command.files().stream()
                      .map(
                          f ->
                              new BatchImportFile.Manifest(
                                  f.filename(), f.sizeBytes(), f.sourceSha256()))
                      .toList();
              batches.insert(
                  actor,
                  created,
                  command.requestId(),
                  digest,
                  budget,
                  Instant.now().toString(),
                  manifest);
              if (!includeDuplicates) batches.markDuplicates(actor, created, manifest);
              return created;
            });
    return status(actor, id, 0, 100);
  }

  public TaskResult upload(Actor actor, String batchId, int ordinal, byte[] bytes) {
    ModelValues.identifier(batchId, 128);
    if (ordinal < 0
        || bytes == null
        || bytes.length == 0
        || bytes.length > ingestion.maximumUploadBytes()) throw ModelValues.invalid();
    byte[] original = bytes.clone();
    String digest = ModelValues.sha256(original);
    String task =
        store.transaction(
            () -> {
              var batch = batches.find(actor, batchId).orElseThrow(ModelValues::notFound);
              var item = batches.file(batchId, ordinal);
              if (!digest.equals(item.get("source_sha256"))
                  || ((Number) item.get("size_bytes")).longValue() != original.length)
                throw conflict();
              if (item.get("ingestion_task_id") != null)
                return (String) item.get("ingestion_task_id");
              if (batches.duplicate(batchId, ordinal)) throw duplicate();
              if (batch.get("halted_reason") != null) throw conflict();
              String filename = (String) item.get("filename");
              var result =
                  ingestion.uploadDocumentInTransaction(
                      actor,
                      filename,
                      ingestion.prepareUpload(filename, "application/octet-stream"),
                      original);
              batches.received(
                  batchId, ordinal, result.documentId(), result.revisionId(), result.taskId());
              return result.taskId();
            });
    return ingestion.ingestionStatus(actor, task);
  }

  public DocumentBatchResult status(Actor actor, String id, int offset, int limit) {
    ModelValues.identifier(id, 128);
    if (offset < 0 || limit < 1 || limit > 100) throw ModelValues.invalid();
    return store.transaction(
        () -> {
          var row = batches.find(actor, id).orElseThrow(ModelValues::notFound);
          var counts = batches.counts(id);
          int total = counts.values().stream().mapToInt(Long::intValue).sum();
          String halted = (String) row.get("halted_reason");
          long skipped = counts.getOrDefault("duplicate", 0L);
          long settled =
              counts.getOrDefault("indexed", 0L)
                  + skipped
                  + counts.getOrDefault("failed", 0L)
                  + counts.getOrDefault("cancelled", 0L)
                  + counts.getOrDefault("deleted", 0L);
          long awaiting = counts.getOrDefault("awaiting_upload", 0L);
          // A failed or cancelled file no longer stops the batch: only a recorded halt does.
          // A skipped duplicate is done: it counts toward completion like an indexed file.
          String state =
              CANCELLED.equals(halted)
                  ? "cancelled"
                  : halted != null
                      ? "halted"
                      : counts.getOrDefault("indexed", 0L) + skipped == total
                          ? "completed"
                          : awaiting > 0 && awaiting + skipped == total
                              ? "awaiting_upload"
                              : settled == total ? "completed_with_failures" : "processing";
          long retryable = batches.retryable(id);
          var actions = new java.util.ArrayList<String>();
          if (retryable > 0 && !CANCELLED.equals(halted)) actions.add("retry_failed");
          if (!CANCELLED.equals(halted) && settled < total) actions.add("cancel");
          if (halted != null) actions.add("resume");
          return new DocumentBatchResult(
              id,
              state,
              total,
              offset,
              limit,
              counts,
              ((Number) row.get("request_limit")).longValue(),
              ((Number) row.get("reserved_requests")).longValue(),
              batches.observed(id),
              batches.uncertain(id),
              halted,
              retryable,
              List.copyOf(actions),
              batches.files(id, offset, limit).stream()
                  .map(
                      f ->
                          new DocumentBatchResult.File(
                              f.ordinal(),
                              f.filename(),
                              f.sizeBytes(),
                              f.sourceSha256(),
                              f.state(),
                              f.documentId(),
                              f.revisionId(),
                              f.ingestionTaskId(),
                              f.indexingTaskId(),
                              f.errorCode(),
                              f.duplicateDocumentId(),
                              f.duplicateOrdinal()))
                  .toList());
        });
  }

  /**
   * Queues every failed or cancelled file of the batch again that still has attempts left: a parse
   * retry (which also re-enters auto-index), a failed auto-index dispatch, or an index retry. Each
   * is an explicit user retry, so it runs even while the batch is halted.
   */
  public DocumentBatchActionResult retryFailed(Actor actor, String id) {
    ModelValues.identifier(id, 128);
    int affected =
        store.transaction(
            () -> {
              var row = batches.find(actor, id).orElseThrow(ModelValues::notFound);
              if (CANCELLED.equals(row.get("halted_reason"))) throw actionConflict();
              var requests = new ImportIndexRepository(store);
              int count = 0;
              for (var file : batches.actionRows(id)) {
                String parse = (String) file.get("parse_state");
                String parseTask = (String) file.get("ingestion_task_id");
                String indexTask = (String) file.get("index_task");
                if ("failed".equals(parse) || "cancelled".equals(parse)) {
                  if (ingestion.retryBatchIngestionInTransaction(actor, parseTask)) count++;
                } else if ("parsed".equals(parse)
                    && "failed".equals(file.get("request_state"))
                    && file.get("request_task") == null) {
                  if (requests.retryFailed((String) file.get("revision_id"))) count++;
                } else if ("parsed".equals(parse)
                    && indexing != null
                    && indexTask != null
                    && Set.of("failed", "cancelled").contains(file.get("index_state"))) {
                  if (indexing.retryBatchIndexingInTransaction(actor, indexTask)) count++;
                }
              }
              return count;
            });
    return new DocumentBatchActionResult(id, "retry_failed", affected, status(actor, id, 0, 100));
  }

  /**
   * Cancels the batch: queued parsing and index tasks stop, no further upload is admitted and no
   * automatic work starts. Running tasks finish; resume continues the batch.
   */
  public DocumentBatchActionResult cancel(Actor actor, String id) {
    ModelValues.identifier(id, 128);
    int affected =
        store.transaction(
            () -> {
              var row = batches.find(actor, id).orElseThrow(ModelValues::notFound);
              if (CANCELLED.equals(row.get("halted_reason"))) throw actionConflict();
              batches.cancel(id);
              int count = 0;
              for (var task : batches.queuedTasks(id)) {
                String parse = (String) task.get("parse_task");
                String index = (String) task.get("index_task");
                if (parse != null && ingestion.cancelBatchIngestionInTransaction(actor, parse))
                  count++;
                if (index != null
                    && indexing != null
                    && indexing.cancelBatchIndexingInTransaction(actor, index)) count++;
              }
              return count;
            });
    return new DocumentBatchActionResult(id, "cancel", affected, status(actor, id, 0, 100));
  }

  /**
   * Clears the batch's halt (budget, usage audit or a user cancel), optionally with a new embedding
   * request limit, and queues its cancelled automatic tasks again. A limit below the requests
   * already reserved is rejected.
   */
  public DocumentBatchActionResult resume(Actor actor, String id, Integer requestLimit) {
    ModelValues.identifier(id, 128);
    if (requestLimit != null && (requestLimit < 1 || requestLimit > 1000000))
      throw ModelValues.invalid();
    int affected =
        store.transaction(
            () -> {
              var row = batches.find(actor, id).orElseThrow(ModelValues::notFound);
              if (row.get("halted_reason") == null) throw actionConflict();
              if (requestLimit != null
                  && requestLimit < ((Number) row.get("reserved_requests")).longValue())
                throw ModelValues.invalid();
              batches.resume(id, requestLimit);
              int count = 0;
              for (var file : batches.actionRows(id)) {
                if ("cancelled".equals(file.get("parse_state"))
                    && truthy(file.get("parse_automatic"))) {
                  if (ingestion.resumeBatchIngestionInTransaction(
                      actor, (String) file.get("ingestion_task_id"))) count++;
                } else if ("cancelled".equals(file.get("index_state"))
                    && truthy(file.get("index_automatic"))
                    && indexing != null) {
                  if (indexing.resumeBatchIndexingInTransaction(
                      actor, (String) file.get("index_task"))) count++;
                }
              }
              return count;
            });
    return new DocumentBatchActionResult(id, "resume", affected, status(actor, id, 0, 100));
  }

  private static boolean truthy(Object value) {
    return value instanceof Number number && number.intValue() != 0;
  }

  private static ApplicationException actionConflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "batch_action_conflict", "批次当前状态不能执行该操作，请刷新批次。");
  }

  private static ApplicationException duplicate() {
    return new ApplicationException(
        FailureKind.CONFLICT, "batch_file_duplicate", "该文件与已有资料相同，已跳过，无需上传。");
  }

  private static ApplicationException conflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "batch_upload_conflict", "批次文件内容或处理状态已改变，请刷新批次。");
  }
}
