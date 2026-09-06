package com.evidence.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.retrieval.RetrievalProjection.AuthorizedScope;
import com.evidence.rag.retrieval.RetrievalProjection.Candidate;
import com.evidence.rag.retrieval.RetrievalProjection.Entry;
import com.evidence.rag.retrieval.RetrievalProjection.Failure;
import com.evidence.rag.retrieval.RetrievalProjection.Query;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalProjectionTest {
  @Test
  void scopesAndVectorsAreImmutableAndSecretsOrTextDoNotAppearInRepresentations() {
    var map = new HashMap<>(Map.of("doc-a", "rev-a"));
    var scope = new AuthorizedScope("org-main", map);
    map.put("doc-b", "rev-b");
    assertEquals(Map.of("doc-a", "rev-a"), scope.documentRevisions());
    assertThrows(UnsupportedOperationException.class, () -> scope.documentRevisions().clear());
    var vector = new ArrayList<>(List.of(1.0, 0.0));
    var entry =
        new Entry("segment-a", "org-main", "doc-a", "rev-a", "private fixture text", vector);
    var query = new Query("private fixture question", vector, scope, 1);
    vector.set(0, 9.0);
    assertEquals(List.of(1.0, 0.0), entry.vector());
    assertEquals(List.of(1.0, 0.0), query.vector());
    assertFalse(entry.toString().contains("private"));
    assertFalse(query.toString().contains("private"));
    assertFalse(scope.toString().contains("doc-a"));
    assertThrows(UnsupportedOperationException.class, () -> entry.vector().clear());
  }

  @Test
  void invalidAndOversizedScopesNeverSilentlyTruncateOrPermitFilterInjection() {
    for (String id : Arrays.asList(null, "", "a\" || true", "a/b", "a\\b", "汉", "a".repeat(129))) {
      assertThrows(Failure.class, () -> new AuthorizedScope(id, Map.of()));
      var map = new HashMap<String, String>();
      map.put(id, "r1");
      assertThrows(Failure.class, () -> new AuthorizedScope("org-main", map));
      map.clear();
      map.put("d1", id);
      assertThrows(Failure.class, () -> new AuthorizedScope("org-main", map));
    }
    assertThrows(Failure.class, () -> new AuthorizedScope("org-main", null));
    var complete = new HashMap<String, String>();
    for (int i = 0; i < 128; i++) {
      complete.put("doc-" + i, "rev-" + i);
    }
    assertEquals(128, new AuthorizedScope("org-main", complete).documentRevisions().size());
    complete.put("doc-128", "rev-128");
    assertThrows(Failure.class, () -> new AuthorizedScope("org-main", complete));
  }

  @Test
  void queryAndProjectionRecordsRejectInvalidTextVectorsAndLimits() {
    var scope = new AuthorizedScope("org-main", Map.of());
    for (List<Double> vector :
        Arrays.<List<Double>>asList(
            null,
            List.of(),
            List.of(1.0),
            List.of(0.0, 0.0),
            List.of(Double.MIN_VALUE, 0.0),
            Arrays.asList(1.0, null),
            List.of(Double.NaN, 0.0),
            List.of(Double.POSITIVE_INFINITY, 0.0),
            List.of(Double.MAX_VALUE, 0.0),
            Collections.nCopies(32_769, 1.0))) {
      assertThrows(Failure.class, () -> new Query("text", vector, scope, 1));
    }
    for (String text :
        Arrays.asList(null, "", "  ", "\u0000", "\uD800", "汉".repeat(6000), "a".repeat(16385))) {
      assertThrows(
          Failure.class, () -> new Entry("s", "org-main", "d", "r", text, List.of(1.0, 0.0)));
    }
    assertThrows(Failure.class, () -> new Query("汉".repeat(2000), List.of(1.0, 0.0), scope, 1));
    assertThrows(Failure.class, () -> new Query("text", List.of(1.0, 0.0), null, 1));
    assertThrows(Failure.class, () -> new Query("text", List.of(1.0, 0.0), scope, 0));
    assertThrows(Failure.class, () -> new Query("text", List.of(1.0, 0.0), scope, 101));
    assertThrows(Failure.class, () -> new Candidate("s", -1));
    assertThrows(Failure.class, () -> new Candidate("s", Double.NaN));
    assertEquals("s", new Candidate("s", 0).segmentId());
  }

  @Test
  void settingsRejectUnsafeOrIncompleteConfigurationWithoutExposingValues() {
    var defaults =
        new Object[] {
          URI.create("https://milvus.example.test"),
          "synthetic-" + "credential",
          "default",
          "java_fixture",
          "org-main",
          "fixture/embed@v1",
          2,
          Duration.ofSeconds(2),
          1024,
          false
        };
    List<Object[]> invalid =
        Arrays.asList(
            new Object[] {0, null},
            new Object[] {0, URI.create("relative/path")},
            new Object[] {0, URI.create("http://milvus.example.test")},
            new Object[] {0, URI.create("http://127.0.0.1")},
            new Object[] {0, URI.create("https://user:secret@milvus.example.test")},
            new Object[] {0, URI.create("https://milvus.example.test/?key=secret")},
            new Object[] {0, URI.create("https://milvus.example.test/#secret")},
            new Object[] {0, URI.create("https://milvus.example.test/path")},
            new Object[] {0, URI.create("https://milvus.example.test:0")},
            new Object[] {0, URI.create("https://milvus.example.test:65536")},
            new Object[] {0, URI.create("ftp://milvus.example.test")},
            new Object[] {1, null},
            new Object[] {1, "invalid\nheader"},
            new Object[] {1, "a".repeat(4097)},
            new Object[] {2, null},
            new Object[] {2, "1invalid"},
            new Object[] {3, null},
            new Object[] {3, "legacy_collection"},
            new Object[] {3, "java_bad\"name"},
            new Object[] {4, null},
            new Object[] {4, "org/other"},
            new Object[] {5, null},
            new Object[] {5, "invalid\"model"},
            new Object[] {6, 1},
            new Object[] {6, 32769},
            new Object[] {7, null},
            new Object[] {7, Duration.ZERO},
            new Object[] {7, Duration.ofSeconds(61)},
            new Object[] {8, 1023},
            new Object[] {8, 4_194_305});
    for (Object[] change : invalid) {
      Object[] values = defaults.clone();
      values[(Integer) change[0]] = change[1];
      Failure failure = assertThrows(Failure.class, () -> settings(values));
      assertEquals("projection_invalid_configuration", failure.code());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("secret"));
    }
    assertFalse(settings(defaults).toString().contains("credential"));
    assertThrows(Failure.class, () -> new MilvusRestProjection(null));
    defaults[0] = URI.create("http://[::1]:19530/");
    defaults[9] = true;
    assertDoesNotThrow(() -> settings(defaults));
    defaults[0] = URI.create("http://localhost:19530");
    assertThrows(Failure.class, () -> settings(defaults));
  }

  private static MilvusRestProjection.Settings settings(Object[] values) {
    return new MilvusRestProjection.Settings(
        (URI) values[0],
        (String) values[1],
        (String) values[2],
        (String) values[3],
        (String) values[4],
        (String) values[5],
        (Integer) values[6],
        (Duration) values[7],
        (Integer) values[8],
        (Boolean) values[9]);
  }

  @Test
  void deterministicTestAdapterObeysScopeAndInitializationButIsNotQualityEvidence() {
    RetrievalProjection projection = new DeterministicProjection("org-main", 2);
    Query query =
        new Query(
            "policy",
            List.of(1.0, 0.0),
            new AuthorizedScope("org-main", Map.of("doc-a", "active")),
            1);
    assertThrows(Failure.class, () -> projection.search(query));
    projection.upsert(List.of());
    assertEquals(
        List.of(),
        projection.search(
            new Query("policy", List.of(1.0, 0.0), new AuthorizedScope("org-main", Map.of()), 1)));
    projection.initialize();
    projection.upsert(
        List.of(
            new Entry("a-inactive", "org-main", "doc-a", "old", "synthetic", List.of(1.0, 0.0)),
            new Entry(
                "b-unselected", "org-main", "doc-b", "active", "synthetic", List.of(1.0, 0.0)),
            new Entry("c-active", "org-main", "doc-a", "active", "synthetic", List.of(1.0, 0.0))));
    assertEquals(
        List.of("c-active"), projection.search(query).stream().map(Candidate::segmentId).toList());
  }
}
