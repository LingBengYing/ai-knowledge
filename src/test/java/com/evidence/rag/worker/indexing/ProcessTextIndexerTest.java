package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.IndexingResult;
import com.evidence.rag.model.domain.VerifiedRevision;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessTextIndexerTest {
  @TempDir Path temporary;

  @Test
  void childHasFixedResourcesClearedEnvironmentPrivateDirectoryAndNoAuthorityToken()
      throws Exception {
    try (var server = new IndexingTestServer()) {
      Path directoryFile = temporary.resolve("directory.txt");
      try (var indexer =
          fixture(server, Duration.ofSeconds(10), "environment", directoryFile.toString())) {
        assertEquals(2, indexer.index(server.claim(2)).verified().segmentCount());
        assertFalse(Files.exists(Path.of(Files.readString(directoryFile))));
        assertTrue(server.requests.isEmpty(), "Synthetic result fixture does not call providers");
      }
    }
  }

  @Test
  void wholeDeadlineIncludesBlockedStdinAndConfirmsChildExit() throws Exception {
    try (var server = new IndexingTestServer()) {
      Path pid = temporary.resolve("deadline.pid");
      try (var indexer = fixture(server, Duration.ofMillis(700), "hang", pid.toString())) {
        long started = System.nanoTime();
        failure("indexing_timeout", () -> indexer.index(server.claim(4096)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4000);
        assertTrue(Files.exists(pid));
        assertDead(pid);
      }
      try (var next = fixture(server, Duration.ofSeconds(10), "success", "unused")) {
        assertEquals(1, next.index(server.claim(1)).verified().segmentCount());
      }
    }
  }

  @Test
  void onlyOneChildAcrossInstancesAndConcurrentCloseNeverLeaksThePermit() throws Exception {
    try (var server = new IndexingTestServer();
        var second = fixture(server, Duration.ofSeconds(10), "success", "unused")) {
      for (int attempt = 0; attempt < 3; attempt++) {
        Path pid = temporary.resolve("close-" + attempt + ".pid");
        var indexer = fixture(server, Duration.ofSeconds(10), "hang", pid.toString());
        var running = new FutureTask<>(() -> resultOf(indexer, server.claim(1)));
        Thread.ofVirtual().start(running);
        try {
          awaitPid(pid);
          failure("indexing_busy", () -> second.index(server.claim(1)));
          failure("indexing_busy", () -> indexer.index(server.claim(1)));
          var start = new CountDownLatch(1);
          var closers = new ArrayList<FutureTask<Void>>();
          for (int i = 0; i < 8; i++) {
            var closer =
                new FutureTask<Void>(
                    () -> {
                      start.await();
                      indexer.close();
                      return null;
                    });
            closers.add(closer);
            Thread.ofVirtual().start(closer);
          }
          start.countDown();
          for (var closer : closers) {
            closer.get(3, TimeUnit.SECONDS);
          }
          assertEquals("indexing_closed:false", running.get(3, TimeUnit.SECONDS));
          assertDead(pid);
          failure("indexing_closed", () -> indexer.index(server.claim(1)));
          assertEquals(1, second.index(server.claim(1)).verified().segmentCount());
        } finally {
          indexer.close();
        }
      }
    }
  }

  @Test
  void interruptionPreservesCallerFlagAndWaitsBeforeAnotherChildStarts() throws Exception {
    try (var server = new IndexingTestServer()) {
      Path pid = temporary.resolve("interrupt.pid");
      try (var indexer = fixture(server, Duration.ofSeconds(10), "hang", pid.toString())) {
        var result = new FutureTask<>(() -> resultOf(indexer, server.claim(1)));
        var caller = Thread.ofVirtual().start(result);
        awaitPid(pid);
        caller.interrupt();
        assertEquals("worker_interrupted:true", result.get(3, TimeUnit.SECONDS));
        assertDead(pid);
      }
      try (var next = fixture(server, Duration.ofSeconds(10), "success", "unused")) {
        Thread.currentThread().interrupt();
        try {
          failure("worker_interrupted", () -> next.index(server.claim(1)));
        } finally {
          assertTrue(Thread.interrupted());
        }
        assertEquals(1, next.index(server.claim(1)).verified().segmentCount());
      }
    }
  }

  @Test
  void invalidSettingsTargetsAndSnapshotsAreRejectedBeforeTheFixtureStarts() throws Exception {
    try (var server = new IndexingTestServer()) {
      for (Duration invalid :
          new Duration[] {null, Duration.ZERO, Duration.ofMillis(9), Duration.ofSeconds(601)}) {
        assertThrows(
            ProcessTextIndexer.Failure.class,
            () ->
                new ProcessTextIndexer(
                    server.settings().models(), server.settings().projection(), invalid));
      }
      assertThrows(
          ProcessTextIndexer.Failure.class,
          () -> new ProcessTextIndexer(null, null, Duration.ofSeconds(10)));
      assertThrows(
          ProcessTextIndexer.Failure.class,
          () ->
              new ProcessTextIndexer(
                  server.settings().models(),
                  server.settings().projection(),
                  Duration.ofSeconds(10),
                  "bad; class",
                  List.of()));
      Path pid = temporary.resolve("not-started.pid");
      try (var indexer = fixture(server, Duration.ofSeconds(10), "hang", pid.toString())) {
        var original = server.claim(2);
        var target = original.target();
        for (var bad :
            List.of(
                new IndexTarget(
                    "different",
                    target.projectionIdentity(),
                    target.modelRevision(),
                    target.dimensions()),
                new IndexTarget(
                    target.embeddingIdentity(),
                    "different",
                    target.modelRevision(),
                    target.dimensions()),
                new IndexTarget(
                    target.embeddingIdentity(),
                    target.projectionIdentity(),
                    "different",
                    target.dimensions()),
                new IndexTarget(
                    target.embeddingIdentity(),
                    target.projectionIdentity(),
                    target.modelRevision(),
                    3))) {
          failure(
              "indexing_output_invalid",
              () ->
                  indexer.index(
                      new IndexClaim(
                          original.jobId(),
                          original.documentId(),
                          original.revisionId(),
                          original.workspaceId(),
                          original.attempt(),
                          original.token(),
                          original.sourceSha256(),
                          original.parserRevision(),
                          bad,
                          original.items(),
                          original.projectionGenerationId())));
        }
        failure("indexing_output_invalid", () -> indexer.index(null));
        var reversed = original.items().reversed();
        failure(
            "indexing_output_invalid",
            () ->
                indexer.index(
                    new IndexClaim(
                        original.jobId(),
                        original.documentId(),
                        original.revisionId(),
                        original.workspaceId(),
                        original.attempt(),
                        original.token(),
                        original.sourceSha256(),
                        original.parserRevision(),
                        target,
                        reversed,
                        original.projectionGenerationId())));
        assertFalse(Files.exists(pid));
        assertTrue(server.requests.isEmpty());
      }
    }
  }

  @Test
  void everyMalformedResponseFailsAsAWholeAndNeverReturnsAPartialManifest() throws Exception {
    try (var server = new IndexingTestServer()) {
      var request =
          IndexProtocol.request(
              server.settings().models(),
              server.settings().projection(),
              Duration.ofSeconds(10),
              server.claim(2));
      var good = receipt(request);
      byte[] encoded = IndexProtocol.encode(good);
      var cases = new ArrayList<byte[]>();
      cases.add(integerAt(encoded, 0, 0));
      cases.add(integerAt(encoded, 4, IndexProtocol.VERSION + 1));
      cases.add(integerAt(encoded, 8, 3));
      cases.add(integerAt(encoded, 8, 1));
      cases.add(integerAt(encoded, 12, 0));
      cases.add(integerAt(encoded, 12, 4097));
      cases.add(integerAt(encoded, 16, -1));
      cases.add(integerAt(encoded, 16, 129));
      cases.add(Arrays.copyOf(encoded, encoded.length - 1));
      cases.add(Arrays.copyOf(encoded, encoded.length + 1));
      cases.add(integerAt(encoded, encoded.length - 4, 1));
      byte[] badUtf8 = encoded.clone();
      badUtf8[20] = (byte) 0xff;
      cases.add(badUtf8);
      cases.add(
          IndexProtocol.encode(
              new IndexingResult(
                  good.entryDigests(),
                  new VerifiedRevision("0".repeat(64), good.verified().manifestSha256(), 2))));
      cases.add(
          IndexProtocol.encode(
              new IndexingResult(
                  good.entryDigests(),
                  new VerifiedRevision(good.verified().projectionIdentity(), "0".repeat(64), 2))));
      var missing = new TreeMap<>(good.entryDigests());
      missing.remove(missing.firstKey());
      cases.add(IndexProtocol.encode(new IndexingResult(missing, good.verified())));
      var badHash = new TreeMap<>(good.entryDigests());
      badHash.put(badHash.firstKey(), "z".repeat(64));
      cases.add(IndexProtocol.encode(new IndexingResult(badHash, good.verified())));
      Path response = temporary.resolve("response.bin");
      for (byte[] invalid : cases) {
        Files.write(response, invalid);
        try (var indexer = fixture(server, Duration.ofSeconds(10), "output", response.toString())) {
          failure("indexing_output_invalid", () -> indexer.index(server.claim(2)));
        }
      }
      try (var indexer = fixture(server, Duration.ofSeconds(10), "oversize", "unused")) {
        failure("indexing_output_invalid", () -> indexer.index(server.claim(2)));
      }
      Files.write(response, encoded);
      try (var indexer = fixture(server, Duration.ofSeconds(10), "nonzero", response.toString())) {
        failure("indexing_failed", () -> indexer.index(server.claim(2)));
      }
    }
  }

  private static ProcessTextIndexer fixture(
      IndexingTestServer server, Duration timeout, String mode, String argument) {
    return new ProcessTextIndexer(
        server.settings().models(),
        server.settings().projection(),
        timeout,
        ProcessFixture.class.getName(),
        List.of(mode, argument));
  }

  private static String resultOf(ProcessTextIndexer indexer, IndexClaim claim) {
    try {
      indexer.index(claim);
      return "unexpected success";
    } catch (ProcessTextIndexer.Failure failure) {
      return failure.code() + ":" + Thread.currentThread().isInterrupted();
    }
  }

  private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(ProcessTextIndexer.Failure.class, operation);
    assertEquals(code, failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("synthetic"));
  }

  private static void awaitPid(Path pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      if (Files.exists(pid) && Files.readString(pid).matches("[1-9][0-9]*")) {
        return;
      }
      Thread.sleep(10);
    }
    fail("Child fixture did not publish a complete PID");
  }

  private static void assertDead(Path pid) throws Exception {
    assertFalse(
        ProcessHandle.of(Long.parseLong(Files.readString(pid)))
            .map(ProcessHandle::isAlive)
            .orElse(false));
  }

  private static byte[] integerAt(byte[] encoded, int offset, int value) {
    var result = encoded.clone();
    ByteBuffer.wrap(result).putInt(offset, value);
    return result;
  }

  private static IndexingResult receipt(IndexProtocol.Request request) {
    var digests = new TreeMap<String, String>();
    var vector = new ArrayList<Double>();
    for (int i = 0; i < request.target().dimensions(); i++) {
      vector.add(0.5);
    }
    for (var segment : request.items()) {
      String physicalId =
          RetrievalProjection.physicalSegmentId(
              request.projectionGenerationId(), segment.evidenceId());
      digests.put(
          physicalId,
          RetrievalProjection.entryDigest(
              new RetrievalProjection.Entry(
                  physicalId,
                  request.workspaceId(),
                  request.documentId(),
                  request.projectionGenerationId(),
                  segment.recallText(),
                  vector)));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            request.workspaceId(), request.documentId(), request.projectionGenerationId(), digests);
    return new IndexingResult(
        digests,
        new VerifiedRevision(
            request.target().projectionIdentity(), manifest.sha256(), digests.size()));
  }

  public static final class ProcessFixture {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("hang")) {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        new CountDownLatch(1).await();
        return;
      }
      byte[] input = System.in.readAllBytes();
      if (args[0].equals("environment")) {
        Map<String, String> environment = System.getenv();
        boolean mac = System.getProperty("os.name", "").startsWith("Mac");
        boolean safeEnvironment =
            environment.isEmpty()
                || mac
                    && environment.keySet().equals(Set.of("__CF_USER_TEXT_ENCODING"))
                    && environment
                        .get("__CF_USER_TEXT_ENCODING")
                        .matches(
                            "(?i)(?:0x)?[0-9a-f]{1,8}:(?:0x)?[0-9a-f]{1,8}:(?:0x)?[0-9a-f]{1,8}");
        if (!safeEnvironment
            || Runtime.getRuntime().maxMemory() > 256L * 1024 * 1024
            || Runtime.getRuntime().availableProcessors() != 1
            || !Path.of(System.getProperty("user.home"))
                .equals(Path.of(System.getProperty("java.io.tmpdir")))
            || !Path.of("")
                .toRealPath()
                .equals(Path.of(System.getProperty("user.home")).toRealPath())
            || new String(input, java.nio.charset.StandardCharsets.UTF_8)
                .contains("synthetic-claim-token")) {
          throw new IllegalStateException("Unsafe fixture process");
        }
        var arguments =
            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String flag :
            List.of(
                "-XX:MaxMetaspaceSize=96m",
                "-XX:MaxDirectMemorySize=32m",
                "-XX:+ExitOnOutOfMemoryError")) {
          if (!arguments.contains(flag)) {
            throw new IllegalStateException("Missing resource control");
          }
        }
        if (arguments.toString().contains("synthetic-model-credential")) {
          throw new IllegalStateException("Credential in argv");
        }
        Files.writeString(Path.of(args[1]), System.getProperty("user.home"));
        System.err.print("discarded synthetic diagnostic".repeat(50_000));
      }
      if (List.of("success", "environment").contains(args[0])) {
        System.out.write(
            IndexProtocol.encode(
                receipt(IndexProtocol.readRequest(new java.io.ByteArrayInputStream(input)))));
      } else if (args[0].equals("oversize")) {
        System.out.write(new byte[IndexProtocol.MAX_OUTPUT + 1]);
      } else {
        System.out.write(Files.readAllBytes(Path.of(args[1])));
      }
      System.out.flush();
      if (args[0].equals("nonzero")) {
        System.exit(7);
      }
    }
  }
}
