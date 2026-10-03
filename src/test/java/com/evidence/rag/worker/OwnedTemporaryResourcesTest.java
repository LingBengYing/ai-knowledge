package com.evidence.rag.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.LibraryOperationGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedTemporaryResourcesTest {
  @TempDir Path directory;

  @Test
  void capturedRootSurvivesThreadDispatchAndOnlyOwnedWorkIsRemoved() throws Exception {
    Path root = directory.toRealPath().resolve("work");
    var gate = new LibraryOperationGate(root);
    Path captured;
    try (var operation = gate.enter()) {
      captured = OwnedTemporaryResources.currentRoot();
      assertEquals(root, captured);
      assertTrue(gate.tryMaintenance().isEmpty());
    }
    Path work = OwnedTemporaryResources.createDirectory("rag-index-", captured);
    Files.writeString(work.resolve("original.txt"), "synthetic private payload");
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertTrue(OwnedTemporaryResources.sweep(root, maintenance));
    }
    assertFalse(Files.exists(work));
    assertTrue(Files.isDirectory(root.resolve(".owners")));
  }

  @Test
  void anUnrecordedPathPreventsSweepWithoutTouchingOtherFiles() throws Exception {
    Path root = directory.toRealPath().resolve("work");
    var gate = new LibraryOperationGate(root);
    Path work;
    try (var operation = gate.enter()) {
      work = OwnedTemporaryResources.createDirectory("rag-parser-");
    }
    Files.writeString(work.resolve("original"), "kept while unknown resource exists");
    Path unknown = root.resolve("unregistered");
    Files.writeString(unknown, "not owned");
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertFalse(OwnedTemporaryResources.sweep(root, maintenance));
    }
    assertEquals("not owned", Files.readString(unknown));
    assertTrue(Files.exists(work.resolve("original")));
  }

  @Test
  void aLiveChildAndAmbiguousLaunchNeverReleaseTemporaryPayload() throws Exception {
    Path root = directory.toRealPath().resolve("work");
    var gate = new LibraryOperationGate(root);
    Path work;
    try (var operation = gate.enter()) {
      work = OwnedTemporaryResources.createDirectory("rag-audio-vector-");
    }
    Files.writeString(work.resolve("wave"), "saved synthetic wave");
    OwnedTemporaryResources.launching(work);
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertFalse(OwnedTemporaryResources.sweep(root, maintenance));
    }
    Process child = new ProcessBuilder("/bin/sleep", "30").start();
    try {
      OwnedTemporaryResources.childStarted(work, child);
      try (var maintenance = gate.tryMaintenance().orElseThrow()) {
        assertFalse(OwnedTemporaryResources.sweep(root, maintenance));
      }
      assertTrue(Files.exists(work.resolve("wave")));
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS));
    }
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertFalse(OwnedTemporaryResources.sweep(root, maintenance));
    }
    Files.delete(work.resolve("wave"));
    Files.delete(work);
    OwnedTemporaryResources.finished(work);
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertTrue(OwnedTemporaryResources.sweep(root, maintenance));
    }
  }

  @Test
  void symlinkAndUnheldMaintenanceCannotEraseForeignPayload() throws Exception {
    Path root = directory.toRealPath().resolve("work");
    var gate = new LibraryOperationGate(root);
    Path work;
    try (var operation = gate.enter()) {
      work = OwnedTemporaryResources.createDirectory("rag-video-av-index-");
    }
    Path foreign = directory.resolve("foreign.txt");
    Files.writeString(foreign, "untouched");
    Files.createSymbolicLink(work.resolve("link"), foreign);
    assertThrows(IOException.class, () -> OwnedTemporaryResources.sweep(root, null));
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertThrows(IOException.class, () -> OwnedTemporaryResources.sweep(root, maintenance));
    }
    assertEquals("untouched", Files.readString(foreign));
  }

  @Test
  void maintenanceCannotCreateANewWorkerEvenWithACapturedRoot() throws Exception {
    Path root = directory.toRealPath().resolve("work");
    var gate = new LibraryOperationGate(root);
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertThrows(
          IOException.class, () -> OwnedTemporaryResources.createDirectory("rag-parser-", root));
      assertFalse(Files.exists(root));
    }
  }
}
