package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.model.entity.SynopsisTaskEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** SQL-only synopsis task and immutable result persistence in the caller's short transaction. */
public final class SynopsisRepository {
  private static final String TASK_SELECT =
      """
      SELECT p.*,t.id AS task_id,t.workspace_id,t.created_by,
        t.model_revision AS synopsis_model_revision,t.policy_revision,t.state,
        t.claim_token_sha256,t.input_fingerprint,t.error_code,
        t.created_at AS task_created_at,t.updated_at AS task_updated_at
      FROM synopsis_tasks t JOIN index_publications p ON p.id=t.publication_id
      """;
  private final SqliteAuthorityStore store;

  public SynopsisRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public int pendingCount(String workspace) {
    return Math.toIntExact(
        store.count(
            "SELECT COUNT(*) FROM synopsis_tasks WHERE workspace_id=? AND state IN ('queued','processing')",
            workspace));
  }

  public boolean hasProcessing(String workspace) {
    return store.count(
            "SELECT COUNT(*) FROM synopsis_tasks WHERE workspace_id=? AND state='processing'",
            workspace)
        > 0;
  }

  public List<String> queuedIds(String workspace) {
    return store
        .rows(
            "SELECT id FROM synopsis_tasks WHERE workspace_id=? AND state='queued' ORDER BY created_at,id",
            workspace)
        .stream()
        .map(row -> text(row, "id"))
        .toList();
  }

  public void insertTask(
      String id,
      Actor creator,
      PublicationVersion publication,
      String model,
      String policy,
      String now) {
    store.execute(
        """
        INSERT INTO synopsis_tasks(id,workspace_id,document_id,revision_id,publication_id,model_revision,policy_revision,state,created_by,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?,'queued',?,?,?)
        """,
        id,
        creator.workspaceId(),
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.publicationId(),
        model,
        policy,
        creator.principalId(),
        now,
        now);
  }

  public Optional<SynopsisTaskEntity> findTask(String id) {
    return store.rows(TASK_SELECT + " WHERE t.id=?", id).stream()
        .map(SynopsisRepository::task)
        .findFirst();
  }

  public Optional<SynopsisTaskEntity> findReusable(
      String documentId, String publicationId, String model, String policy) {
    return store
        .rows(
            TASK_SELECT
                + " WHERE t.document_id=? AND t.publication_id=? AND t.model_revision=? AND t.policy_revision=? AND t.state IN ('queued','processing','available') ORDER BY t.created_at DESC,t.id DESC LIMIT 1",
            documentId,
            publicationId,
            model,
            policy)
        .stream()
        .map(SynopsisRepository::task)
        .findFirst();
  }

  public boolean markProcessing(String id, String tokenHash, SynopsisFileInput input, String now) {
    var found = findTask(id);
    if (found.isEmpty()
        || !found.get().state().equals("queued")
        || !found.get().publication().equals(input.publication())) {
      return false;
    }
    for (int ordinal = 0; ordinal < input.evidence().size(); ordinal++) {
      insertInput(id, input.publication().publicationId(), ordinal, input.evidence().get(ordinal));
    }
    store.execute(
        "UPDATE synopsis_tasks SET state='processing',claim_token_sha256=?,input_fingerprint=?,input_count=?,updated_at=? WHERE id=? AND state='queued'",
        tokenHash,
        input.fingerprint(),
        input.evidence().size(),
        now,
        id);
    return true;
  }

  public boolean markProcessing(String id, String tokenHash, SynopsisInput input, String now) {
    return markProcessing(
        id, tokenHash, new SynopsisFileInput(input.publication(), input.evidence()), now);
  }

