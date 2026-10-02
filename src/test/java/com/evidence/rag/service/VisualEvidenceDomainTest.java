package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.VisualTraceEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualEvidenceDomainTest {
  @Test
  void imageAnswerHasACompleteTraceWithoutManufacturedTextEvidence() {
    var visual =
        new VisualTraceEvidence(
            1,
            "visual-physical",
            0.8,
            0.9,
            List.of("a".repeat(64)),
            "synthetic-vision-v1",
            "java-visual-assessment-v1");

    var trace =
        new TraceDraft(
            "b".repeat(64),
            "c".repeat(64),
            "answered",
            null,
            "synthetic-text-v1",
            "visual-answer-v1",
            "visual-answer-v1",
            List.of(),
            List.of(visual));

    assertTrue(trace.evidence().isEmpty());
    assertEquals(List.of(visual), trace.visualEvidence());
    assertEquals("TraceDraft[redacted]", trace.toString());
  }
}
