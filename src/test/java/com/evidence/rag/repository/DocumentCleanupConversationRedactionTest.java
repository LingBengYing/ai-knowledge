package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.AgentProtocol;
import com.evidence.rag.model.dto.AgentRunResult;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeCitation;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.ConversationService;
import com.evidence.rag.service.DocumentCleanupService;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Cleanup is only "completed" when archived conversations no longer carry the document. */
class DocumentCleanupConversationRedactionTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final String PURGED_TEXT = "synthetic secret sentence";
  private final JsonMapper json = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void cleanupRedactsCitingTurnsAndResetsDerivedSummaryInsidePurge() {
    try (var authority = new AuthorityTestContext(directory)) {
      var store = authority.store();
      String documentId = parsed(authority, "secret.txt", PURGED_TEXT);
      String otherDocument = parsed(authority, "other.txt", "unrelated synthetic body");
      var conversations = new ConversationRepository(store);
      String conversation = UUID.randomUUID().toString();
      String unrelatedConversation = UUID.randomUUID().toString();
      String citing = UUID.randomUUID().toString();
      String later = UUID.randomUUID().toString();
      String unrelated = UUID.randomUUID().toString();
      String laterJson = json.writeValueAsString(run(later, otherDocument, "other.txt"));
      String unrelatedJson = json.writeValueAsString(run(unrelated, otherDocument, "other.txt"));
      long otherGeneration =
          store.transaction(
              () -> {
                conversations.insert(OWNER, conversation, "t", 1);
                conversations.begin(
                    OWNER, conversation, UUID.randomUUID().toString(), citing, "q", 1);
                conversations.finish(
                    OWNER,
                    conversation,
                    citing,
                    "completed",
                    json.writeValueAsString(run(citing, documentId, "secret.txt")),
                    2);
                conversations.begin(
                    OWNER, conversation, UUID.randomUUID().toString(), later, "q2", 3);
                long generation =
                    conversations.find(OWNER, conversation).orElseThrow().generation();
                assertTrue(
                    conversations.checkpoint(
                        OWNER, conversation, later, generation, 1, "summary of " + PURGED_TEXT, 4));
                conversations.finish(OWNER, conversation, later, "completed", laterJson, 5);
                conversations.insert(OWNER, unrelatedConversation, "u", 1);
                conversations.begin(
                    OWNER, unrelatedConversation, UUID.randomUUID().toString(), unrelated, "q3", 1);
                conversations.finish(
                    OWNER, unrelatedConversation, unrelated, "completed", unrelatedJson, 2);
                return conversations.find(OWNER, unrelatedConversation).orElseThrow().generation();
              });

      var service = service(store);
      service.request(OWNER, documentId);
      assertTrue(service.runOnce());
      assertEquals("completed", service.status(OWNER, documentId).cleanupStatus());

      store.transaction(
          () -> {
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM knowledge_conversation_turns WHERE result_json LIKE ? OR result_json LIKE ?",
                    "%" + PURGED_TEXT + "%",
                    "%secret.txt%"));
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM knowledge_conversations WHERE summary LIKE ?",
                    "%" + PURGED_TEXT + "%"));
            var state = conversations.find(OWNER, conversation).orElseThrow();
            assertEquals("", state.summary());
            assertEquals(0, state.coveredTurnCount());
            assertEquals("idle", state.compressionStatus());
            assertNull(state.activeRunId());
            var turns = conversations.turns(OWNER, conversation);
            assertEquals("q", turns.get(0).question());
            assertEquals("completed", turns.get(0).status());
            assertEquals(laterJson, turns.get(1).resultJson());
            assertEquals(
                unrelatedJson,
                conversations.turns(OWNER, unrelatedConversation).getFirst().resultJson());
            assertEquals(
                otherGeneration,
                conversations.find(OWNER, unrelatedConversation).orElseThrow().generation());
            return null;
          });

      // The redacted turn keeps the stored shape the existing read path and web contract accept:
      // a completed run whose formal result is an abstention without citations.
      var detail =
          new ConversationService(store, conversations, OWNER.workspaceId(), 24000, 4)
              .get(OWNER, conversation);
      var redacted = detail.turns().getFirst().result();
      assertEquals(citing, redacted.id());
      assertEquals("completed", redacted.status());
      assertNull(redacted.error());
      assertTrue(redacted.suggestions().isEmpty());
      assertTrue(redacted.events().stream().allMatch(event -> event.details() == null));
      assertEquals("reading", redacted.events().getFirst().type());
      var answer = redacted.result();
      assertEquals("answer-1", answer.answerId());
      assertEquals("abstained", answer.status());
      assertEquals("source_removed", answer.reason());
      assertEquals(ConversationRepository.REDACTED_ANSWER, answer.answer());
      assertTrue(answer.citations().isEmpty());
      assertEquals(
          "other.txt", detail.turns().get(1).result().result().citations().getFirst().filename());

      assertFalse(service.runOnce());
    }
  }

  private static AgentRunResult run(String id, String documentId, String filename) {
    String revision = UUID.randomUUID().toString();
    String quote = filename.equals("secret.txt") ? PURGED_TEXT : "unrelated synthetic body";
    var source =
        new AgentRunResult.Source(
            "source-1", documentId, revision, filename, "document_text", 1, null, null);
    var details =
        new AgentRunResult.Details(
            "completed",
            "knowledge_read",
            null,
            3L,
            1,
            1,
            List.of(source),
            null,
            "reasoning about " + quote,
            false,
            List.of());
    var citation =
        new KnowledgeCitation(
            1,
            "document_text",
            documentId,
            revision,
            filename,
            "a".repeat(64),
            "parser-v1",
            "text/plain",
            quote,
            "b".repeat(64),
            1,
            0,
            10,
            null,
            null,
            null,
            "source_text",
            "/v1/documents/" + documentId + "/revisions/" + revision + "/content",
            "/v1/knowledge-sources/answer-1/1");
    return new AgentRunResult(
        id,
        "completed",
        List.of(new AgentRunResult.Event(1, "reading", "正在阅读原文", details)),
        new KnowledgeAnswerResult(
            "answer-1", "answered", "answer from " + filename + " [1]", null, List.of(citation)),
        List.of(new AgentProtocol.Suggestion("t", "maintain " + filename, List.of(documentId))),
        null);
  }

  private static String parsed(AuthorityTestContext authority, String filename, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    var task = authority.ingestion().uploadDocument(OWNER, filename, "text/plain", bytes);
    var claim = authority.ingestion().claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(claim, new TextParser().parse(filename, "text/plain", bytes)));
    return task.documentId();
  }

  private static DocumentCleanupService service(SqliteAuthorityStore store) {
    return new DocumentCleanupService(
        store,
        new DocumentCleanupRepository(store),
        new DocumentLifecycleRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        attempts -> {
          throw new AssertionError("No projection writes for a parsed-only document");
        });
  }
}
