package com.evidence.rag.tool.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.GroundingQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keeps the original connector regression unchanged while exercising a different public query. */
class TextGroundingProcedureResourceTest {
  @TempDir Path directory;

  @Test
  void anUnquotedLongProcedureIsRefusedWithinTheIsolatedProcessBudget() throws Exception {
    assertNotNull(directory);
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertTrue(classpath != null && !classpath.isBlank());
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
                ProcedureProbe.class.getName())
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    Process child = builder.start();
    try {
      child.getOutputStream().close();
      long remaining = deadline - Duration.ofSeconds(2).toNanos() - System.nanoTime();
      assertTrue(
          remaining > 0 && child.waitFor(remaining, TimeUnit.NANOSECONDS),
          "The legal procedure must be refused before the reserved process-cleanup budget");
      assertEquals(0, child.exitValue(), "The probe must return incomplete evidence, not a crash");
      byte[] output = child.getInputStream().readNBytes(128);
      assertTrue(output.length < 128);
      assertTrue(
          new String(output, StandardCharsets.UTF_8)
              .matches("incomplete_evidence elapsed_ms=[0-9]{1,6}\\R"));
    } finally {
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
        assertFalse(child.isAlive(), "The probe must be confirmed terminated");
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        child.getInputStream().close();
        child.getErrorStream().close();
        child.getOutputStream().close();
      }
    }
  }

  public static final class ProcedureProbe {
    public static void main(String[] args) {
      long started = System.nanoTime();
      boolean refused = false;
      try {
        String first = "清理日志：点击清理按钮";
        String page = first + "。\n" + "然后点击清理按钮。\n".repeat(50_000);
        var source = TextGroundingTest.evidence("procedure-resource", page, first);
        var result =
            new TextGrounding()
                .verify(
                    "清理日志如何操作？",
                    List.of(source),
                    List.of(new GroundingQuote(source.physicalSegmentId(), first)));
        refused =
            !result.supported()
                && "incomplete_evidence".equals(result.reason())
                && result.quotes().isEmpty();
      } catch (Throwable failure) {
        // The child emits only a safe verdict; failures cannot become a successful refusal.
        refused = false;
      }
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      System.out.println((refused ? "incomplete_evidence" : "failed") + " elapsed_ms=" + elapsed);
      System.exit(refused ? 0 : 1);
    }
  }
}
