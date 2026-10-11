package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ConversationState;
import com.evidence.rag.model.domain.ModelValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Actor-owned dialogue persistence. Every operation is inside the caller's store transaction. */
public final class ConversationRepository {
  /** Fixed notice that replaces an archived answer once a document it relied on is cleaned up. */
  public static final String REDACTED_ANSWER = "引用的资料已清理。";

  private final SqliteAuthorityStore store;
  private final JsonMapper json = JsonMapper.builder().build();

  public ConversationRepository(SqliteAuthorityStore store) {
    this.store = store;
  }

  public void insert(Actor actor, String id, String title, long now) {
    store.execute(
        "INSERT INTO knowledge_conversations(id,workspace_id,actor_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
        id,
        actor.workspaceId(),
        actor.principalId(),
        title,
        now,
        now);
  }

  public List<ConversationState> list(Actor actor, int offset, int limit) {
    return store
        .rows(
            "SELECT * FROM knowledge_conversations WHERE workspace_id=? AND actor_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
            actor.workspaceId(),
            actor.principalId(),
            limit,
            offset)
        .stream()
        .map(ConversationRepository::snapshot)
        .toList();
  }

  public Optional<ConversationState> find(Actor actor, String id) {
    return store
        .rows(
            "SELECT * FROM knowledge_conversations WHERE workspace_id=? AND actor_id=? AND id=?",
            actor.workspaceId(),
            actor.principalId(),
            id)
        .stream()
        .map(ConversationRepository::snapshot)
        .findFirst();
  }

  public void rename(Actor actor, String id, String title, long now) {
    store.execute(
        "UPDATE knowledge_conversations SET title=?,updated_at=? WHERE workspace_id=? AND actor_id=? AND id=?",
        title,
        now,
        actor.workspaceId(),
        actor.principalId(),
        id);
  }

  public void delete(Actor actor, String id) {
    find(actor, id).orElseThrow(ModelValues::notFound);
    store.execute("DELETE FROM knowledge_conversation_turns WHERE conversation_id=?", id);
    store.execute("DELETE FROM knowledge_conversations WHERE id=?", id);
  }

  public Optional<ConversationState.Turn> request(Actor actor, String id, String requestId) {
    find(actor, id).orElseThrow(ModelValues::notFound);
    return store
        .rows(
            "SELECT * FROM knowledge_conversation_turns WHERE conversation_id=? AND request_id=?",
            id,
            requestId)
        .stream()
        .map(this::turn)
        .findFirst();
  }

  public List<ConversationState.Turn> turns(Actor actor, String id) {
    find(actor, id).orElseThrow(ModelValues::notFound);
    return store
        .rows(
            "SELECT * FROM knowledge_conversation_turns WHERE conversation_id=? ORDER BY ordinal",
            id)
        .stream()
        .map(this::turn)
        .toList();
  }

  public List<ConversationState.Turn> turnPage(Actor actor, String id, int offset, int limit) {
    find(actor, id).orElseThrow(ModelValues::notFound);
    return store
        .rows(
            "SELECT * FROM knowledge_conversation_turns WHERE conversation_id=? ORDER BY ordinal LIMIT ? OFFSET ?",
            id,
            limit,
            offset)
        .stream()
        .map(this::turn)
        .toList();
  }

  public void begin(
      Actor actor, String id, String requestId, String runId, String question, long now) {
    var snapshot = find(actor, id).orElseThrow(ModelValues::notFound);
    long ordinal = snapshot.turnCount() + 1;
    store.execute(
        "INSERT INTO knowledge_conversation_turns(conversation_id,ordinal,run_id,request_id,question,status,created_at,updated_at) VALUES(?,?,?,?,?,'running',?,?)",
        id,
        ordinal,
        runId,
        requestId,
        question,
        now,
        now);
    store.execute(
        "UPDATE knowledge_conversations SET turn_count=?,active_run_id=?,generation=generation+1,updated_at=? WHERE id=?",
        ordinal,
        runId,
        now,
        id);
  }

  public boolean active(Actor actor, String id, String runId) {
    return find(actor, id).map(value -> runId.equals(value.activeRunId())).orElse(false);
  }

  public void finish(
      Actor actor, String id, String runId, String status, String resultJson, long now) {
    if (!active(actor, id, runId)) {
      return;
    }
    store.execute(
        "UPDATE knowledge_conversation_turns SET status=?,result_json=?,updated_at=? WHERE conversation_id=? AND run_id=? AND status='running'",
        status,
        resultJson,
        now,
        id,
        runId);
    store.execute(
        "UPDATE knowledge_conversations SET active_run_id=NULL,generation=generation+1,updated_at=?,compression_status=CASE WHEN compression_status='compressing' THEN 'failed' ELSE compression_status END WHERE id=?",
        now,
        id);
  }

  public boolean checkpoint(
      Actor actor,
      String id,
      String runId,
      long generation,
      long covered,
      String summary,
      long now) {
    if (!active(actor, id, runId)) {
      return false;
    }
    store.execute(
        "UPDATE knowledge_conversations SET summary=?,covered_turn_count=?,compression_status='compressed',updated_at=? WHERE id=? AND generation=? AND active_run_id=?",
        summary,
        covered,
        now,
        id,
        generation,
        runId);
    return store.count("SELECT changes()") == 1;
  }

