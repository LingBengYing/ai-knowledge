package com.evidence.rag.bootstrap;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.service.ManagementService;
import java.util.Map;

/** Explicit synthetic seed use case; no automatic startup, database connection or HTTP endpoint. */
public final class DemoFixtures {
  private DemoFixtures() {}

  public static void seed(ManagementService management) {
    var owner = new Actor("org-main", "owner");
    var readAndEdit = Map.of("reader", "reader", "editor", "editor");
    management.registerSyntheticDocument(
        owner,
        fixture("demo-document", "差旅政策（合成资料）.pdf", "document", "application/pdf"),
        readAndEdit);
    management.registerSyntheticDocument(
        owner, fixture("demo-image", "设备标识（合成资料）.png", "image", "image/png"), readAndEdit);
    management.registerSyntheticDocument(
        owner, fixture("demo-audio", "项目例会（合成资料）.mp3", "audio", "audio/mpeg"), Map.of());
    management.registerSyntheticDocument(
        owner, fixture("demo-video", "操作培训（合成资料）.mp4", "video", "video/mp4"), Map.of());
    management.createFolder(owner, "待整理");
  }

  private static SyntheticDocument fixture(String id, String filename, String type, String mime) {
    // No original file or evidence exists; every list row retains its explicit synthetic marker.
    return new SyntheticDocument(id, filename, type, mime, "synthetic-" + id, "0".repeat(64), 0);
  }
}
