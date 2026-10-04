package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;

/** Complete existing selection, with a separate result limit for each material category. */
public record ProductHelpCommand(AnswerCommand answer, int topK, boolean rerank) {
  public ProductHelpCommand {
    if (answer == null || topK < 1 || topK > 10) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "ProductHelpCommand[redacted]";
  }
}
