package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Immutable query-time settings, independent of every model and index identity. */
public record RetrievalSettings(
    long version,
    String searchMethod,
    String rankingMode,
    double denseWeight,
    int topK,
    boolean scoreThresholdEnabled,
    double scoreThreshold) {
  public static final long MAX_VERSION = 9_007_199_254_740_991L;

  public RetrievalSettings {
    if (version < 0
        || version > MAX_VERSION
        || searchMethod == null
        || !Set.of("vector", "full_text", "hybrid").contains(searchMethod)
        || rankingMode == null
        || !Set.of("weighted", "rerank").contains(rankingMode)
        || !Double.isFinite(denseWeight)
        || denseWeight < 0
        || denseWeight > 1
        || topK < 1
        || topK > 20
        || !Double.isFinite(scoreThreshold)) {
      throw new ApplicationException(
          FailureKind.INVALID_REQUEST, "invalid_retrieval_settings", "检索设置字段或取值无效。");
    }
  }

  public static RetrievalSettings defaults() {
    return new RetrievalSettings(0, "hybrid", "rerank", 0.5, 5, false, 0.5);
  }

  public boolean rerank() {
    return rankingMode.equals("rerank");
  }

  public String retrievalScoreKind() {
    return switch (searchMethod) {
      case "vector" -> "vector_similarity";
      case "full_text" -> "bm25";
      default -> rerank() ? "rrf" : "weighted_score";
    };
  }

  public String thresholdScoreKind() {
    return rerank() ? "rerank_score" : retrievalScoreKind();
  }

  public String fingerprint() {
    return ModelValues.sha256(
        ("java-retrieval-settings-v1\n"
                + version
                + "\n"
                + searchMethod
                + "\n"
                + rankingMode
                + "\n"
                + Double.toHexString(denseWeight)
                + "\n"
                + topK
                + "\n"
                + scoreThresholdEnabled
                + "\n"
                + Double.toHexString(scoreThreshold))
            .getBytes(StandardCharsets.UTF_8));
  }
}
