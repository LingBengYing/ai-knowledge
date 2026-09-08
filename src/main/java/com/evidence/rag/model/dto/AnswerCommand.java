package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** Validated question and explicit selection; the trusted Actor is never client input. */
public record AnswerCommand(String question, DocumentSelection selection) {
  public AnswerCommand {
    if (question == null
        || question.isBlank()
        || selection == null
        || question.getBytes(StandardCharsets.UTF_8).length > 4096
        || question
            .codePoints()
            .anyMatch(
                c ->
                    (c < 32 && c != '\n' && c != '\t')
                        || c == 127
                        || (c >= 0xD800 && c <= 0xDFFF))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "AnswerCommand[redacted]";
  }
}
