package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoAssessment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoAnswerRequestMapperTest {
  private static final String QUESTION = "指示灯的颜色是什么？重启等待时间是多少秒？";

  @Test
  void explicitProofModesKeepTheEntireQuestionAndSelection() {
    for (var mode : VideoAssessment.Mode.values()) {
      var command =
          VideoAnswerRequestMapper.command(
              Map.of(
                  "question",
                  QUESTION,
                  "mode",
                  mode.name().toLowerCase(java.util.Locale.ROOT),
                  "document_ids",
                  List.of("video-1", "uncited-text-2")));
      assertEquals(QUESTION, command.answer().question());
      assertEquals(mode, command.mode());
      assertFalse(command.answer().selection().all());
      assertEquals(
          List.of("video-1", "uncited-text-2"), command.answer().selection().documentIds());
      assertFalse(command.toString().contains(QUESTION));
    }
  }

  @Test
  void omittedSelectionAndExplicitEmptyRemainDifferent() {
    assertTrue(
        VideoAnswerRequestMapper.command(Map.of("question", QUESTION, "mode", "joint"))
            .answer()
            .selection()
            .all());
    var empty =
        VideoAnswerRequestMapper.command(
            Map.of("question", QUESTION, "mode", "joint", "document_ids", List.of()));
    assertFalse(empty.answer().selection().all());
    assertTrue(empty.answer().selection().documentIds().isEmpty());
  }

  @Test
  void rejectsMissingUnknownOrNullModesAndForgedSourceFields() {
    for (Object mode : List.of("all", "JOINT", 1, List.of("joint"))) {
      assertInvalid(Map.of("question", QUESTION, "mode", mode));
    }
    assertInvalid(null);
    assertInvalid(Map.of("question", QUESTION));
    var nullMode = new LinkedHashMap<String, Object>();
    nullMode.put("question", QUESTION);
    nullMode.put("mode", null);
    assertInvalid(nullMode);
    assertInvalid(Map.of("question", QUESTION, "mode", "joint", "group_id", "client-chosen"));
    assertInvalid(Map.of("question", QUESTION, "mode", "joint", "document_ids", "video-1"));
    assertInvalid(Map.of("question", "", "mode", "joint"));
  }

  private static void assertInvalid(Map<String, Object> body) {
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> VideoAnswerRequestMapper.command(body))
            .code());
  }
}
