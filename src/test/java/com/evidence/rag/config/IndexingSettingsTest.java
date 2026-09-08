package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IndexingSettingsTest {
  @Test
  void totalJobDeadlineIsBoundedEvenWhenCapabilityIsDisabled() {
    for (boolean enabled : new boolean[] {false, true}) {
      for (int invalid : new int[] {Integer.MIN_VALUE, -1, 0, 9, 600001, Integer.MAX_VALUE}) {
        assertThrows(IllegalArgumentException.class, () -> new IndexingSettings(enabled, invalid));
      }
    }
  }

  @Test
  void explicitBoundariesDoNotImplicitlyEnableIndexing() {
    assertFalse(new IndexingSettings(false, 10).enabled());
    assertEquals(600000, new IndexingSettings(true, 600000).timeoutMs());
  }
}
