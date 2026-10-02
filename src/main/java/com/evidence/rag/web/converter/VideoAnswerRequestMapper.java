package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.dto.VideoAnswerCommand;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Video-only proof-mode mapping; authorization and complete selection remain in the Service. */
public final class VideoAnswerRequestMapper {
  private VideoAnswerRequestMapper() {}

  public static VideoAnswerCommand command(Map<String, Object> body) {
    if (body == null
        || !Set.of("question", "document_ids", "mode").containsAll(body.keySet())
        || !(body.get("mode") instanceof String supplied)) {
      throw ModelValues.invalid();
    }
    VideoAssessment.Mode mode =
        switch (supplied) {
          case "visual" -> VideoAssessment.Mode.VISUAL;
          case "transcript" -> VideoAssessment.Mode.TRANSCRIPT;
          case "joint" -> VideoAssessment.Mode.JOINT;
          case "ocr", "subtitle" -> null;
          default -> throw ModelValues.invalid();
        };
    var answer = new HashMap<>(body);
    answer.remove("mode");
    return new VideoAnswerCommand(
        AnswerRequestMapper.command(answer),
        mode,
        "ocr".equals(supplied),
        "subtitle".equals(supplied));
  }
}
