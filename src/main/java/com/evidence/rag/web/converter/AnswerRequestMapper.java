package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict HTTP request shape; all authorization decisions remain in the application Module. */
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
    if (!(body.get("document_ids") instanceof List<?> supplied) || supplied.size() > 128) {
      throw invalid();
    }
    var ids = new ArrayList<String>();
    for (Object value : supplied) {
      if (!(value instanceof String id) || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
        throw invalid();
      }
      ids.add(id);
    }
    return new AnswerCommand(question, DocumentSelection.selected(ids));
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "问答请求字段或取值无效。");
  }
}
