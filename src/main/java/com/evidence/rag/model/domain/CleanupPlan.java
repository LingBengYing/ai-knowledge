package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/** Immutable complete body inventory; no original text or bytes are retained. */
public record CleanupPlan(
    String cleanupId,
    String documentId,
    String workspaceId,
    String sourceSha256,
    String manifestSha256,
    List<CleanupPayload> payloadRows) {
  public CleanupPlan {
    ModelValues.identifier(cleanupId, 100);
    ModelValues.identifier(documentId, 100);
    ModelValues.identifier(workspaceId, 200);
    if (sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}") || payloadRows == null) {
      throw ModelValues.invalid();
    }
    payloadRows = ordered(payloadRows);
    if (!fingerprint(cleanupId, documentId, workspaceId, sourceSha256, payloadRows)
        .equals(manifestSha256)) {
      throw ModelValues.invalid();
    }
  }

  public static List<CleanupPayload> ordered(List<CleanupPayload> rows) {
    return rows.stream()
        .sorted(
            Comparator.comparing(CleanupPayload::tableName)
                .thenComparing(CleanupPayload::rowKey)
                .thenComparing(CleanupPayload::bodyColumn))
        .toList();
  }

  public static String fingerprint(
      String cleanup, String document, String workspace, String source, List<CleanupPayload> rows) {
    var value = new StringBuilder("java-document-cleanup-plan-v1\n");
    for (String field : List.of(cleanup, document, workspace, source)) {
      append(value, field);
    }
    for (var row : ordered(rows)) {
      for (String field :
          List.of(
              row.tableName(),
              row.rowKey(),
              row.bodyColumn(),
              Long.toString(row.sizeBytes()),
              row.sha256())) {
        append(value, field);
      }
    }
    return ModelValues.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder value, String field) {
    value
        .append(field.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(field)
        .append('\n');
  }

  @Override
  public String toString() {
    return "CleanupPlan[redacted]";
  }
}
