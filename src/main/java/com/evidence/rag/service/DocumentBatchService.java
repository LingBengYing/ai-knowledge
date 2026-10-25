package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.BatchImportFile;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.DocumentBatchActionResult;
import com.evidence.rag.model.dto.DocumentBatchAppendCommand;
import com.evidence.rag.model.dto.DocumentBatchAppendResult;
import com.evidence.rag.model.dto.DocumentBatchCommand;
import com.evidence.rag.model.dto.DocumentBatchList;
import com.evidence.rag.model.dto.DocumentBatchResult;
import com.evidence.rag.model.dto.DocumentBatchSourcesResult;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.repository.DocumentBatchRepository;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Manifest registration, idempotent file admission and authoritative batch progress. */
public final class DocumentBatchService {
  private static final String CANCELLED = "batch_cancelled";
  private final SqliteAuthorityStore store;
  private final DocumentBatchRepository batches;
  private final BatchPlacementService placement;
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
    this.placement = new BatchPlacementService(new ManagementRepository(store));
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
    if (command != null && Boolean.TRUE.equals(command.open())) return createOpen(actor, command);
    if (actor == null
        || command == null
        || command.expectedTotal() != null
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
    appendPlacementDigest(canonical, command);
    for (var file : command.files()) {
      if (file == null) throw ModelValues.invalid();
      checkEntry(file.filename(), file.sizeBytes(), file.sourceSha256());
      String path = BatchImportFile.relativePath(file.relativePath(), file.filename());
      bytes = Math.addExact(bytes, file.sizeBytes());
      canonical
          .append(file.filename())
          .append('\t')
          .append(file.sizeBytes())
          .append('\t')
          .append(file.sourceSha256());
      // Only a folder path changes the digest, so manifests without paths keep theirs.
      if (path != null) canonical.append('\t').append(path);
      canonical.append('\n');
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
              var destination =
                  placement.prepare(
                      actor,
                      command.targetFolderId(),
                      Boolean.TRUE.equals(command.preservePaths()));
              var manifest =
                  command.files().stream()
                      .map(
                          f ->
                              new BatchImportFile.Manifest(
                                  f.filename(),
                                  f.sizeBytes(),
                                  f.sourceSha256(),
                                  BatchImportFile.relativePath(f.relativePath(), f.filename())))
                      .toList();
              for (var entry : manifest) placement.validatePath(destination, entry.relativePath());
              batches.insert(
                  actor,
                  created,
                  command.requestId(),
                  digest,
                  budget,
                  Instant.now().toString(),
                  manifest);
              batches.savePlacement(created, destination);
              if (!includeDuplicates) batches.markDuplicates(actor, created, manifest);
              return created;
            });
    return status(actor, id, 0, 100);
  }

  /** The most entries one append may carry; a browser sends far fewer at a time. */
  public static final int MAX_APPEND = 1000;

  private void checkEntry(String filename, long sizeBytes, String sha256) {
    if (sizeBytes < 1
        || sizeBytes > ingestion.maximumUploadBytes()
        || sha256 == null
        || !sha256.matches("[a-f0-9]{64}")) throw ModelValues.invalid();
    ingestion.prepareUpload(filename, "application/octet-stream");
  }

  /**
   * Registers an open batch: no entries yet, {@code expected_total} announced (at most the batch
   * file limit). Entries are appended in ordinal order and the batch is sealed afterwards; until
   * then it never counts as completed. The same request ID with the same options returns the same
   * batch, so a lost reply is safely repeated.
   */
  private DocumentBatchResult createOpen(Actor actor, DocumentBatchCommand command) {
    Integer expected = command.expectedTotal();
    if (actor == null
        || (command.files() != null && !command.files().isEmpty())
        || expected == null
        || expected < 1
        || expected > maxFiles) throw ModelValues.invalid();
    ModelValues.identifier(command.requestId(), 128);
    int budget =
        command.embeddingRequestLimit() == null ? 1000000 : command.embeddingRequestLimit();
    if (budget < 1 || budget > 1000000) throw ModelValues.invalid();
    boolean includeDuplicates = Boolean.TRUE.equals(command.includeDuplicates());
    // A one-shot manifest line has three or more tab-separated fields, so this never collides.
    var canonical = new StringBuilder().append(budget).append('\n');
    if (includeDuplicates) canonical.append("include_duplicates\n");
    canonical.append("open\t").append(expected).append('\n');
    appendPlacementDigest(canonical, command);
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
                  List.of());
              batches.open(created, expected, includeDuplicates);
              batches.savePlacement(
                  created,
                  placement.prepare(
                      actor,
                      command.targetFolderId(),
                      Boolean.TRUE.equals(command.preservePaths())));
              return created;
            });
    return status(actor, id, 0, 100);
  }

  private static void appendPlacementDigest(StringBuilder canonical, DocumentBatchCommand command) {
    if (command.targetFolderId() != null) {
      ModelValues.identifier(command.targetFolderId(), 100);
      canonical.append("target_folder\t").append(command.targetFolderId()).append('\n');
    }
    if (Boolean.TRUE.equals(command.preservePaths())) canonical.append("preserve_paths\n");
  }

  /**
   * Appends manifest entries to an open batch. Ordinals are consecutive within the request; an
   * entry at an already registered ordinal must equal it (a replay of a lost reply succeeds),
   * otherwise 409. New entries continue right after the last registered one, so a lost chunk is
   * never skipped over. Unless the batch was opened with duplicates included, each new entry whose
   * bytes are already in the library, or repeat an earlier entry of the batch, is skipped as a
   * duplicate. A sealed or cancelled batch takes no new entries.
   */
  public DocumentBatchAppendResult append(
      Actor actor, String batchId, DocumentBatchAppendCommand command) {
    ModelValues.identifier(batchId, 128);
    if (actor == null
        || command == null
        || command.files() == null
        || command.files().isEmpty()
        || command.files().size() > MAX_APPEND) throw ModelValues.invalid();
    var entries = command.files();
    var manifest = new java.util.ArrayList<BatchImportFile.Manifest>(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      var entry = entries.get(i);
      if (entry == null
          || entry.ordinal() == null
          || entry.ordinal() < 0
          || entry.ordinal() >= maxFiles
          || entry.ordinal() != entries.getFirst().ordinal() + i) throw ModelValues.invalid();
      checkEntry(entry.filename(), entry.sizeBytes(), entry.sourceSha256());
      manifest.add(
          new BatchImportFile.Manifest(
              entry.filename(),
              entry.sizeBytes(),
              entry.sourceSha256(),
              BatchImportFile.relativePath(entry.relativePath(), entry.filename())));
    }
    int first = entries.getFirst().ordinal(), end = first + entries.size();
    return store.transaction(
        () -> {
          var batch = batches.find(actor, batchId).orElseThrow(ModelValues::notFound);
          var destination = batches.placement(batchId);
          for (var entry : manifest) placement.validatePath(destination, entry.relativePath());
          var stream = batches.stream(batchId).orElseThrow(DocumentBatchService::notOpen);
          int expected = ((Number) stream.get("expected_total")).intValue();
          int registered = batches.registered(batchId);
          if (first > registered) throw gap(registered);
          // Entries already registered must be repeated exactly.
          var stored = batches.entries(batchId, first, Math.min(end, registered));
          for (var row : stored) {
            var entry = manifest.get(((Number) row.get("ordinal")).intValue() - first);
            if (!entry.filename().equals(row.get("filename"))
                || entry.sizeBytes() != ((Number) row.get("size_bytes")).longValue()
                || !entry.sourceSha256().equals(row.get("source_sha256"))
                || !java.util.Objects.equals(entry.relativePath(), row.get("relative_path")))
              throw conflict();
          }
          var added =
              manifest.subList(Math.min(registered - first, manifest.size()), manifest.size());
          if (!added.isEmpty()) {
            if (stream.get("sealed_at") != null) throw sealed();
            if (CANCELLED.equals(batch.get("halted_reason"))) throw actionConflict();
            if (end > expected) throw ModelValues.invalid();
            long bytes = batches.registeredBytes(batchId);
            for (var entry : added) bytes = Math.addExact(bytes, entry.sizeBytes());
            if (bytes > ingestion.maximumWorkspaceOriginalBytes()) throw ModelValues.invalid();
            batches.append(batchId, registered, added);
            if (((Number) stream.get("include_duplicates")).intValue() == 0)
              batches.markDuplicates(
                  actor,
                  batchId,
                  registered,
                  added,
                  batches.firstOrdinals(
                      batchId,
                      registered,
                      added.stream()
                          .map(BatchImportFile.Manifest::sourceSha256)
                          .distinct()
                          .toList()));
          }
          var items =
              batches.entryStates(batchId, first, end).stream()
                  .map(
                      row ->
                          new DocumentBatchAppendResult.Item(
                              ((Number) row.get("ordinal")).intValue(),
                              (String) row.get("state"),
                              (String) row.get("duplicate_document"),
                              row.get("duplicate_ordinal") == null
                                  ? null
                                  : ((Number) row.get("duplicate_ordinal")).intValue()))
                  .toList();
          return new DocumentBatchAppendResult(
              batchId, Math.max(registered, end), expected, stream.get("sealed_at") == null, items);
        });
  }

  /**
   * Closes an open batch's manifest with the entries registered so far (possibly fewer than
   * announced). Sealing again is harmless; a one-shot batch has nothing to seal.
   */
  public DocumentBatchActionResult seal(Actor actor, String id) {
    ModelValues.identifier(id, 128);
    int affected =
        store.transaction(
            () -> {
              batches.find(actor, id).orElseThrow(ModelValues::notFound);
              batches.stream(id).orElseThrow(DocumentBatchService::notOpen);
              return batches.seal(id) ? 1 : 0;
            });
    return new DocumentBatchActionResult(id, "seal", affected, status(actor, id, 0, 100));
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
              placement.place(
                  actor,
                  result.documentId(),
                  batches.placement(batchId),
                  batches.relativePath(batchId, ordinal));
              return result.taskId();
            });
    return ingestion.ingestionStatus(actor, task);
  }

  public DocumentBatchResult status(Actor actor, String id, int offset, int limit) {
    return status(actor, id, List.of(), offset, limit);
  }

  /** File states a batch page can be filtered by. */
  public static final Set<String> FILE_STATES =
      Set.of(
          "awaiting_upload",
          "queued",
          "parsing",
          "parsed",
          "indexing",
          "indexed",
          "failed",
          "cancelled",
          "deleted",
          "duplicate");

  /** The window the batch page's rate is measured over. */
  static final Duration THROUGHPUT_WINDOW = Duration.ofMinutes(10);

  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /**
   * One page of the batch. With {@code states}, only files in one of those states are paged ({@code
   * matched} says how many); counts, state and actions always describe the whole batch.
   */
  public DocumentBatchResult status(
      Actor actor, String id, List<String> states, int offset, int limit) {
    ModelValues.identifier(id, 128);
    if (offset < 0
        || limit < 1
        || limit > 100
        || states == null
        || states.size() > FILE_STATES.size()
        || !FILE_STATES.containsAll(states)
        || Set.copyOf(states).size() != states.size()) throw ModelValues.invalid();
    return store.transaction(
        () -> {
          var row = batches.find(actor, id).orElseThrow(ModelValues::notFound);
          var destination = batches.placement(id);
          var counts = batches.counts(id);
          int total = counts.values().stream().mapToInt(Long::intValue).sum();
          String halted = (String) row.get("halted_reason");
          var stream = batches.stream(id);
          boolean open = stream.isPresent() && stream.orElseThrow().get("sealed_at") == null;
          String state = state(counts, halted, open);
          long settled = settled(counts);
          int matched =
              states.isEmpty()
                  ? total
                  : states.stream()
                      .mapToInt(name -> counts.getOrDefault(name, 0L).intValue())
                      .sum();
          long retryable = batches.retryable(id);
          var actions = new java.util.ArrayList<String>();
          if (retryable > 0 && !CANCELLED.equals(halted)) actions.add("retry_failed");
          if (!CANCELLED.equals(halted) && (settled < total || open)) actions.add("cancel");
          if (halted != null) actions.add("resume");
          if (open) actions.add("seal");
          String created = (String) row.get("created_at");
          Instant now = Instant.now();
          Instant start = now.minus(THROUGHPUT_WINDOW);
          try {
            Instant began = Instant.parse(created);
            if (began.isAfter(start)) start = began;
          } catch (java.time.format.DateTimeParseException ignored) {
            // A malformed creation time only widens the window to its full length.
          }
          if (start.isAfter(now)) start = now;
          var throughput =
              new DocumentBatchResult.Throughput(
                  Duration.between(start, now).toSeconds(),
                  batches.finishedSince(id, MILLIS.format(start)),
                  MILLIS.format(now));
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
              batches.files(id, states, offset, limit).stream()
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
                              f.duplicateOrdinal(),
                              f.relativePath(),
                              f.startedAt(),
                              f.finishedAt()))
                  .toList(),
              created,
              batches.updatedAt(id),
              List.copyOf(states),
              matched,
              throughput,
              open,
              stream.map(r -> ((Number) r.get("expected_total")).intValue()).orElse(null),
              destination.targetFolderId(),
              destination.targetFolderName(),
              destination.preservePaths());
        });
  }

  /** Bounded batch metadata, not a whole-library compile or a read of every source's body. */
  public DocumentBatchSourcesResult sources(
      Actor actor, String id, String query, String folder, int offset, int limit) {
    ModelValues.identifier(id, 128);
    if (actor == null || offset < 0 || limit < 1 || limit > 100) throw ModelValues.invalid();
    String search = query == null ? "" : query.strip();
    ModelValues.bounded(search, 200);
    String prefix = folder == null ? "" : folder;
    if (!prefix.isEmpty()) BatchImportFile.relativePath(prefix + "/source.txt", "source.txt");
    return store.transaction(
        () -> {
          batches.find(actor, id).orElseThrow(ModelValues::notFound);
          var candidates = batches.sourceCandidates(id);
          var publications =
              new java.util.HashMap<String, com.evidence.rag.model.domain.PublicationVersion>();
          var documentIds =
              candidates.stream().map(row -> (String) row.get("document_id")).distinct().toList();
          // Keep SQL parameter counts bounded while reusing the complete authority publication
          // checks.
          for (int start = 0; start < documentIds.size(); start += 256)
            new EvidenceRepository(store)
                .findActivePublications(
                    actor,
                    DocumentSelection.selected(
                        documentIds.subList(start, Math.min(start + 256, documentIds.size()))))
                .forEach(publication -> publications.put(publication.documentId(), publication));
          var items = new java.util.ArrayList<DocumentBatchSourcesResult.Item>();
          var folders = new java.util.TreeMap<String, Integer>();
          String needle = search.toLowerCase(java.util.Locale.ROOT);
          for (var row : candidates) {
            var publication = publications.get(row.get("document_id"));
            if (publication == null
                || !publication.sourceRevisionId().equals(row.get("revision_id"))) continue;
            String path = (String) row.get("relative_path");
            String parent = BatchPlacementService.folder(path);
            folders.merge(parent, 1, Integer::sum);
            if (!prefix.isEmpty() && !parent.equals(prefix) && !parent.startsWith(prefix + "/"))
              continue;
            String filename = (String) row.get("filename"),
                displayName = (String) row.get("display_name");
            if (!needle.isEmpty()
                && !java.util.stream.Stream.of(filename, displayName, path)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains(needle)))
              continue;
            items.add(
                new DocumentBatchSourcesResult.Item(
                    ((Number) row.get("ordinal")).intValue(),
                    publication.documentId(),
                    filename,
                    displayName,
                    path,
                    publication.publicationId(),
                    publication.sourceRevisionId()));
          }
          int from = Math.min(offset, items.size()),
              to = (int) Math.min((long) from + limit, items.size());
          return new DocumentBatchSourcesResult(
              id,
              items.size(),
              offset,
              limit,
              List.copyOf(items.subList(from, to)),
              folders.entrySet().stream()
                  .map(
                      entry ->
                          new DocumentBatchSourcesResult.Folder(entry.getKey(), entry.getValue()))
                  .toList());
        });
  }

  /**
   * The workspace's batches, newest first. Batches belong to the workspace, as for every other
   * batch operation: any member who can open a batch by its id sees it listed.
   */
  public DocumentBatchList list(Actor actor, int offset, int limit) {
    if (actor == null || offset < 0 || limit < 1 || limit > 100) throw ModelValues.invalid();
    return store.transaction(
        () -> {
          int total = batches.countForWorkspace(actor);
          var rows = batches.page(actor, offset, limit);
          var ids = rows.stream().map(row -> (String) row.get("id")).toList();
          var counts = batches.counts(ids);
          var streams = batches.streams(ids);
          return new DocumentBatchList(
              offset,
              limit,
              total,
              rows.stream()
                  .map(
                      row -> {
                        String id = (String) row.get("id");
                        var batchCounts = counts.getOrDefault(id, Map.of());
                        String halted = (String) row.get("halted_reason");
                        var stream = streams.get(id);
                        boolean open = stream != null && stream.get("sealed_at") == null;
                        return new DocumentBatchList.Item(
                            id,
                            state(batchCounts, halted, open),
                            batchCounts.values().stream().mapToInt(Long::intValue).sum(),
                            batchCounts,
                            halted,
                            (String) row.get("created_at"),
                            (String) row.get("updated_at"),
                            open,
                            stream == null
                                ? null
                                : ((Number) stream.get("expected_total")).intValue());
                      })
                  .toList());
        });
  }

  private static long settled(Map<String, Long> counts) {
    return counts.getOrDefault("indexed", 0L)
        + counts.getOrDefault("duplicate", 0L)
        + counts.getOrDefault("failed", 0L)
        + counts.getOrDefault("cancelled", 0L)
        + counts.getOrDefault("deleted", 0L);
  }

  /**
   * The batch state from its file counts, recorded halt and whether its manifest is still open. An
   * open batch expects more files, so it waits for uploads instead of ever being completed.
   */
  private static String state(Map<String, Long> counts, String halted, boolean open) {
    String state = state(counts, halted);
    return open && state.startsWith("completed") ? "awaiting_upload" : state;
  }

  private static String state(Map<String, Long> counts, String halted) {
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long skipped = counts.getOrDefault("duplicate", 0L);
    long settled = settled(counts);
    long awaiting = counts.getOrDefault("awaiting_upload", 0L);
    // A failed or cancelled file no longer stops the batch: only a recorded halt does.
    // A skipped duplicate is done: it counts toward completion like an indexed file.
    return CANCELLED.equals(halted)
        ? "cancelled"
        : halted != null
            ? "halted"
            : counts.getOrDefault("indexed", 0L) + skipped == total
                ? "completed"
                : awaiting > 0 && awaiting + skipped == total
                    ? "awaiting_upload"
                    : settled == total ? "completed_with_failures" : "processing";
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

  private static ApplicationException notOpen() {
    return new ApplicationException(
        FailureKind.CONFLICT, "batch_not_open", "该批次的清单已一次登记完成，不能追加或结束登记。");
  }

  private static ApplicationException sealed() {
    return new ApplicationException(FailureKind.CONFLICT, "batch_sealed", "批次已结束登记，不能再追加文件。");
  }

  private static ApplicationException gap(int registered) {
    return new ApplicationException(
        FailureKind.CONFLICT,
        "batch_manifest_gap",
        "批次已登记 " + registered + " 份文件，追加须从第 " + (registered + 1) + " 份接着登记，请刷新批次。");
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
