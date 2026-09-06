package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.RagApplication;
import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.shared.Actor;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoFixturesTest {
  @TempDir Path directory;

  @Test
  void seedingIsExplicitSyntheticAndNeverOverwritesData() {
    assertThrows(
        IllegalArgumentException.class, () -> RagApplication.main(new String[] {"--seed-demo"}));
    RagApplication.main(new String[] {"--seed-demo", directory.toString()});
    try (var module = new ManagementModule(directory)) {
      assertEquals(4L, module.listDocuments(new Actor("org-main", "owner"), Map.of()).get("total"));
      assertEquals(
          2L, module.listDocuments(new Actor("org-main", "reader"), Map.of()).get("total"));
      assertEquals(
          0L, module.listDocuments(new Actor("elsewhere", "owner"), Map.of()).get("total"));
      assertTrue(
          module
              .listDocuments(new Actor("org-main", "owner"), Map.of())
              .toString()
              .contains("synthetic_fixture=true"));
    }
    assertThrows(IllegalStateException.class, () -> DemoFixtures.seed(directory));
  }
}
