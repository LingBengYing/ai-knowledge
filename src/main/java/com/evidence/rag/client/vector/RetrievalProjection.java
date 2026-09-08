package com.evidence.rag.client.vector;

import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Projection seam. Callers supply authority-validated scope and freeze revision writes during
 * verify.
 */
public interface RetrievalProjection {
  int MAX_SCOPE = 128;
  int MAX_BATCH = 64;
  int MAX_TEXT_BYTES = 16_384;
  int MAX_QUERY_BYTES = 4_096;
  int MAX_TOP_K = 100;
  int MAX_REVISION_SEGMENTS = 4096;

  String identity();

  VerifiedRevision verify(RevisionManifest manifest);

  /** Maps immutable source identity into one immutable indexing-attempt namespace. */
  static String physicalSegmentId(String generationId, String sourceSegmentId) {
    checkInterrupted();
    requireId(generationId);
    requireId(sourceSegmentId);
    MessageDigest hash = digest();
    digestText(hash, "evidence-rag-physical-segment-v1");
    digestText(hash, generationId);
    digestText(hash, sourceSegmentId);
    return "seg-" + HexFormat.of().formatHex(hash.digest());
  }

  /** SHA-256 over length-prefixed strict UTF-8 fields and big-endian canonical float32 bits. */
  static String entryDigest(Entry entry) {
    if (entry == null) {
      throw invalid();
    }
    MessageDigest digest = digest();
    digestText(digest, "evidence-rag-entry-float32-v1");
    digestText(digest, entry.segmentId());
    digestText(digest, entry.workspaceId());
    digestText(digest, entry.documentId());
    digestText(digest, entry.revisionId());
    digestText(digest, entry.text());
    digestInt(digest, entry.vector().size());
    for (Double value : entry.vector()) {
      checkInterrupted();
      digestInt(digest, Float.floatToIntBits(value.floatValue()));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  record RevisionManifest(
      String workspaceId, String documentId, String revisionId, Map<String, String> entryDigests) {
    public RevisionManifest {
      requireId(workspaceId);
      requireId(documentId);
      requireId(revisionId);
      if (entryDigests == null
          || entryDigests.isEmpty()
          || entryDigests.size() > MAX_REVISION_SEGMENTS) {
        throw invalid();
      }
      var copied = new TreeMap<String, String>();
      entryDigests.forEach(
          (id, hash) -> {
            checkInterrupted();
            requireId(id);
            requireHash(hash);
            copied.put(id, hash);
          });
      entryDigests = Collections.unmodifiableMap(copied);
    }

    public String sha256() {
      MessageDigest digest = digest();
      digestText(digest, "evidence-rag-revision-manifest-v1");
      digestText(digest, workspaceId);
      digestText(digest, documentId);
      digestText(digest, revisionId);
      digestInt(digest, entryDigests.size());
      entryDigests.forEach(
          (id, hash) -> {
            digestText(digest, id);
            digestText(digest, hash);
          });
      return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public String toString() {
      return "RevisionManifest[redacted]";
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static void digestText(MessageDigest digest, String text) {
    checkInterrupted();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    digestInt(digest, bytes.length);
    digest.update(bytes);
  }

  private static void digestInt(MessageDigest digest, int value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new ProjectionException("projection_interrupted");
    }
  }

  private static void requireHash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw invalid();
    }
  }

  void initialize();

  /** Validates existing query resources without creating, loading, or writing them. */
  void prepareSearch();

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
    var canonical = new ArrayList<Double>(vector.size());
    for (Double value : vector) {
      if (value == null || !Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE) {
        throw invalid();
      }
      float scalar = value.floatValue();
      nonzero |= scalar != 0;
      canonical.add(scalar == 0 ? 0.0 : (double) scalar);
    }
    if (!nonzero) {
      throw invalid();
    }
    return List.copyOf(canonical);
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

  private static ProjectionException invalid() {
    return new ProjectionException("projection_invalid_input");
  }
}
