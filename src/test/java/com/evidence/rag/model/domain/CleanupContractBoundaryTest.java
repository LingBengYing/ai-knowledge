package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.ProjectionCleanup;
import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Public cleanup truth and sealed hash-only inventory boundaries; no storage or provider fixtures.
 */
class CleanupContractBoundaryTest {
  private static final String NOW = "2026-10-03T10:00:00Z";
  private static final String HASH = "a".repeat(64);

  @Test
  void unrequestedWithdrawalAndEveryRequestedPhaseRetainDistinctCompletionTruth() {
    var legacy = receipt("deleting", "not_requested", null, null, null, List.of());
    assertEquals("not_requested", legacy.cleanupStatus());
    assertTrue(legacy.resources().isEmpty());
    for (String phase : List.of("pending", "running", "blocked", "failed")) {
      String error = List.of("blocked", "failed").contains(phase) ? "cleanup_unverified" : null;
      var state = receipt("deleting", phase, "cleanup", null, error, resources(phase));
      assertEquals("deleting", state.status());
      assertEquals(phase, state.cleanupStatus());
      assertEquals(9, state.resources().size());
    }
    var mixed = new ArrayList<>(resources("completed"));
    mixed.set(3, new CleanupResource("managed_temporaries", "not_applicable"));
    var completed = receipt("deleted", "completed", "cleanup", NOW, null, mixed);
    mixed.clear();
    assertEquals(9, completed.resources().size());
    assertEquals("not_applicable", completed.resources().get(3).status());
    assertThrows(UnsupportedOperationException.class, () -> completed.resources().clear());
    assertEquals("DocumentCleanupState[redacted]", completed.toString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inconsistentReceipts")
  void malformedReceiptsCannotClaimAcceptanceOrCompletion(String meaning, Executable creation) {
    assertThrows(ApplicationException.class, creation, meaning);
  }

  private static Stream<Arguments> inconsistentReceipts() {
    return Stream.of(
        invalid(
            "missing document status",
            () -> receipt(null, "pending", "cleanup", null, null, resources("pending"))),
        invalid(
            "missing cleanup phase",
            () -> receipt("deleting", null, "cleanup", null, null, resources("pending"))),
        invalid(
            "ordinary ready is not a cleanup status",
            () -> receipt("ready", "pending", "cleanup", null, null, resources("pending"))),
        invalid(
            "unknown remote state is not public completion",
            () -> receipt("deleting", "unknown", "cleanup", null, null, resources("pending"))),
        invalid(
            "requested cleanup must include resource inventory",
            () -> receipt("deleting", "pending", "cleanup", null, null, null)),
        invalid(
            "resource inventory cannot contain an unreadable slot",
            () ->
                receipt(
                    "deleting",
                    "pending",
                    "cleanup",
                    null,
                    null,
                    Arrays.asList((CleanupResource) null))),
        invalid(
            "pending requires a stable task identity",
            () -> receipt("deleting", "pending", null, null, null, resources("pending"))),
        invalid(
            "unadopted withdrawal has no cleanup task",
            () -> receipt("deleting", "not_requested", "cleanup", null, null, List.of())),
        invalid(
            "unadopted withdrawal cannot claim resource work",
            () -> receipt("deleting", "not_requested", null, null, null, resources("pending"))),
        invalid(
            "unadopted withdrawal cannot contain a cleanup failure",
            () -> receipt("deleting", "not_requested", null, null, "cleanup_failed", List.of())),
        invalid(
            "pending cannot expose deleted",
            () -> receipt("deleted", "pending", "cleanup", null, null, resources("pending"))),
        invalid(
            "completed cannot retain deleting",
            () -> receipt("deleting", "completed", "cleanup", NOW, null, resources("completed"))),
        invalid(
            "pending cannot expose a completion timestamp",
            () -> receipt("deleting", "pending", "cleanup", NOW, null, resources("pending"))),
        invalid(
            "completed requires a completion timestamp",
            () -> receipt("deleted", "completed", "cleanup", null, null, resources("completed"))),
        invalid(
            "unsafe internal failure text is not an error code",
            () ->
                receipt(
                    "deleting",
                    "failed",
                    "cleanup",
                    null,
                    "remote /private/path",
                    resources("failed"))),
        invalid(
            "partial resource inventories never qualify",
            () ->
                receipt(
                    "deleting",
                    "pending",
                    "cleanup",
                    null,
                    null,
                    resources("pending").subList(0, 8))));
  }

  @Test
  void safeWaitingReasonsAndNullResourceFieldsDoNotInventAdditionalPublicStates() {
    assertEquals(
        "cleanup_waiting",
        receipt("deleting", "running", "cleanup", null, "cleanup_waiting", resources("running"))
            .errorCode());
    assertThrows(ApplicationException.class, () -> new CleanupResource(null, "blocked"));
    assertThrows(
        ApplicationException.class, () -> new CleanupResource("remote_write_terminal", null));
    assertThrows(
        UnsupportedOperationException.class, () -> CleanupResource.KINDS.add("foreign_snapshot"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidPayloads")
  void inventoryRequiresUnambiguousRowIdentityAndMeasuredOriginalBytes(
      String meaning, Executable creation) {
    assertThrows(ApplicationException.class, creation, meaning);
  }

  private static Stream<Arguments> invalidPayloads() {
    return Stream.of(
        invalid("missing table identity", () -> new CleanupPayload(null, "row", "text", 1, HASH)),
        invalid(
            "table identity cannot be executable SQL",
            () -> new CleanupPayload("corpus_pages;DELETE", "row", "text", 1, HASH)),
        invalid(
            "missing body identity",
            () -> new CleanupPayload("corpus_pages", "row", null, 1, HASH)),
        invalid(
            "body identity cannot be executable SQL",
            () -> new CleanupPayload("corpus_pages", "row", "text=secret", 1, HASH)),
        invalid("missing row key", () -> new CleanupPayload("corpus_pages", null, "text", 1, HASH)),
        invalid("empty row key", () -> new CleanupPayload("corpus_pages", "", "text", 1, HASH)),
        invalid(
            "unbounded inventory key",
            () -> new CleanupPayload("corpus_pages", "a".repeat(1025), "text", 1, HASH)),
        invalid(
            "negative measured byte count",
            () -> new CleanupPayload("corpus_pages", "row", "text", -1, HASH)),
        invalid(
            "missing body hash", () -> new CleanupPayload("corpus_pages", "row", "text", 1, null)),
        invalid(
            "noncanonical body hash",
            () -> new CleanupPayload("corpus_pages", "row", "text", 1, "A".repeat(64))));
  }

  @Test
  void sealedPlanAllowsGenuineEmptyBodiesButBindsByteCountAndRetainsNoMutableInventory() {
    var empty = new CleanupPayload("audio_spans", "726576:30", "text", 0, HASH);
    var original = new CleanupPayload("corpus_documents", "646f63", "original_blob", 12, HASH);
    var rows = new ArrayList<>(List.of(original, empty));
    String digest = CleanupPlan.fingerprint("cleanup", "document", "workspace", HASH, rows);
    var plan = new CleanupPlan("cleanup", "document", "workspace", HASH, digest, rows);
    rows.clear();
    assertEquals(0, plan.payloadRows().getFirst().sizeBytes());
    assertEquals(2, plan.payloadRows().size());
    assertThrows(UnsupportedOperationException.class, () -> plan.payloadRows().clear());
    assertFalse(plan.toString().contains("646f63"));
    assertEquals("CleanupPayload[redacted]", original.toString());
    var wrongSize =
        new CleanupPayload(
            original.tableName(), original.rowKey(), original.bodyColumn(), 13, original.sha256());
    assertNotEquals(
        digest,
        CleanupPlan.fingerprint(
            "cleanup", "document", "workspace", HASH, List.of(empty, wrongSize)));
    assertThrows(
        ApplicationException.class,
        () ->
            new CleanupPlan(
                "cleanup", "document", "workspace", HASH, digest, List.of(empty, wrongSize)));
    assertThrows(
        ApplicationException.class,
        () ->
            new CleanupPlan("cleanup", "document", "workspace", null, digest, plan.payloadRows()));
    assertThrows(
        ApplicationException.class,
        () ->
            new CleanupPlan(
                "cleanup", "document", "workspace", "not-a-hash", digest, plan.payloadRows()));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupPlan("cleanup", "document", "workspace", HASH, digest, null));
  }

  @Test
  void pagingRetainsAuthorizedSnapshotAndRejectsInvalidCursorsOrExcessRows() {
    var state = receipt("deleting", "pending", "cleanup", null, null, resources("pending"));
    var rows = new ArrayList<>(List.of(state));
    var page = new CleanupPage(rows, 1, 1, 1);
    rows.clear();
    assertEquals(List.of(state), page.items());
    assertEquals(1, page.total());
    assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    assertEquals("CleanupPage[redacted]", page.toString());
    assertTrue(new CleanupPage(List.of(), 0, 2, 100).items().isEmpty());
    assertThrows(ApplicationException.class, () -> new CleanupPage(null, 0, 1, 20));
    assertThrows(ApplicationException.class, () -> new CleanupPage(List.of(), -1, 1, 20));
    assertThrows(ApplicationException.class, () -> new CleanupPage(List.of(), 0, 0, 20));
    assertThrows(ApplicationException.class, () -> new CleanupPage(List.of(), 0, 1, 0));
    assertThrows(ApplicationException.class, () -> new CleanupPage(List.of(), 0, 1, 101));
    assertThrows(ApplicationException.class, () -> new CleanupPage(List.of(state, state), 2, 1, 1));
  }

  @Test
  void batchDispositionCannotAttachAnotherTaskOrHideFailureAsAccepted() {
    var pending = receipt("deleting", "pending", "cleanup", null, null, resources("pending"));
    assertThrows(
        ApplicationException.class, () -> new CleanupBatchItem("document", null, null, null));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatchItem("document", "completed", pending, null));
    assertThrows(
        ApplicationException.class, () -> new CleanupBatchItem("document", "accepted", null, null));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatchItem("document", "accepted", pending, "cleanup_failed"));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatchItem("document", "busy", pending, "document_busy"));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatchItem("document", "not_found", pending, "not_found"));
    assertThrows(
        ApplicationException.class,
        () -> new CleanupBatchItem("document", "not_found", null, "document_busy"));
    assertEquals(
        "CleanupBatchItem[redacted]",
        new CleanupBatchItem("document", "accepted", pending, null).toString());
  }

