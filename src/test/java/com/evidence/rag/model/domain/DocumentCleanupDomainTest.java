package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentCleanupDomainTest {
  private static final String NOW = "2026-10-03T10:00:00Z";

  @Test
  void orderedBatchRetainsAcceptedBusyAndHiddenItemsWithoutInferringCompletion() {
    var pending = state("pending", null, null, "pending");
    var batch =
        new CleanupBatch(
            List.of(
                new CleanupBatchItem("document", "accepted", pending, null),
                new CleanupBatchItem("busy-doc", "busy", null, "document_busy"),
                new CleanupBatchItem("hidden", "not_found", null, "not_found")),
            3);
    assertEquals(
        List.of("document", "busy-doc", "hidden"),
        batch.items().stream().map(CleanupBatchItem::documentId).toList());
    assertEquals("deleting", batch.items().getFirst().cleanup().status());
    assertThrows(ApplicationException.class, () -> new CleanupBatch(batch.items(), 2));
    assertThrows(
        ApplicationException.class, () -> new CleanupBatchItem("wrong", "accepted", pending, null));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatchItem("document", "busy", null, "internal_secret"));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatch(List.of(batch.items().getFirst(), batch.items().getFirst()), 2));
  }

  @Test
  void completionRequiresEveryFixedResourceAndSafeFailureRemainsDeleting() {
    assertEquals("deleted", state("completed", NOW, null, "completed").status());
    assertEquals(
        "deleting", state("blocked", null, "cleanup_inventory_unknown", "blocked").status());
    assertThrows(ApplicationException.class, () -> state("completed", NOW, null, "pending"));
    assertThrows(ApplicationException.class, () -> state("blocked", null, null, "blocked"));
    assertThrows(
        ApplicationException.class, () -> state("completed", NOW, "cleanup_failed", "completed"));
    var duplicate = new ArrayList<>(resources("completed"));
    duplicate.set(0, duplicate.get(1));
    assertThrows(
        ApplicationException.class,
        () ->
            new DocumentCleanupState(
                "document", "cleanup", "deleted", "completed", NOW, NOW, NOW, null, duplicate));
    assertThrows(
        ApplicationException.class, () -> new CleanupResource("unknown_path", "completed"));
    assertThrows(ApplicationException.class, () -> new CleanupResource("database_file", "unknown"));
  }

  @Test
  void planDigestBindsOriginalBodyBytesAndEveryRowIdentityIndependentlyOfCallerOrdering() {
    var one = new CleanupPayload("corpus_pages", "726576:31", "text", 12, "a".repeat(64));
    var two = new CleanupPayload("corpus_documents", "646F63", "original_blob", 33, "b".repeat(64));
    var input = new ArrayList<>(List.of(one, two));
    String digest =
        CleanupPlan.fingerprint("cleanup", "document", "workspace", "c".repeat(64), input);
    var plan = new CleanupPlan("cleanup", "document", "workspace", "c".repeat(64), digest, input);
    input.clear();
    assertEquals(2, plan.payloadRows().size());
    assertEquals(
        digest,
        CleanupPlan.fingerprint(
            "cleanup", "document", "workspace", "c".repeat(64), List.of(two, one)));
    assertNotEquals(
        digest,
        CleanupPlan.fingerprint("cleanup", "document", "other", "c".repeat(64), List.of(two, one)));
    assertNotEquals(
        digest,
        CleanupPlan.fingerprint(
            "cleanup", "document", "workspace", "d".repeat(64), List.of(two, one)));
    var differentRow =
        new CleanupPayload(
            one.tableName(), "726576:32", one.bodyColumn(), one.sizeBytes(), one.sha256());
    assertNotEquals(
        digest,
        CleanupPlan.fingerprint(
            "cleanup", "document", "workspace", "c".repeat(64), List.of(two, differentRow)));
    assertThrows(
        ApplicationException.class,
        () ->
            new CleanupPlan(
                "cleanup",
                "different-document",
                "workspace",
                "c".repeat(64),
                digest,
                plan.payloadRows()));
    assertFalse(plan.toString().contains(one.rowKey()));
  }

  @Test
  void qualifiedProjectionIdentityRejectsSecretsAndSharedCollectionsAndIsRedacted() {
    var target =
        new QualifiedProjectionTarget(
            "https://vector.example.invalid",
            "default",
            "java_owned",
            "workspace",
            "embedding-v1",
            2,
            "a".repeat(64));
    assertFalse(target.toString().contains("vector.example"));
    assertThrows(
        ApplicationException.class,
        () ->
            new QualifiedProjectionTarget(
                "https://secret@vector.example.invalid",
                "default",
                "java_owned",
                "workspace",
                "embedding-v1",
                2,
                "a".repeat(64)));
    assertThrows(
        ApplicationException.class,
        () ->
            new QualifiedProjectionTarget(
                "https://vector.example.invalid/api",
                "default",
                "java_owned",
                "workspace",
                "embedding-v1",
                2,
                "a".repeat(64)));
    assertThrows(
        ApplicationException.class,
        () ->
            new QualifiedProjectionTarget(
                "https://vector.example.invalid",
                "default",
                "shared_collection",
                "workspace",
                "embedding-v1",
                2,
                "a".repeat(64)));
    assertThrows(
        ApplicationException.class,
        () ->
            new ProjectionAttempt(
                "document",
                "different-workspace",
                "revision",
                "a".repeat(64),
                "generation",
                "text",
                target,
                false));
    assertThrows(
        ApplicationException.class,
        () -> new ManagedBackupFile("../foreign.db", "a".repeat(64), 12));
  }

  private static List<CleanupResource> resources(String status) {
    return CleanupResource.KINDS.stream().map(kind -> new CleanupResource(kind, status)).toList();
  }

  private static DocumentCleanupState state(
      String state, String completed, String error, String resource) {
    return new DocumentCleanupState(
        "document",
        "cleanup",
        state.equals("completed") ? "deleted" : "deleting",
        state,
        NOW,
        NOW,
        completed,
        error,
        resources(resource));
  }
}
