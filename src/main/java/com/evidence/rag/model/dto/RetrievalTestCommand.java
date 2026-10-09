package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;

/** The existing question and full selection contract, plus bounded retrieval-only options. */
public record RetrievalTestCommand(
    AnswerCommand answer, Integer topK, Boolean rerank, RetrievalSettings retrievalSettings) {
  public RetrievalTestCommand(AnswerCommand answer, Integer topK, Boolean rerank) {
    this(answer, topK, rerank, null);
  }

  public RetrievalTestCommand {
    if (answer == null
        || (topK != null && (topK < 1 || topK > 20))
        || (retrievalSettings != null && (topK != null || rerank != null))) {
      throw ModelValues.invalid();
    }
  }

  public RetrievalSettings effectiveSettings(RetrievalSettings saved) {
    var base = retrievalSettings == null ? saved : retrievalSettings;
    return new RetrievalSettings(
        saved.version(),
        base.searchMethod(),
        rerank == null ? base.rankingMode() : rerank ? "rerank" : "weighted",
        base.denseWeight(),
        topK == null ? base.topK() : topK,
        base.scoreThresholdEnabled(),
        base.scoreThreshold());
  }

  @Override
  public String toString() {
    return "RetrievalTestCommand[redacted]";
  }
}
