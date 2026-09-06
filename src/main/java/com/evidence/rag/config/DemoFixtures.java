package com.evidence.rag.config;

import com.evidence.rag.management.ManagementModule;
import com.evidence.rag.shared.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Explicit local CLI fixture import; never registered as a web endpoint or automatic startup hook.
 */
public final class DemoFixtures {
  private DemoFixtures() {}

  public static void seed(Path directory) {
    if (Files.exists(directory.resolve("java-library.db"))) {
      throw new IllegalStateException(
          "Demo import requires a new empty database; existing data is never overwritten");
    }
    try (var module = new ManagementModule(directory)) {
      var owner = new Actor("org-main", "owner");
      var readAndEdit = Map.of("reader", "reader", "editor", "editor");
      module.registerSyntheticDocument(
          owner,
          fixture("demo-document", "差旅政策（合成资料）.pdf", "document", "application/pdf"),
          readAndEdit);
      module.registerSyntheticDocument(
          owner, fixture("demo-image", "设备标识（合成资料）.png", "image", "image/png"), readAndEdit);
      module.registerSyntheticDocument(
          owner, fixture("demo-audio", "项目例会（合成资料）.mp3", "audio", "audio/mpeg"), Map.of());
      module.registerSyntheticDocument(
          owner, fixture("demo-video", "操作培训（合成资料）.mp4", "video", "video/mp4"), Map.of());
      module.createFolder(owner, Map.of("name", "待整理"));
    }
  }

  private static ManagementModule.SyntheticDocument fixture(
      String id, String filename, String type, String mime) {
    // No original file or evidence exists; the synthetic marker remains visible in every list row.
    return new ManagementModule.SyntheticDocument(
        id, filename, type, mime, "synthetic-" + id, "0".repeat(64), 0);
  }
}
