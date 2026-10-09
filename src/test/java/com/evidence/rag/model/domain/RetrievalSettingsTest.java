package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import org.junit.jupiter.api.Test;

class RetrievalSettingsTest {
  @Test
  void defaultsAndScoreKindsSeparateRankingFromThresholds() {
    assertEquals(
        new RetrievalSettings(0, "hybrid", "rerank", 0.5, 5, false, 0.5),
        RetrievalSettings.defaults());
    assertTrue(RetrievalSettings.defaults().rerank());
    assertEquals("rrf", RetrievalSettings.defaults().retrievalScoreKind());
    assertEquals("rerank_score", RetrievalSettings.defaults().thresholdScoreKind());
    for (String method : new String[] {"vector", "full_text", "hybrid"}) {
      String expected =
          switch (method) {
            case "vector" -> "vector_similarity";
            case "full_text" -> "bm25";
            default -> "weighted_score";
          };
      var weighted = new RetrievalSettings(1, method, "weighted", 0, 1, true, -2.5);
      assertEquals(expected, weighted.retrievalScoreKind());
      assertEquals(expected, weighted.thresholdScoreKind());
      assertEquals(
          "rerank_score",
          new RetrievalSettings(1, method, "rerank", 1, 20, true, 2.5).thresholdScoreKind());
    }
  }

  @Test
  void fingerprintBindsEveryFieldAndVersionWithoutModelState() {
    var original = RetrievalSettings.defaults();
    assertEquals(original.fingerprint(), RetrievalSettings.defaults().fingerprint());
    for (var changed :
        new RetrievalSettings[] {
          new RetrievalSettings(1, "hybrid", "rerank", 0.5, 5, false, 0.5),
          new RetrievalSettings(0, "vector", "rerank", 0.5, 5, false, 0.5),
          new RetrievalSettings(0, "hybrid", "weighted", 0.5, 5, false, 0.5),
          new RetrievalSettings(0, "hybrid", "rerank", 0.6, 5, false, 0.5),
          new RetrievalSettings(0, "hybrid", "rerank", 0.5, 6, false, 0.5),
          new RetrievalSettings(0, "hybrid", "rerank", 0.5, 5, true, 0.5),
          new RetrievalSettings(0, "hybrid", "rerank", 0.5, 5, false, 0.6)
        }) {
      assertNotEquals(original.fingerprint(), changed.fingerprint());
    }
  }

  @Test
  void invalidShapesNeverBecomeSilentDefaults() {
    for (Runnable invalid :
        new Runnable[] {
          () -> new RetrievalSettings(-1, "hybrid", "rerank", 0.5, 5, false, 0.5),
          () ->
              new RetrievalSettings(
                  RetrievalSettings.MAX_VERSION + 1, "hybrid", "rerank", 0.5, 5, false, 0.5),
          () -> new RetrievalSettings(0, "unknown", "rerank", 0.5, 5, false, 0.5),
          () -> new RetrievalSettings(0, "hybrid", "unknown", 0.5, 5, false, 0.5),
          () -> new RetrievalSettings(0, "hybrid", "rerank", Double.NaN, 5, false, 0.5),
          () -> new RetrievalSettings(0, "hybrid", "rerank", -0.1, 5, false, 0.5),
          () -> new RetrievalSettings(0, "hybrid", "rerank", 1.1, 5, false, 0.5),
          () -> new RetrievalSettings(0, "hybrid", "rerank", 0.5, 0, false, 0.5),
          () -> new RetrievalSettings(0, "hybrid", "rerank", 0.5, 21, false, 0.5),
          () ->
              new RetrievalSettings(0, "hybrid", "rerank", 0.5, 5, false, Double.POSITIVE_INFINITY)
        }) {
      assertEquals(
          FailureKind.INVALID_REQUEST,
          assertThrows(ApplicationException.class, invalid::run).kind());
    }
  }
}
