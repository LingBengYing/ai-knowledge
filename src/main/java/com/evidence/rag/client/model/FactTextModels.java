package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.QuestionFact;
import java.util.List;

/** Target-fact extraction seam; callers retain the complete question and own factual proof. */
public interface FactTextModels {
  String PROMPT_REVISION = "java-fact-text-prompt-v1";

  TextModels.Extraction extractFact(
      String question, QuestionFact fact, List<TextModels.Evidence> evidence);

  String revision();
}
