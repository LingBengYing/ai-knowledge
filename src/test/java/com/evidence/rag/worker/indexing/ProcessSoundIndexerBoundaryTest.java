package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessSoundIndexerBoundaryTest {
  @TempDir Path directory;

  @Test
  void alreadyInterruptedCallerStartsNoChildAndRetainsItsFlag() {
    try (var process =
        fixture("success", directory.resolve("unused"), directory.resolve("unused-release"))) {
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "sound_index_interrupted",
            assertThrows(
                    ProcessSoundIndexer.Failure.class,
                    () -> process.index(SoundIndexProtocolTest.request().claim()))
                .code());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(3, process.index(SoundIndexProtocolTest.request().claim()).entries().size());
    }
  }

  @Test
  void overlappingProcessUseIsDeniedWithoutCancellingTheFirstAndCanBeReused() throws Exception {
    Path ready = directory.resolve("ready"), release = directory.resolve("release");
    try (var process = fixture("wait", ready, release)) {
      var first = new FutureTask<>(() -> process.index(SoundIndexProtocolTest.request().claim()));
      Thread.ofVirtual().start(first);
      try {
        awaitFile(ready);
        assertEquals(
            "sound_index_busy",
            assertThrows(
                    ProcessSoundIndexer.Failure.class,
                    () -> process.index(SoundIndexProtocolTest.request().claim()))
                .code());
      } finally {
        Files.writeString(release, "release");
      }
      assertEquals(3, first.get(3, TimeUnit.SECONDS).entries().size());
      assertEquals(3, process.index(SoundIndexProtocolTest.request().claim()).entries().size());
    }
  }

  @Test
  void nonzeroChildExitInvalidatesEvenACompleteReceiptAndHasNoDiagnosticCause() {
    try (var process =
        fixture("nonzero", directory.resolve("unused"), directory.resolve("unused-release"))) {
      var failure =
          assertThrows(
              ProcessSoundIndexer.Failure.class,
              () -> process.index(SoundIndexProtocolTest.request().claim()));
      assertEquals("sound_index_failed", failure.code());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("fixture-secret-diagnostic"));
    }
  }

  @Test
  void zeroExitCannotMakeTruncatedOutputAValidReceipt() {
    try (var process =
        fixture("truncated", directory.resolve("unused"), directory.resolve("unused-release"))) {
      assertEquals(
          "sound_index_output_invalid",
          assertThrows(
                  ProcessSoundIndexer.Failure.class,
                  () -> process.index(SoundIndexProtocolTest.request().claim()))
              .code());
    }
  }

  @Test
  void cleanupEntryBudgetFailureKeepsInstanceFencedAndLeavesNoLiveChild() throws Exception {
    Path info = directory.resolve("child-info");
    var process = fixture("many-files", info, directory.resolve("unused-release"));
    try {
      assertEquals(
          "sound_index_cleanup_failed",
          assertThrows(
                  ProcessSoundIndexer.Failure.class,
                  () -> process.index(SoundIndexProtocolTest.request().claim()))
              .code());
      var lines = Files.readAllLines(info);
      assertFalse(
          ProcessHandle.of(Long.parseLong(lines.get(0))).map(ProcessHandle::isAlive).orElse(false));
      assertEquals(
          "sound_index_busy",
          assertThrows(
                  ProcessSoundIndexer.Failure.class,
                  () -> process.index(SoundIndexProtocolTest.request().claim()))
              .code());
    } finally {
      assertEquals(
          "sound_index_cleanup_failed",
          assertThrows(ProcessSoundIndexer.Failure.class, process::close).code());
      // Only this synthetic child's recorded private directory is reclaimed by the test.
      if (Files.exists(info)) {
        Path owned = Path.of(Files.readAllLines(info).get(1));
        try (var paths = Files.walk(owned)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
          }
        }
      }
    }
  }

  @Test
  void launchRejectsUntrustedMainClassAndCorruptPackagedClasspathOffline() throws Exception {
    var request = SoundIndexProtocolTest.request();
    assertEquals(
        "sound_index_output_invalid",
        assertThrows(
                ProcessSoundIndexer.Failure.class,
                () ->
                    new ProcessSoundIndexer(
                        request.sounds(),
                        request.models(),
                        request.projection(),
                        Duration.ofSeconds(5),
                        "unsafe main --argument",
                        List.of()))
            .code());
    Path corrupt = directory.resolve("corrupt.jar");
    Files.writeString(corrupt, "not a jar");
    String old = System.getProperty("surefire.test.class.path");
    try {
      System.setProperty("surefire.test.class.path", corrupt.toString());
      assertEquals(
          "sound_index_failed",
          assertThrows(
                  ProcessSoundIndexer.Failure.class,
                  () ->
                      new ProcessSoundIndexer(
                          request.sounds(),
                          request.models(),
                          request.projection(),
                          Duration.ofSeconds(5)))
              .code());
    } finally {
      restoreClasspath(old);
    }
  }

  @Test
  void plainJarAndJarWithoutManifestCannotPretendToBeBootWorkerPackages() throws Exception {
    var request = SoundIndexProtocolTest.request();
    String old = System.getProperty("surefire.test.class.path");
    try {
      for (boolean manifestPresent : new boolean[] {false, true}) {
        Path jar = directory.resolve(manifestPresent ? "plain.jar" : "no-manifest.jar");
        if (manifestPresent) {
          var manifest = new Manifest();
          manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
          manifest.getMainAttributes().putValue("Main-Class", "unavailable.synthetic.Main");
          try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.flush();
          }
        } else {
          try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.flush();
          }
        }
        System.setProperty("surefire.test.class.path", jar.toString());
        try (var process =
            new ProcessSoundIndexer(
                request.sounds(), request.models(), request.projection(), Duration.ofSeconds(5))) {
          assertEquals(
              "sound_index_failed",
              assertThrows(ProcessSoundIndexer.Failure.class, () -> process.index(request.claim()))
                  .code());
        }
      }
    } finally {
      restoreClasspath(old);
    }
  }

  private static void restoreClasspath(String original) {
    if (original == null) {
      System.clearProperty("surefire.test.class.path");
    } else {
      System.setProperty("surefire.test.class.path", original);
    }
  }

  private static ProcessSoundIndexer fixture(String mode, Path ready, Path release) {
    var request = SoundIndexProtocolTest.request();
    return new ProcessSoundIndexer(
        request.sounds(),
        request.models(),
        request.projection(),
        Duration.ofSeconds(5),
        Fixture.class.getName(),
        List.of(mode, ready.toString(), release.toString()));
  }

  private static void awaitFile(Path file) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!Files.exists(file) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(file));
  }

  public static final class Fixture {
    public static void main(String[] args) throws Exception {
      var request = SoundIndexProtocol.readRequest(System.in);
      if (args[0].equals("wait")) {
        Files.writeString(Path.of(args[1]), "ready");
        while (!Files.exists(Path.of(args[2]))) {
          Thread.sleep(10);
        }
      }
      if (args[0].equals("many-files")) {
        Path owned = Path.of(System.getProperty("java.io.tmpdir"));
        Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\n" + owned);
        for (int i = 0; i < 1030; i++) {
          Files.writeString(owned.resolve("synthetic-" + i), "fixture");
        }
      }
      if (args[0].equals("truncated")) {
        System.out.write(new byte[] {0x52, 0x41});
      } else {
        System.out.write(SoundIndexProtocol.encode(SoundIndexProtocolTest.receipt(request)));
      }
      System.out.flush();
      if (args[0].equals("nonzero")) {
        System.err.print("fixture-secret-diagnostic");
        System.exit(7);
      }
    }
  }
}