  public void compression(Actor actor, String id, String runId, String status) {
    if (active(actor, id, runId)) {
      store.execute(
          "UPDATE knowledge_conversations SET compression_status=? WHERE id=?", status, id);
    }
  }

  /**
   * Document cleanup redaction, run inside the cleanup purge transaction. Every archived turn of
   * the workspace whose stored result references the document (formal citations, tool source
   * coordinates or maintenance suggestions) keeps its question and run status, but its formal
   * result becomes an abstention with the fixed notice and no citations, and tool details and
   * suggestions (which may carry quotes, filenames or model reasoning) are dropped. A summary
   * checkpoint that covers a redacted turn cannot be scrubbed precisely, so it is reset and later
   * regenerated from the redacted turns.
   */
  public void redactDocument(String workspaceId, String documentId, long now) {
    var earliest = new LinkedHashMap<String, Long>();
    for (var row :
        store.rows(
            "SELECT t.conversation_id,t.ordinal,t.result_json FROM knowledge_conversation_turns t JOIN knowledge_conversations c ON c.id=t.conversation_id WHERE c.workspace_id=? AND t.result_json IS NOT NULL AND instr(t.result_json,?)>0 ORDER BY t.conversation_id,t.ordinal",
            workspaceId,
            documentId)) {
      var result = json.readTree(AuthorityRows.text(row, "result_json"));
      if (!(result instanceof ObjectNode stored) || !references(stored, documentId)) {
        continue;
      }
      if (stored.get("result") instanceof ObjectNode answer) {
        answer.put("status", "abstained");
        answer.put("answer", REDACTED_ANSWER);
        answer.put("reason", "source_removed");
        answer.putArray("citations");
      }
      for (var event : stored.path("events")) {
        if (event instanceof ObjectNode value) {
          value.remove("details");
        }
      }
      stored.putArray("suggestions");
      String conversation = AuthorityRows.text(row, "conversation_id");
      long ordinal = AuthorityRows.number(row, "ordinal");
      store.execute(
          "UPDATE knowledge_conversation_turns SET result_json=?,updated_at=? WHERE conversation_id=? AND ordinal=?",
          json.writeValueAsString(stored),
          now,
          conversation,
          ordinal);
      earliest.merge(conversation, ordinal, Math::min);
    }
    earliest.forEach(
        (conversation, ordinal) ->
            store.execute(
                "UPDATE knowledge_conversations SET summary='',covered_turn_count=0,compression_status=CASE WHEN compression_status='compressing' THEN 'failed' ELSE 'idle' END,generation=generation+1 WHERE id=? AND covered_turn_count>=?",
                conversation,
                ordinal));
  }

  private static boolean references(JsonNode result, String documentId) {
    for (var citation : result.path("result").path("citations")) {
      if (documentId.equals(citation.path("document_id").asString(null))) return true;
    }
    for (var event : result.path("events")) {
      for (var source : event.path("details").path("sources")) {
        if (documentId.equals(source.path("document_id").asString(null))) return true;
      }
    }
    for (var suggestion : result.path("suggestions")) {
      for (var id : suggestion.path("document_ids")) {
        if (documentId.equals(id.asString(null))) return true;
      }
    }
    return false;
  }

  /** A restart interrupts pending work, never reruns models or silently replays old tools. */
  public void interruptOrphans(long now) {
    for (var row :
        store.rows(
            "SELECT conversation_id,run_id FROM knowledge_conversation_turns WHERE status='running'")) {
      String runId = AuthorityRows.text(row, "run_id");
      var result =
          Map.of(
              "id",
              runId,
              "status",
              "failed",
              "events",
              List.of(),
              "suggestions",
              List.of(),
              "error",
              Map.of("code", "agent_interrupted", "message", "服务已重启，本轮未完成，请重新发起。"));
      store.execute(
          "UPDATE knowledge_conversation_turns SET status='failed',result_json=?,updated_at=? WHERE run_id=?",
          json.writeValueAsString(result),
          now,
          runId);
    }
    store.execute(
        "UPDATE knowledge_conversations SET active_run_id=NULL,generation=generation+1,updated_at=?,compression_status=CASE WHEN compression_status='compressing' THEN 'failed' ELSE compression_status END WHERE active_run_id IS NOT NULL",
        now);
  }

  private ConversationState.Turn turn(Map<String, Object> row) {
    String result = (String) row.get("result_json");
    return new ConversationState.Turn(
        AuthorityRows.number(row, "ordinal"),
        AuthorityRows.text(row, "run_id"),
        AuthorityRows.text(row, "question"),
        AuthorityRows.text(row, "status"),
        result,
        AuthorityRows.number(row, "created_at"),
        AuthorityRows.number(row, "updated_at"));
  }

  private static ConversationState snapshot(Map<String, Object> row) {
    String summary = AuthorityRows.text(row, "summary");
    return new ConversationState(
        AuthorityRows.text(row, "id"),
        AuthorityRows.text(row, "title"),
        AuthorityRows.number(row, "created_at"),
        AuthorityRows.number(row, "updated_at"),
        AuthorityRows.number(row, "turn_count"),
        (String) row.get("active_run_id"),
        AuthorityRows.number(row, "generation"),
        summary,
        AuthorityRows.number(row, "covered_turn_count"),
        AuthorityRows.text(row, "compression_status"));
  }
}
