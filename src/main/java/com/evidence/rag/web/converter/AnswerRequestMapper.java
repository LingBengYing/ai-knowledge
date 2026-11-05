package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Logged-in workspace questions; document_ids is a deprecated, ignored compatibility field. */
public final class AnswerRequestMapper {
  private AnswerRequestMapper() {}

  public static AnswerCommand command(Map<String, Object> body) {
    if (body == null
        || !body.containsKey("question")
        || !Set.of("question", "document_ids").containsAll(body.keySet())
        || !(body.get("question") instanceof String question)) {
      throw invalid();
    }
    if (!body.containsKey("document_ids")) {
      return new AnswerCommand(question, DocumentSelection.allDocuments());
    }
    if (!(body.get("document_ids") instanceof List<?> supplied)) {
      throw invalid();
    }
    for (Object value : supplied) {
      if (!(value instanceof String id) || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
        throw invalid();
      }
    }
    return new AnswerCommand(question, DocumentSelection.allDocuments());
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "问答请求字段或取值无效。");
  }
}
