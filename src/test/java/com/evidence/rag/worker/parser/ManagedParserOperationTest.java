package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.OwnedTemporaryResources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedParserOperationTest {
  @TempDir Path directory;

  @Test
  void actualParserChildRetainsOperationAdmissionAfterTheSubmittingThreadLeaves() throws Exception {
    var gate = new LibraryOperationGate(directory.toRealPath().resolve("work"));
    Path pidFile = directory.resolve("child.pid");
    try (var parser =
        new ProcessTextParser(
            Duration.ofSeconds(10),
            ProcessTextParserTest.ProcessFixture.class.getName(),
            List.of("hang", pidFile.toString()))) {
      FutureTask<String> task;
      try (var submitter = gate.enter()) {
        var body =
            LibraryOperationGate.protectCurrent(
                (Callable<String>)
                    () -> {
                      try {
                        parser.parse(
                            "source.txt",
                            "text/plain",
                            "owned synthetic payload".getBytes(StandardCharsets.UTF_8));
                        return "unexpected";
                      } catch (TextParser.Failure stopped) {
                        return stopped.code();
                      }
                    });
        task = new FutureTask<>(body);
        Thread.ofVirtual().start(task);
      }
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(pidFile) && System.nanoTime() < until) {
        Thread.sleep(5);
      }
      assertTrue(Files.exists(pidFile));
      assertFalse(gate.isIdle());
      assertTrue(gate.tryMaintenance().isEmpty());
      long child = Long.parseLong(Files.readString(pidFile));
      parser.close();
      assertEquals("parser_closed", task.get(3, TimeUnit.SECONDS));
      assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(gate.isIdle());
      try (var maintenance = gate.tryMaintenance().orElseThrow()) {
        assertTrue(OwnedTemporaryResources.verifyIdle(gate.managedRoot(), maintenance));
        assertTrue(OwnedTemporaryResources.sweep(gate.managedRoot(), maintenance));
      }
    }
  }

  @Test
  void successfulRealParsingRemovesItsSidecarOwnershipRecordBeforeMaintenance() throws Exception {
    var gate = new LibraryOperationGate(directory.toRealPath().resolve("work"));
    try (var parser = new ProcessTextParser(Duration.ofSeconds(10));
        var caller = gate.enter()) {
      assertEquals(
          "complete synthetic material",
          parser
              .parse(
                  "source.txt",
                  "text/plain",
                  "complete synthetic material".getBytes(StandardCharsets.UTF_8))
              .pages()
              .getFirst()
              .text());
    }
    assertTrue(gate.isIdle());
    try (var maintenance = gate.tryMaintenance().orElseThrow()) {
      assertTrue(OwnedTemporaryResources.verifyIdle(gate.managedRoot(), maintenance));
      try (var owners = Files.list(gate.managedRoot().resolve(".owners"))) {
        assertEquals(0, owners.count());
      }
    }
  }
}