  private void insertInput(
      String taskId, String publicationId, int ordinal, SynopsisEvidence evidence) {
    String column;
    String table;
    switch (evidence.kind()) {
      case TEXT, IMAGE_OCR -> {
        column = "source_segment_id";
        table = "index_publication_entries";
      }
      case IMAGE -> {
        column = "image_evidence_id";
        table = "image_publication_entries";
      }
      case AUDIO_TRANSCRIPT -> {
        column = "audio_span_id";
        table = "audio_publication_entries";
      }
      case VIDEO_FRAME -> {
        column = "video_frame_id";
        table = "video_frame_publication_entries";
      }
      case VIDEO_TRANSCRIPT -> {
        column = "video_transcript_span_id";
        table = "video_transcript_publication_entries";
      }
      case VIDEO_OCR -> {
        column = "video_ocr_segment_id";
        table = "video_ocr_publication_entries";
      }
      case VIDEO_SUBTITLE -> {
        column = "video_subtitle_cue_id";
        table = "video_subtitle_publication_entries";
      }
      default -> throw new IllegalStateException("Unsupported synopsis evidence kind");
    }
    store.execute(
        "INSERT INTO synopsis_input_evidence(task_id,ordinal,publication_id,physical_segment_id,kind,content_sha256,start_us,end_us,"
            + column
            + ") VALUES(?,?,?,?,?,?,?,?,(SELECT "
            + column
            + " FROM "
            + table
            + " WHERE publication_id=? AND physical_segment_id=?))",
        taskId,
        ordinal,
        publicationId,
        evidence.id(),
        evidence.kind().name(),
        evidence.sha256(),
        evidence.time() == null ? null : evidence.time().startUs(),
        evidence.time() == null ? null : evidence.time().endUs(),
        publicationId,
        evidence.id());
  }

  public boolean complete(String id, String tokenHash, FileSynopsis synopsis, String now) {
    var found = findTask(id);
    if (found.isEmpty()) {
      return false;
    }
    var current = found.get();
    if (!current.state().equals("processing")
        || !Objects.equals(current.claimHash(), tokenHash)
        || !current.publication().equals(synopsis.publication())
        || !current.inputFingerprint().equals(synopsis.inputFingerprint())
        || !current.modelRevision().equals(synopsis.modelRevision())
        || !current.policyRevision().equals(synopsis.policyRevision())) {
      return false;
    }
    if (synopsis.unavailableReason() != null) {
      return terminate(id, "unavailable", synopsis.unavailableReason(), now);
    }
    Map<String, FileSynopsis.Reference> sources = inputReferences(id);
    for (var entry : synopsis.entries()) {
      for (var reference : entry.evidence()) {
        if (!reference.equals(sources.get(reference.id()))) {
          return false;
        }
      }
    }
    boolean timed = sources.values().stream().anyMatch(reference -> reference.time() != null);
    boolean timeline =
        synopsis.entries().stream()
            .anyMatch(entry -> entry.item().section() == SynopsisDraft.Section.TIMELINE);
    if (timed != timeline) {
      return false;
    }
    for (int ordinal = 0; ordinal < synopsis.entries().size(); ordinal++) {
      var entry = synopsis.entries().get(ordinal);
      store.execute(
          "INSERT INTO synopsis_entries(task_id,ordinal,section,text,start_us,end_us,reference_count) VALUES(?,?,?,?,?,?,?)",
          id,
          ordinal,
          entry.item().section().name(),
          entry.item().text(),
          entry.interval() == null ? null : entry.interval().startUs(),
          entry.interval() == null ? null : entry.interval().endUs(),
          entry.evidence().size());
      for (int sourceOrdinal = 0; sourceOrdinal < entry.evidence().size(); sourceOrdinal++) {
        store.execute(
            "INSERT INTO synopsis_references(task_id,entry_ordinal,source_ordinal,physical_segment_id) VALUES(?,?,?,?)",
            id,
            ordinal,
            sourceOrdinal,
            entry.evidence().get(sourceOrdinal).id());
      }
    }
    store.execute(
        "UPDATE synopsis_tasks SET state='available',claim_token_sha256=NULL,entry_count=?,updated_at=? WHERE id=? AND state='processing'",
        synopsis.entries().size(),
        now,
        id);
    return true;
  }

  public boolean terminate(String id, String state, String error, String now) {
    if (!List.of("unavailable", "cancelled").contains(state)) {
      return false;
    }
    if (store.count(
            "SELECT COUNT(*) FROM synopsis_tasks WHERE id=? AND state IN ('queued','processing')",
            id)
        == 0) {
      return false;
    }
    store.execute(
        "UPDATE synopsis_tasks SET state=?,error_code=?,claim_token_sha256=NULL,updated_at=? WHERE id=? AND state IN ('queued','processing')",
        state,
        error,
        now,
        id);
    return true;
  }