  @Test
  void fullHundredItemBatchIsCopiedAndCannotSilentlyBecomeEmptyOrOverLimit() {
    var items =
        new ArrayList<>(
            IntStream.range(0, 100)
                .mapToObj(i -> new CleanupBatchItem("doc-" + i, "busy", null, "document_busy"))
                .toList());
    var batch = new CleanupBatch(items, 100);
    items.clear();
    assertEquals(100, batch.items().size());
    assertEquals("doc-0", batch.items().getFirst().documentId());
    assertEquals("doc-99", batch.items().getLast().documentId());
    assertThrows(UnsupportedOperationException.class, () -> batch.items().clear());
    assertEquals("CleanupBatch[redacted]", batch.toString());
    assertThrows(ApplicationException.class, () -> new CleanupBatch(null, 1));
    assertThrows(ApplicationException.class, () -> new CleanupBatch(List.of(), 0));
    var oversized = new ArrayList<>(batch.items());
    oversized.add(new CleanupBatchItem("doc-tail", "not_found", null, "not_found"));
    assertThrows(ApplicationException.class, () -> new CleanupBatch(oversized, 101));
  }

  @Test
  void remoteLogicalAbsenceDoesNotSubstituteForWriteTerminationOrPhysicalProof() {
    var logicalOnly =
        new ProjectionCleanup.Result(
            "completed", "blocked", "blocked", "cleanup_remote_unverified");
    assertEquals("completed", logicalOnly.logicalRows());
    assertEquals("blocked", logicalOnly.writeTerminal());
    assertEquals("blocked", logicalOnly.physicalStorage());
    var localOnly =
        new ProjectionCleanup.Result("not_applicable", "not_applicable", "not_applicable", null);
    assertEquals("not_applicable", localOnly.physicalStorage());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionCleanup.Result("unknown", "blocked", "blocked", "cleanup_unverified"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectionCleanup.Result("completed", "pending", "blocked", "cleanup_unverified"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectionCleanup.Result(
                "completed", "completed", "assumed_deleted", "cleanup_unverified"));
    assertEquals(
        "CleanupClaim[redacted]",
        new CleanupClaim("cleanup", "document", "workspace", "opaque-synthetic-claim").toString());
  }

  private static Arguments invalid(String meaning, Executable creation) {
    return Arguments.of(meaning, creation);
  }

  private static List<CleanupResource> resources(String status) {
    return CleanupResource.KINDS.stream().map(kind -> new CleanupResource(kind, status)).toList();
  }

  private static DocumentCleanupState receipt(
      String status,
      String phase,
      String cleanup,
      String completed,
      String error,
      List<CleanupResource> resources) {
    return new DocumentCleanupState(
        "document", cleanup, status, phase, NOW, NOW, completed, error, resources);
  }
}
