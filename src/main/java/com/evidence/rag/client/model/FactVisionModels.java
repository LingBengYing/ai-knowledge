package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VisualImage;
import java.util.List;

/** Original-image target-fact seam, distinct from whole-question visual assessment. */
public interface FactVisionModels {
  String PROMPT_REVISION = "java-fact-vision-prompt-v1";

  VisionModels.Draft draftFact(String question, QuestionFact fact, VisualImage image);

  VisionModels.Verification verifyFact(
      String question, QuestionFact fact, VisualImage image, List<String> claims);

  String revision();
}
