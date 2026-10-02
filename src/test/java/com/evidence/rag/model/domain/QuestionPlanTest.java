package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionPlanTest {
  @Test
  void validatesWholeQuestionIdentityAndMakesDefensiveCopy() {
    String question = "指示灯的颜色是什么？";
    var fact = fact(question, 0, "value\n指示灯的颜色\n");
    var mutable = new ArrayList<>(List.of(fact));
    var plan = new QuestionPlan(question, sha(question), "planner-test", mutable);
    mutable.clear();
    assertEquals(1, plan.facts().size());
    assertThrows(UnsupportedOperationException.class, () -> plan.facts().clear());
    assertThrows(
        ApplicationException.class,
        () -> new QuestionPlan(question, "a".repeat(64), "planner-test", List.of(fact)));
    assertThrows(
        ApplicationException.class,
        () -> new QuestionPlan("外壳的颜色是什么？", sha("外壳的颜色是什么？"), "planner-test", List.of(fact)));
  }

  @Test
  void requiresDenseUniqueFactListWithBoundedQuestionAndRequirements() {
    String question = "指示灯的颜色是什么？";
    var first = fact(question, 0, "value\n指示灯的颜色\n");
    var gap = fact(question, 2, "value\n重启操作\n");
    var duplicate = fact(question, 1, first.requirement());
    for (var facts :
        List.of(List.<QuestionFact>of(), List.of(first, gap), List.of(first, duplicate))) {
      assertThrows(
          ApplicationException.class,
          () -> new QuestionPlan(question, sha(question), "planner-test", facts));
    }
    assertThrows(
        ApplicationException.class, () -> new QuestionFact(-1, "a".repeat(64), "value\nx\n"));
    assertThrows(
        ApplicationException.class, () -> new QuestionFact(8, "a".repeat(64), "value\nx\n"));
    assertThrows(ApplicationException.class, () -> new QuestionFact(0, "invalid", "value\nx\n"));
    assertThrows(ApplicationException.class, () -> new QuestionFact(0, "a".repeat(64), ""));
    assertThrows(ApplicationException.class, () -> new QuestionFact(0, "a".repeat(64), "\u0000"));
    assertThrows(
        ApplicationException.class, () -> new QuestionFact(0, "a".repeat(64), "x".repeat(4097)));
    String oversized = "界".repeat(1366);
    assertThrows(
        ApplicationException.class,
        () -> new QuestionPlan(oversized, sha(oversized), "planner-test", List.of(first)));
  }

  private static QuestionFact fact(String question, int ordinal, String requirement) {
    return new QuestionFact(
        ordinal,
        sha("video-fact-v1\n" + sha(question) + "\n" + ordinal + "\n" + sha(requirement)),
        requirement);
  }

  private static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