  public int recoverProcessing(String workspace, String now) {
    int count =
        Math.toIntExact(
            store.count(
                "SELECT COUNT(*) FROM synopsis_tasks WHERE workspace_id=? AND state='processing'",
                workspace));
    store.execute(
        "UPDATE synopsis_tasks SET state='unavailable',error_code='worker_interrupted',claim_token_sha256=NULL,updated_at=? WHERE workspace_id=? AND state='processing'",
        now,
        workspace);
    return count;
  }

  public Optional<FileSynopsis> findSynopsis(String taskId) {
    var found = findTask(taskId);
    if (found.isEmpty() || !found.get().state().equals("available")) {
      return Optional.empty();
    }
    var current = found.get();
    var sources = inputReferences(taskId);
    var entries = new ArrayList<FileSynopsis.Entry>();
    for (var row :
        store.rows("SELECT * FROM synopsis_entries WHERE task_id=? ORDER BY ordinal", taskId)) {
      var references =
          store
              .rows(
                  "SELECT physical_segment_id FROM synopsis_references WHERE task_id=? AND entry_ordinal=? ORDER BY source_ordinal",
                  taskId,
                  integer(row, "ordinal"))
              .stream()
              .map(reference -> sources.get(text(reference, "physical_segment_id")))
              .toList();
      var item =
          new SynopsisDraft.Item(
              SynopsisDraft.Section.valueOf(text(row, "section")),
              text(row, "text"),
              references.stream().map(FileSynopsis.Reference::id).toList());
      entries.add(new FileSynopsis.Entry(item, references, interval(row)));
    }
    return Optional.of(
        new FileSynopsis(
            current.publication(),
            current.inputFingerprint(),
            current.modelRevision(),
            current.policyRevision(),
            entries,
            null));
  }

  private Map<String, FileSynopsis.Reference> inputReferences(String taskId) {
    var result = new LinkedHashMap<String, FileSynopsis.Reference>();
    for (var row :
        store.rows(
            "SELECT physical_segment_id,content_sha256,kind,start_us,end_us FROM synopsis_input_evidence WHERE task_id=? ORDER BY ordinal",
            taskId)) {
      var reference =
          new FileSynopsis.Reference(
              text(row, "physical_segment_id"),
              text(row, "content_sha256"),
              SynopsisEvidence.Kind.valueOf(text(row, "kind")),
              interval(row));
      result.put(reference.id(), reference);
    }
    return result;
  }

  private static SynopsisEvidence.TimeRange interval(Map<String, Object> row) {
    if (row.get("start_us") == null) {
      return null;
    }
    return new SynopsisEvidence.TimeRange(
        ((Number) row.get("start_us")).longValue(), ((Number) row.get("end_us")).longValue());
  }

  private static SynopsisTaskEntity task(Map<String, Object> row) {
    var publication =
        new PublicationVersion(
            text(row, "document_id"),
            text(row, "id"),
            text(row, "revision_id"),
            text(row, "projection_generation_id"),
            text(row, "source_sha256"),
            text(row, "parser_revision"),
            new IndexTarget(
                text(row, "embedding_identity"),
                text(row, "projection_identity"),
                text(row, "model_revision"),
                integer(row, "dimensions")),
            text(row, "manifest_sha256"),
            integer(row, "segment_count"));
    return new SynopsisTaskEntity(
        text(row, "task_id"),
        new Actor(text(row, "workspace_id"), text(row, "created_by")),
        publication,
        text(row, "synopsis_model_revision"),
        text(row, "policy_revision"),
        text(row, "state"),
        text(row, "claim_token_sha256"),
        text(row, "input_fingerprint"),
        text(row, "error_code"),
        text(row, "task_created_at"),
        text(row, "task_updated_at"));
  }

  private static String text(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static int integer(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).intValue();
  }
}
