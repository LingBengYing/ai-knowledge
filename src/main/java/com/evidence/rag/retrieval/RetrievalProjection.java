package com.evidence.rag.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Candidate-only projection seam. The caller supplies a complete, authority-validated scope. */
public interface RetrievalProjection {
  int MAX_SCOPE = 128;
  int MAX_BATCH = 64;
  int MAX_TEXT_BYTES = 16_384;
  int MAX_QUERY_BYTES = 4_096;
  int MAX_TOP_K = 100;

  void initialize();

  void upsert(List<Entry> entries);

  List<Candidate> search(Query query);

  record AuthorizedScope(String workspaceId, Map<String, String> documentRevisions) {
    public AuthorizedScope {
      requireId(workspaceId);
      if (documentRevisions == null || documentRevisions.size() > MAX_SCOPE) {
        throw invalid();
      }
      var copied = new TreeMap<String, String>();
      documentRevisions.forEach(
          (document, revision) -> {
            requireId(document);
            requireId(revision);
            copied.put(document, revision);
          });
      documentRevisions = Collections.unmodifiableMap(copied);
    }

    @Override
    public String toString() {
      return "AuthorizedScope[redacted]";
    }
  }

  record Entry(
      String segmentId,
      String workspaceId,
      String documentId,
      String revisionId,
      String text,
      List<Double> vector) {
    public Entry {
      requireId(segmentId);
      requireId(workspaceId);
      requireId(documentId);
      requireId(revisionId);
      requireText(text, MAX_TEXT_BYTES);
      vector = immutableVector(vector);
    }

    @Override
    public String toString() {
      return "Entry[redacted]";
    }
  }

  record Query(String text, List<Double> vector, AuthorizedScope scope, int limit) {
    public Query {
      requireText(text, MAX_QUERY_BYTES);
      vector = immutableVector(vector);
      if (scope == null || limit < 1 || limit > MAX_TOP_K) {
        throw invalid();
      }
    }

    @Override
    public String toString() {
      return "Query[redacted]";
    }
  }

  record Candidate(String segmentId, double score) {
    public Candidate {
      requireId(segmentId);
      if (!Double.isFinite(score) || score < 0) {
        throw invalid();
      }
    }
  }

  private static List<Double> immutableVector(List<Double> vector) {
    if (vector == null || vector.size() < 2 || vector.size() > 32_768) {
      throw invalid();
    }
    boolean nonzero = false;
    for (Double value : vector) {
      if (value == null || !Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
        throw invalid();
      }
      nonzero |= value.floatValue() != 0;
    }
    if (!nonzero) {
      throw invalid();
    }
    return List.copyOf(vector);
  }

  private static void requireText(String text, int maxBytes) {
    if (text == null
        || text.isBlank()
        || text.length() > maxBytes
        || text.getBytes(StandardCharsets.UTF_8).length > maxBytes
        || text.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))) {
      throw invalid();
    }
  }

  private static void requireId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw invalid();
    }
  }

  private static Failure invalid() {
    return new Failure("projection_invalid_input");
  }

  final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public Failure(String code) {
      super("检索投影调用或配置未通过安全校验。", null, false, true);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
