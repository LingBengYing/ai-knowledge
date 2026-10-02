package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.VideoAssessment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoOcrRequestMapperTest {
  @Test
  void ocrIsAnIndependentTextProofModeWithoutChangingTheOriginalThreeModes() {
    var question = "Project A's status and budget?";
    var selected = List.of("video-1", "uncited-text-2");
    var command =
        VideoAnswerRequestMapper.command(
            Map.of("question", question, "mode", "ocr", "document_ids", selected));
    assertTrue(command.ocr());
    assertNull(command.mode());
    assertEquals(question, command.answer().question());
    assertEquals(selected, command.answer().selection().documentIds());
    assertEquals(3, VideoAssessment.Mode.values().length);
    assertFalse(command.toString().contains(question));
  }

  @Test
  void ocrEmptySelectionNeverBecomesAll() {
    var empty =
        VideoAnswerRequestMapper.command(
            Map.of("question", "What is the budget?", "mode", "ocr", "document_ids", List.of()));
    assertFalse(empty.answer().selection().all());
    assertTrue(empty.answer().selection().documentIds().isEmpty());
    assertTrue(
        VideoAnswerRequestMapper.command(Map.of("question", "What is the budget?", "mode", "ocr"))
            .answer()
            .selection()
            .all());
  }
}
