package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerRequestMapperTest {
  @Test
  void missingSelectionAndExplicitEmptyHaveDifferentMeaning() {
    var all = AnswerRequestMapper.command(Map.of("question", "政策？"));
    var empty = AnswerRequestMapper.command(Map.of("question", "政策？", "document_ids", List.of()));
    assertTrue(all.selection().all());
    assertFalse(empty.selection().all());
    assertTrue(empty.selection().documentIds().isEmpty());
    assertEquals("政策？", empty.question());
  }

  @Test
  void preservesEverySelectedIdAndRejectsAmbiguousOrOversizedSelection() {
    var ids = new ArrayList<String>();
    for (int index = 0; index < 128; index++) {
      ids.add("doc-" + index);
    }
    var command = AnswerRequestMapper.command(Map.of("question", "政策？", "document_ids", ids));
    assertEquals(ids, command.selection().documentIds());
    ids.add("doc-128");
    assertEquals(128, command.selection().documentIds().size());
    assertInvalid(Map.of("question", "政策？", "document_ids", ids));
    for (Object value :
        List.of(
            List.of("doc-one", "doc-one"),
            List.of("../private"),
            List.of(3),
            "doc-one",
            Arrays.asList("doc-one", null))) {
      assertInvalid(Map.of("question", "政策？", "document_ids", value));
    }
    var nullSelection = new LinkedHashMap<String, Object>();
    nullSelection.put("question", "政策？");
    nullSelection.put("document_ids", null);
    assertInvalid(nullSelection);
  }

  @Test
  void validatesQuestionUnicodeAndExactUtf8BudgetWithoutCoercion() {
    String boundary = "中".repeat(1365) + "a";
    assertEquals(boundary, AnswerRequestMapper.command(Map.of("question", boundary)).question());
    assertEquals("政策😀？", AnswerRequestMapper.command(Map.of("question", "政策😀？")).question());
    for (Object question :
        new Object[] {
          null, true, 3, List.of("text"), "", "  \n", "\uD800", "\uDC00", "\u0000", boundary + "a"
        }) {
      var body = new LinkedHashMap<String, Object>();
      body.put("question", question);
      assertInvalid(body);
    }
  }

  @Test
  void rejectsUnknownAuthorityAndProviderFields() {
    assertInvalid(null);
    assertInvalid(Map.of());
    for (String field : List.of("role", "workspace_id", "locator", "model", "score", "extra")) {
      assertInvalid(Map.of("question", "政策？", field, "untrusted"));
    }
  }

  private static void assertInvalid(Map<String, Object> body) {
    var failure = assertThrows(ApplicationException.class, () -> AnswerRequestMapper.command(body));
    assertEquals(FailureKind.INVALID_INPUT, failure.kind());
    assertEquals("invalid_request", failure.code());
  }
}
