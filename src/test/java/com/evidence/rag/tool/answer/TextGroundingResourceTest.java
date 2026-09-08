package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Real bounded subprocess regression: a slow verifier must not leave a CPU-consuming test thread.
 */
class TextGroundingResourceTest {
  private static final long TOTAL_BUDGET_NANOS = Duration.ofSeconds(10).toNanos();
  private static final long CLEANUP_RESERVE_NANOS = Duration.ofSeconds(2).toNanos();
  @TempDir Path directory;

  @Test
  void manyUnassignedConnectorsBeforeAnExactFactFinishWithinTheProcessBudget() throws Exception {
    assertNotNull(directory, "The resource probe requires a fresh temporary directory");
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertTrue(classpath != null && !classpath.isBlank(), "The current test classpath is required");
    String absoluteClasspath =
        String.join(
            File.pathSeparator,
            Arrays.stream(classpath.split(File.pathSeparator))
                .map(value -> Path.of(value).toAbsolutePath().normalize().toString())
                .toList());
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                absoluteClasspath,
                ConnectorProbe.class.getName())
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    long deadline = System.nanoTime() + TOTAL_BUDGET_NANOS;
    Process child = builder.start();
    try {
      child.getOutputStream().close();
      long remaining = deadline - CLEANUP_RESERVE_NANOS - System.nanoTime();
      assertTrue(
          remaining > 0 && child.waitFor(remaining, TimeUnit.NANOSECONDS),
          "Grounding the legal page must finish before the reserved process-cleanup budget");
      assertEquals(
          0, child.exitValue(), "The probe must preserve the supported fact and exact locator");
      byte[] output = child.getInputStream().readNBytes(128);
      assertTrue(output.length < 128, "The probe output is limited to a safe verdict and timing");
      assertTrue(
          new String(output, StandardCharsets.UTF_8).matches("supported elapsed_ms=[0-9]{1,6}\\R"),
          "The probe must report only its safe success verdict and elapsed time");
    } finally {
      try {
        stopAndConfirmExit(child, deadline);
      } finally {
        child.getInputStream().close();
        child.getErrorStream().close();
        child.getOutputStream().close();
      }
    }
  }

  @Test
  void interruptedGroundingPreservesTheFlagAndDoesNotReturnPartialProof() {
    String fact = "项目预算为470万元";
    var candidate = TextGroundingTest.evidence("interrupted-resource", fact, fact);
    boolean wasInterrupted = Thread.interrupted();
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          CancellationException.class,
          () ->
              new TextGrounding()
                  .verify(
                      "项目预算是多少？",
                      List.of(candidate),
                      List.of(new GroundingQuote(candidate.physicalSegmentId(), fact))));
      assertTrue(
          Thread.currentThread().isInterrupted(), "Cancellation must preserve the interrupt flag");
    } finally {
      Thread.interrupted();
      if (wasInterrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @ParameterizedTest
  @MethodSource("connectorPrefixes")
  void connectorAssignmentRangesRetainWordBoundariesAndNumericColonRules(
      String prefix, boolean expectedSupported) {
    String fact = "项目预算为470万元";
    var candidate = TextGroundingTest.evidence("connector-boundary", prefix + fact, fact);
    var result =
        new TextGrounding()
            .verify(
                "项目预算是多少？",
                List.of(candidate),
                List.of(new GroundingQuote(candidate.physicalSegmentId(), fact)));
    assertEquals(expectedSupported, result.supported());
    if (expectedSupported) {
      assertEquals("supported", result.reason());
      assertEquals(1, result.quotes().size());
      assertEquals(fact, result.quotes().getFirst().quote());
      assertEquals(prefix.codePointCount(0, prefix.length()), result.quotes().getFirst().start());
      assertEquals(
          (prefix + fact).codePointCount(0, (prefix + fact).length()),
          result.quotes().getFirst().end());
    } else {
      assertEquals("incomplete_evidence", result.reason());
      assertTrue(result.quotes().isEmpty());
    }
  }

  static Stream<Arguments> connectorPrefixes() {
    return Stream.of(
        Arguments.of("Notes is同时", true),
        Arguments.of("😀Notes ARE同时", true),
        Arguments.of("条目为甲同时is 7然后", true),
        Arguments.of("Notes this同时", false),
        Arguments.of("10:30同时", false),
        Arguments.of("备注：有效同时", true));
  }

  private static void stopAndConfirmExit(Process child, long deadline) {
    boolean interrupted = Thread.interrupted();
    try {
      if (child.isAlive()) {
        child.destroyForcibly();
      }
      while (child.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          break;
        }
        try {
          child.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      assertFalse(child.isAlive(), "The bounded resource probe must be confirmed terminated");
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static final class ConnectorProbe {
    public static void main(String[] args) {
      long started = System.nanoTime();
      boolean supported = false;
      try {
        String fact = "项目预算为470万元";
        String page = "同时".repeat(200000) + "\n" + fact;
        var candidate = TextGroundingTest.evidence("resource", page, fact);
        var result =
            new TextGrounding()
                .verify(
                    "项目预算是多少？",
                    List.of(candidate),
                    List.of(new GroundingQuote(candidate.physicalSegmentId(), fact)));
        if (result.supported()
            && "supported".equals(result.reason())
            && result.quotes().size() == 1) {
          var quote = result.quotes().getFirst();
          supported =
              fact.equals(quote.quote())
                  && candidate.physicalSegmentId().equals(quote.physicalId())
                  && quote.start() == 400001
                  && quote.end() == 400011
                  && quote.factHashes().size() == 1
                  && quote.factHashes().getFirst().matches("[a-f0-9]{64}");
        }
      } catch (Throwable failure) {
        // A child failure is a nonzero safe verdict, never raw evidence or an exception dump.
        supported = false;
      }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      System.out.println((supported ? "supported" : "failed") + " elapsed_ms=" + elapsed);
      System.exit(supported ? 0 : 1);
    }
  }
}
