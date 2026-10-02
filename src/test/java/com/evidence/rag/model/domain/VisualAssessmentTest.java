package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualAssessmentTest {
  private static final String SHA = "a".repeat(64);

  @Test
  void acceptedFactsAreCopiedWithoutChangingMultilineOrUnicodeText() {
    var claims = new ArrayList<>(List.of("红色圆形\n位于左侧。", "A square\tis beside it. 😀"));
    var assessment = assessment(claims, null);
    claims.clear();

    assertEquals(List.of("红色圆形\n位于左侧。", "A square\tis beside it. 😀"), assessment.claims());
    assertThrows(UnsupportedOperationException.class, () -> assessment.claims().clear());
    assertEquals("VisualAssessment[redacted]", assessment.toString());
  }

  @Test
  void refusalUsesFiniteSafeReasonsAndCannotCarryPartialFacts() {
    for (String reason :
        List.of(
            "model_refused",
            "incomplete_evidence",
            "unsupported_claims",
            "model_failure",
            "configuration_changed",
            "processing_interrupted")) {
      assertTrue(assessment(List.of(), reason).claims().isEmpty());
      assertThrows(ApplicationException.class, () -> assessment(List.of("private fact"), reason));
    }
    assertThrows(ApplicationException.class, () -> assessment(List.of(), "private response body"));
    assertThrows(ApplicationException.class, () -> assessment(List.of(), null));
  }

  @Test
  void incompleteDuplicateOrOverBudgetFactsAndInvalidIdentityAreRejected() {
    assertThrows(ApplicationException.class, () -> assessment(null, null));
    assertThrows(ApplicationException.class, () -> assessment(List.of("same", "same"), null));
    assertThrows(ApplicationException.class, () -> assessment(List.of(" "), null));
    assertThrows(ApplicationException.class, () -> assessment(List.of("\uD800"), null));
    assertThrows(ApplicationException.class, () -> assessment(List.of("x".repeat(1025)), null));
    assertThrows(
        ApplicationException.class,
        () -> assessment(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), null));
    assertThrows(
        ApplicationException.class,
        () -> new VisualAssessment(List.of("fact"), "bad", "model-v1", "policy-v1", null));
    assertThrows(
        ApplicationException.class,
        () -> new VisualAssessment(List.of("fact"), SHA, "", "policy-v1", null));
  }

  private static VisualAssessment assessment(List<String> claims, String reason) {
    return new VisualAssessment(claims, SHA, "model-v1", "policy-v1", reason);
  }
}
