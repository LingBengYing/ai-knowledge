package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Request preparation identity only, separated from authorized answer evidence. */
public record QueryTrace(
    String questionSha256,
    String preparationRevision,
    String rankingRevision,
    String status,
    String reasonCode,
    String manifestSha256,
    List<QueryTraceAttachment> attachments) {
  public QueryTrace {
    ModelValues.identifier(preparationRevision, 200);
    if (rankingRevision != null) {
      ModelValues.identifier(rankingRevision, 200);
    }
    if (questionSha256 == null
        || !questionSha256.matches("[a-f0-9]{64}")
        || (!"prepared".equals(status) && !"failed".equals(status))
        || attachments == null
        || attachments.isEmpty()
        || attachments.size() > 3) {
      throw ModelValues.invalid();
    }
    boolean prepared = "prepared".equals(status);
    if (prepared
        ? reasonCode != null || manifestSha256 == null || !manifestSha256.matches("[a-f0-9]{64}")
        : manifestSha256 != null
            || reasonCode == null
            || !reasonCode.matches("[a-z][a-z0-9_]{0,63}")) {
      throw ModelValues.invalid();
    }
    var selectedImages = new HashSet<String>();
    for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
      var attachment = attachments.get(ordinal);
      if (attachment == null
          || attachment.ordinal() != ordinal
          || prepared != (attachment.manifest() != null)) {
        throw ModelValues.invalid();
      }
      if (prepared) {
        selectedImages.addAll(attachment.manifest().selectedImageSha256());
      }
    }
    if (selectedImages.size() > 3) {
      throw ModelValues.invalid();
    }
    attachments = List.copyOf(attachments);
  }

  public static QueryTrace prepared(PreparedQuery query, String rankingRevision) {
    if (query == null) {
      throw ModelValues.invalid();
    }
    return new QueryTrace(
        ModelValues.sha256(query.originalQuestion().getBytes(StandardCharsets.UTF_8)),
        query.preparationRevision(),
        rankingRevision,
        "prepared",
        null,
        query.manifestSha256(),
        query.attachments().stream()
            .map(
                item ->
                    new QueryTraceAttachment(
                        item.ordinal(), item.sourceSha256(), item.mediaKind(), item))
            .toList());
  }

  public static QueryTrace failed(
      String questionSha256,
      List<QueryAttachment> attachments,
      String preparationRevision,
      String rankingRevision,
      String reasonCode) {
    if (attachments == null || attachments.size() > 3) {
      throw ModelValues.invalid();
    }
    var sources = new ArrayList<QueryTraceAttachment>();
    for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
      var attachment = attachments.get(ordinal);
      if (attachment == null) {
        throw ModelValues.invalid();
      }
      sources.add(new QueryTraceAttachment(ordinal, attachment.sha256(), attachment.kind(), null));
    }
    return new QueryTrace(
        questionSha256, preparationRevision, rankingRevision, "failed", reasonCode, null, sources);
  }

  @Override
  public String toString() {
    return "QueryTrace[redacted]";
  }
}
