package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessAudioVectorIndexerTest {
  @TempDir Path directory;

  @Test
  void exactReceiptReturnsThroughResourceBoundedClearedEnvironmentChild() throws Exception {
    var request = AudioVectorProtocolTest.request();
    Path privateDirectory = directory.resolve("directory.txt");
    try (var process = fixture(Duration.ofSeconds(10), "environment", privateDirectory)) {
      var receipt = process.index(request.claim());
      assertEquals(AudioVectorProtocolTest.receipt(request), receipt);
      assertFalse(Files.exists(Path.of(Files.readString(privateDirectory))));
    }
  }

  @Test
  void timeoutIncludesBlockedStdinAndWaitsForChildTermination() throws Exception {
    Path pid = directory.resolve("timeout.pid");
    try (var process = fixture(Duration.ofMillis(700), "hang", pid)) {
      assertEquals(
          "audio_vector_timeout",
          assertThrows(
                  ProcessAudioVectorIndexer.Failure.class,
                  () -> process.index(AudioVectorProtocolTest.request().claim()))
              .code());
      assertDead(pid);
    }
  }

  @Test
  void closeKillsAnActiveChildAndPreventsReuse() throws Exception {
    Path pid = directory.resolve("close.pid");
    var process = fixture(Duration.ofSeconds(10), "hang", pid);
    var result = new FutureTask<>(() -> result(process));
    Thread.ofVirtual().start(result);
    try {
      awaitPid(pid);
      process.close();
      assertEquals("audio_vector_closed:false", result.get(3, TimeUnit.SECONDS));
      assertDead(pid);
      assertEquals(
          "audio_vector_closed",
          assertThrows(
                  ProcessAudioVectorIndexer.Failure.class,
                  () -> process.index(AudioVectorProtocolTest.request().claim()))
              .code());
    } finally {
      process.close();
    }
  }

  @Test
  void interruptionPreservesCallerFlagAndConfirmsChildExit() throws Exception {
    Path pid = directory.resolve("interrupt.pid");
    try (var process = fixture(Duration.ofSeconds(10), "hang", pid)) {
      var result = new FutureTask<>(() -> result(process));
      var caller = Thread.ofVirtual().start(result);
      awaitPid(pid);
      caller.interrupt();
      assertEquals("audio_vector_interrupted:true", result.get(3, TimeUnit.SECONDS));
      assertDead(pid);
    }
  }

  @Test
  void malformedOutputIsRejectedAsAWholeAndNeverExposesDiagnostics() {
    try (var process = fixture(Duration.ofSeconds(10), "oversize", directory.resolve("unused"))) {
      var failure =
          assertThrows(
              ProcessAudioVectorIndexer.Failure.class,
              () -> process.index(AudioVectorProtocolTest.request().claim()));
      assertEquals("audio_vector_output_invalid", failure.code());
      assertNull(failure.getCause());
      assertFalse(failure.toString().contains("fixture-key"));
    }
  }

  @Test
  void applicationWorkerEntryReturnsOnlyTheBinaryProtocolWithoutStartingSpring() {
    var request = AudioVectorProtocolTest.request();
    try (var process =
        new ProcessAudioVectorIndexer(
            request.models(),
            request.projection(),
            Duration.ofSeconds(5),
            RagApplication.class.getName(),
            List.of("--audio-vector-worker"))) {
      assertEquals(
          "audio_vector_unavailable",
          assertThrows(
                  ProcessAudioVectorIndexer.Failure.class, () -> process.index(request.claim()))
              .code());
    }
  }

  @Test
  void workerRejectsWrongParentStartBeforeAnyRemoteCall() throws Exception {
    var request = AudioVectorProtocolTest.request();
    var forged =
        new AudioVectorProtocol.Request(
            request.models(),
            request.projection(),
            request.timeout(),
            request.claim(),
            new IndexWorkerLifetime.Parent(ProcessHandle.current().pid(), Instant.EPOCH));
    var bytes = new ByteArrayOutputStream();
    AudioVectorProtocol.writeRequest(bytes, forged);
    var output = new ByteArrayOutputStream();
    AudioVectorWorker.run(new ByteArrayInputStream(bytes.toByteArray()), output);
    assertEquals(
        "audio_vector_unavailable",
        assertThrows(
                ProcessAudioVectorIndexer.Failure.class,
                () -> AudioVectorProtocol.decode(output.toByteArray(), request))
            .code());
    assertFalse(Thread.currentThread().isInterrupted());
  }

  private static ProcessAudioVectorIndexer fixture(Duration timeout, String mode, Path path) {
    var request = AudioVectorProtocolTest.request();
    return new ProcessAudioVectorIndexer(
        request.models(),
        request.projection(),
        timeout,
        Fixture.class.getName(),
        List.of(mode, path.toString()));
  }

  private static String result(ProcessAudioVectorIndexer process) {
    try {
      process.index(AudioVectorProtocolTest.request().claim());
      return "unexpected";
    } catch (ProcessAudioVectorIndexer.Failure failure) {
      return failure.code() + ":" + Thread.currentThread().isInterrupted();
    }
  }

  private static void awaitPid(Path pid) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < until) {
      if (Files.exists(pid) && Files.readString(pid).matches("[1-9][0-9]*")) {
        return;
      }
      Thread.sleep(10);
    }
    fail("Child did not record complete PID");
  }

  private static void assertDead(Path pid) throws Exception {
    assertTrue(Files.exists(pid));
    assertFalse(
        ProcessHandle.of(Long.parseLong(Files.readString(pid)))
            .map(ProcessHandle::isAlive)
            .orElse(false));
  }

  public static final class Fixture {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("hang")) {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        new CountDownLatch(1).await();
        return;
      }
      var request = AudioVectorProtocol.readRequest(System.in);
      if (args[0].equals("environment")) {
        var environment = System.getenv();
        boolean safe =
            environment.isEmpty()
                || System.getProperty("os.name", "").startsWith("Mac")
                    && environment.keySet().equals(Set.of("__CF_USER_TEXT_ENCODING"))
                    && environment
                        .get("__CF_USER_TEXT_ENCODING")
                        .matches(
                            "(?i)(?:0x)?[0-9a-f]{1,8}:(?:0x)?[0-9a-f]{1,8}:(?:0x)?[0-9a-f]{1,8}");
        if (!safe
            || Runtime.getRuntime().maxMemory() > 256L * 1024 * 1024
            || Runtime.getRuntime().availableProcessors() != 1
            || !Path.of(System.getProperty("user.home"))
                .equals(Path.of(System.getProperty("java.io.tmpdir")))
            || !Path.of("")
                .toRealPath()
                .equals(Path.of(System.getProperty("user.home")).toRealPath())
            || ManagementFactory.getRuntimeMXBean()
                .getInputArguments()
                .toString()
                .contains("fixture-key")) {
          throw new IllegalStateException("Unsafe child");
        }
        Files.writeString(Path.of(args[1]), System.getProperty("user.home"));
        System.err.print("discarded".repeat(10000));
      }
      System.out.write(
          args[0].equals("oversize")
              ? new byte[AudioVectorProtocol.MAX_OUTPUT + 1]
              : AudioVectorProtocol.encode(AudioVectorProtocolTest.receipt(request)));
      System.out.flush();
    }
  }
}
