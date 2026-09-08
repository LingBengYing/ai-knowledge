package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AnswersSettingsTest {
  @Test
  void acceptsOnlyBoundedProcessingBudgetsAndScarceResourceConcurrency() {
    assertEquals(60000, new AnswersSettings(true, 60000, 2).timeoutMs());
    assertEquals(1, new AnswersSettings(false, 10, 1).maxConcurrent());
    assertEquals(8, new AnswersSettings(true, 600000, 8).maxConcurrent());
    for (int timeout : new int[] {Integer.MIN_VALUE, 0, 9, 600001, Integer.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> new AnswersSettings(true, timeout, 2));
    }
    for (int capacity : new int[] {Integer.MIN_VALUE, 0, 9, Integer.MAX_VALUE}) {
      assertThrows(
          IllegalArgumentException.class, () -> new AnswersSettings(false, 60000, capacity));
    }
  }
}
