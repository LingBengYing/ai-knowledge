package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IndexWorkerLifetimeTest {
  @Test
  void reusedPidWithDifferentStartInstantNeverGetsAWriterLease() throws Exception {
    try (var server = new IndexingTestServer()) {
      var current = IndexWorkerLifetime.Parent.current();
      var forged = new IndexWorkerLifetime.Parent(current.pid(), current.started().minusSeconds(1));
      assertFalse(forged.alive());
      var original =
          IndexProtocol.request(
              server.settings().models(),
              server.settings().projection(),
              Duration.ofSeconds(5),
              server.claim(1));
      var request =
          new IndexProtocol.Request(
              original.models(),
              original.projection(),
              original.timeout(),
              original.workspaceId(),
              original.documentId(),
              original.revisionId(),
              original.target(),
              original.items(),
              original.projectionGenerationId(),
              forged);
      var input = new ByteArrayOutputStream();
      IndexProtocol.writeRequest(input, request);
      var output = new ByteArrayOutputStream();
      IndexWorker.run(new ByteArrayInputStream(input.toByteArray()), output);
      assertArrayEquals(IndexProtocol.failure(1), output.toByteArray());
      assertTrue(server.requests.isEmpty());
      assertTrue(
          ProcessHandle.current().isAlive(), "The ordinary run Interface never halts its host JVM");
    }
  }

  @Test
  void leaseAdmissionWaitIsBoundedAndClosingReleasesOnlyItsOwnLock() throws Exception {
    try (var server = new IndexingTestServer()) {
      var parent = IndexWorkerLifetime.Parent.current();
      try (var first =
          IndexWorkerLifetime.acquire(
              server.settings().projection(), parent, Duration.ofSeconds(5), false)) {
        var waiting =
            new FutureTask<>(
                () -> {
                  try (var acquired =
                      IndexWorkerLifetime.acquire(
                          server.settings().projection(), parent, Duration.ofMillis(100), false)) {
                    acquired.check();
                    return "unexpected";
                  } catch (ProcessTextIndexer.Failure failure) {
                    return failure.code();
                  }
                });
        Thread.ofVirtual().start(waiting);
        assertEquals("indexing_timeout", waiting.get(2, TimeUnit.SECONDS));
        first.check();
      }
      try (var next =
          IndexWorkerLifetime.acquire(
              server.settings().projection(), parent, Duration.ofSeconds(5), false)) {
        next.check();
      }
      assertTrue(server.requests.isEmpty());
    }
  }

  @Test
  void timedOutSameJvmWaiterCannotReleaseHolderLockToAnotherJvm() throws Exception {
    try (var server = new IndexingTestServer()) {
      var parent = IndexWorkerLifetime.Parent.current();
      try (var holder =
          IndexWorkerLifetime.acquire(
              server.settings().projection(), parent, Duration.ofSeconds(5), false)) {
        assertEquals(
            "indexing_timeout",
            independentJvmProbe(server),
            "Control probe must observe the original OS lock before the second channel exists");
        var waiter =
            new FutureTask<>(
                () -> {
                  try (var acquired =
                      IndexWorkerLifetime.acquire(
                          server.settings().projection(), parent, Duration.ofMillis(100), false)) {
                    acquired.check();
                    return "acquired";
                  } catch (ProcessTextIndexer.Failure failure) {
                    return failure.code();
                  }
                });
        Thread.ofVirtual().start(waiter);
        assertEquals("indexing_timeout", waiter.get(2, TimeUnit.SECONDS));
        holder.check();
        assertEquals(
            "indexing_timeout",
            independentJvmProbe(server),
            "Closing the timed-out same-JVM waiter must not release the live holder's OS lock");
      }
      assertEquals(
          "acquired",
          independentJvmProbe(server),
          "Only closing the holder permits another JVM to enter");
    }
  }

  private static String independentJvmProbe(IndexingTestServer server) throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    String absolute =
        String.join(
            File.pathSeparator,
            Arrays.stream(classpath.split(File.pathSeparator))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString())
                .toList());
    var builder =
        new ProcessBuilder(
                List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx128m",
                    "-cp",
                    absolute,
                    LeaseProbe.class.getName()))
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    Process probe = builder.start();
    try {
      try (var input = probe.getOutputStream()) {
        IndexProtocol.writeRequest(
            input,
            IndexProtocol.request(
                server.settings().models(),
                server.settings().projection(),
                Duration.ofMillis(350),
                server.claim(1)));
      }
      assertTrue(
          probe.waitFor(3, TimeUnit.SECONDS),
          "Independent lease probe must terminate within its bounded deadline");
      assertEquals(0, probe.exitValue());
      byte[] output = probe.getInputStream().readNBytes(128);
      assertTrue(output.length < 128);
      return new String(output, StandardCharsets.UTF_8);
    } finally {
      if (probe.isAlive()) {
        probe.destroyForcibly();
      }
      assertTrue(probe.waitFor(3, TimeUnit.SECONDS));
      probe.getInputStream().close();
      probe.getErrorStream().close();
    }
  }

  @Test
  void repeatedCloseCannotReleaseThePermitHeldByTheNextLifetime() throws Exception {
    try (var server = new IndexingTestServer()) {
      var parent = IndexWorkerLifetime.Parent.current();
      var previous =
          IndexWorkerLifetime.acquire(
              server.settings().projection(), parent, Duration.ofSeconds(5), false);
      previous.close();
      try (var current =
          IndexWorkerLifetime.acquire(
              server.settings().projection(), parent, Duration.ofSeconds(5), false)) {
        previous.close();
        var waiting =
            new FutureTask<>(
                () -> {
                  try (var acquired =
                      IndexWorkerLifetime.acquire(
                          server.settings().projection(), parent, Duration.ofMillis(100), false)) {
                    acquired.check();
                    return "acquired";
                  } catch (ProcessTextIndexer.Failure failure) {
                    return failure.code();
                  }
                });
        Thread.ofVirtual().start(waiting);
        assertEquals("indexing_timeout", waiting.get(2, TimeUnit.SECONDS));
        current.check();
        assertEquals("indexing_timeout", independentJvmProbe(server));
      }
      assertEquals("acquired", independentJvmProbe(server));
    }
  }

  public static final class LeaseProbe {
    public static void main(String[] args) throws Exception {
      var request = IndexProtocol.readRequest(System.in);
      String result;
      try (var acquired =
          IndexWorkerLifetime.acquire(
              request.projection(), request.parent(), request.timeout(), false)) {
        acquired.check();
        result = "acquired";
      } catch (ProcessTextIndexer.Failure failure) {
        result = failure.code();
      }
      System.out.print(result);
      System.out.flush();
    }
  }
}
