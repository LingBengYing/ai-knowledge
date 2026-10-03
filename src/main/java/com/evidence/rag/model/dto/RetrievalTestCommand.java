package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;

/** The existing question and full selection contract, plus bounded retrieval-only options. */
public record RetrievalTestCommand(AnswerCommand answer, int topK, boolean rerank) {
  public RetrievalTestCommand {
    if (answer == null || topK < 1 || topK > 20) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "RetrievalTestCommand[redacted]";
  }
}
