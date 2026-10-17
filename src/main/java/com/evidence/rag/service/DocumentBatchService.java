package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchImportFile;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.repository.DocumentBatchRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Manifest registration, idempotent file admission and authoritative batch progress. */
public final class DocumentBatchService {
  private final SqliteAuthorityStore store;
  private final DocumentBatchRepository batches;
  private final IngestionService ingestion;
  private final int maxFiles;
  private final int uploadConcurrency;

  public DocumentBatchService(
      SqliteAuthorityStore store, IngestionService ingestion, int maxFiles) {
    this(store, ingestion, maxFiles, 2);
  }

  public DocumentBatchService(
      SqliteAuthorityStore store, IngestionService ingestion, int maxFiles, int uploadConcurrency) {
    this.store = store;
    this.batches = new DocumentBatchRepository(store);
    this.ingestion = ingestion;
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
    long bytes = 0;
    var canonical = new StringBuilder().append(budget).append('\n');
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
    if (bytes > 256L * 1024 * 1024) throw ModelValues.invalid();
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
              batches.insert(
                  actor,
                  created,
                  command.requestId(),
                  digest,
                  budget,
                  Instant.now().toString(),
                  command.files().stream()
                      .map(
                          f ->
                              new BatchImportFile.Manifest(
                                  f.filename(), f.sizeBytes(), f.sourceSha256()))
                      .toList());
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
          String state =
              row.get("halted_reason") != null
                      || counts.containsKey("failed")
                      || counts.containsKey("cancelled")
                      || counts.containsKey("deleted")
                  ? "halted"
                  : counts.getOrDefault("indexed", 0L) == total
                      ? "completed"
                      : counts.getOrDefault("awaiting_upload", 0L) == total
                          ? "awaiting_upload"
                          : "processing";
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
              (String) row.get("halted_reason"),
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
                              f.errorCode()))
                  .toList());
        });
  }

  private static ApplicationException conflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "batch_upload_conflict", "批次文件内容或处理状态已改变，请刷新批次。");
  }
}
