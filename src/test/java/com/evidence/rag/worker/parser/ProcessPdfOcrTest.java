package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.tool.parser.TextParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real PDF worker/native child lifecycle; an explicitly controlled OCR protocol process. */
class ProcessPdfOcrTest {
  @TempDir Path directory;

  @Test
  void everyPdfPageUsesTheNativeChildWhileTxtAndNullOptionsRetainTheOldProtocol() throws Exception {
    var options = options("valid");
    try (var parser = new ProcessTextParser(Duration.ofSeconds(10), options)) {
      var parsed =
          parser.parse("synthetic.pdf", "application/pdf", PdfOcrCompilerTest.pdf(3, false, null));
      assertEquals(3, parsed.pages().size());
      assertEquals(3, parsed.segments().size());
      assertTrue(parsed.pages().stream().allMatch(page -> page.text().equals("budget650\n")));
      assertEquals("3", Files.readString(directory.resolve("calls")));
      byte[] text = "😀旧文字650。\r\n".getBytes(StandardCharsets.UTF_8);
      assertEquals(
          new TextParser().parse("notes.md", "text/markdown", text),
          parser.parse("notes.md", "text/markdown", text));
      assertEquals("3", Files.readString(directory.resolve("calls")));
      try (var original = new ProcessTextParser(Duration.ofSeconds(10), null)) {
        assertEquals(
            new TextParser().parse("notes.md", "text/markdown", text),
            original.parse("notes.md", "text/markdown", text));
      }
    }
  }

  @Test
  void closeConfirmsBothPdfJvmAndNativeGrandchildExitAndReleasesSharedAdmission() throws Exception {
    var parser = new ProcessTextParser(Duration.ofSeconds(15), options("hang"));
    var running = asynchronous(parser);
    try {
      awaitFile(directory.resolve("ocr.pid"));
      try (var other = new ProcessTextParser(Duration.ofSeconds(10))) {
        var busy =
            assertThrows(
                TextParser.Failure.class,
                () -> other.parse("x.txt", "text/plain", new byte[] {65}));
        assertEquals("parser_busy", busy.code());
        parser.close();
        assertEquals("parser_closed:false", running.get(6, TimeUnit.SECONDS));
        assertDead(directory.resolve("ocr.pid"));
        assertDead(directory.resolve("worker.pid"));
        assertEquals(
            "A", other.parse("x.txt", "text/plain", new byte[] {65}).pages().getFirst().text());
      }
    } finally {
      parser.close();
    }
  }

  @Test
  void overallDeadlineStopsTheNativeGrandchildWithoutAcceptingPartialEvidence() throws Exception {
    try (var parser = new ProcessTextParser(Duration.ofMillis(1500), options("hang"))) {
      long started = System.nanoTime();
      var failure =
          assertThrows(
              TextParser.Failure.class,
              () ->
                  parser.parse("x.pdf", "application/pdf", PdfOcrCompilerTest.pdf(1, false, null)));
      assertEquals("parser_timeout", failure.code());
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 7000);
      assertDead(directory.resolve("ocr.pid"));
      assertDead(directory.resolve("worker.pid"));
    }
  }

  @Test
  void interruptionPreservesCallerFlagAndConfirmsGrandchildExit() throws Exception {
    var parser = new ProcessTextParser(Duration.ofSeconds(15), options("hang"));
    try {
      var result = new FutureTask<>(() -> parseResult(parser));
      var caller = Thread.ofVirtual().start(result);
      awaitFile(directory.resolve("ocr.pid"));
      caller.interrupt();
      assertEquals("parser_interrupted:true", result.get(6, TimeUnit.SECONDS));
      assertDead(directory.resolve("ocr.pid"));
      assertDead(directory.resolve("worker.pid"));
    } finally {
      parser.close();
    }
  }

  @Test
  void abruptApplicationParentExitMakesPdfWatchdogStopItsNativeChild() throws Exception {
    var options = options("hang");
    var command =
        new ArrayList<>(
            List.of(
                java(),
                "-Xmx128m",
                "-cp",
                classpath(),
                ParentFixture.class.getName(),
                options.ocr().executable().toString()));
    var builder =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    var parent = builder.start();
    try {
      awaitFile(directory.resolve("ocr.pid"));
      long worker = Long.parseLong(Files.readString(directory.resolve("worker.pid")));
      long nativePid = Long.parseLong(Files.readString(directory.resolve("ocr.pid")));
      assertTrue(ProcessHandle.of(worker).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(ProcessHandle.of(nativePid).map(ProcessHandle::isAlive).orElse(false));
      parent.destroyForcibly();
      assertTrue(parent.waitFor(3, TimeUnit.SECONDS));
      assertDead(directory.resolve("ocr.pid"));
      assertDead(directory.resolve("worker.pid"));
    } finally {
      parent.destroyForcibly();
      parent.waitFor();
      for (String name : List.of("ocr.pid", "worker.pid")) {
        if (Files.exists(directory.resolve(name))) {
          ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve(name))))
              .ifPresent(ProcessHandle::destroyForcibly);
        }
      }
    }
  }

  private FutureTask<String> asynchronous(ProcessTextParser parser) {
    var result = new FutureTask<>(() -> parseResult(parser));
    Thread.ofVirtual().start(result);
    return result;
  }

  private static String parseResult(ProcessTextParser parser) throws Exception {
    try {
      parser.parse("synthetic.pdf", "application/pdf", PdfOcrCompilerTest.pdf(1, false, null));
      return "unexpected-success";
    } catch (TextParser.Failure failure) {
      return failure.code() + ":" + Thread.currentThread().isInterrupted();
    }
  }

  private PdfOcrOptions options(String mode) throws Exception {
    Path executable = directory.resolve("ocr-executable");
    Files.writeString(
        executable,
        "#!/bin/sh\n[ \"$7\" = tsv ] || exit 2\nexec "
            + quote(java())
            + " -Xmx96m -cp "
            + quote(classpath())
            + " "
            + quote(OcrFixture.class.getName())
            + " "
            + quote(directory.toString())
            + " "
            + quote(mode)
            + "\n");
    assertTrue(executable.toFile().setExecutable(true));
    return new PdfOcrOptions(new ImageOcrOptions(executable, "eng", "controlled-pdf-v1"));
  }

  private static String quote(String text) {
    return "'" + text.replace("'", "'\"'\"'") + "'";
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String classpath() {
    return String.join(
        File.pathSeparator,
        Arrays.stream(
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"))
                    .split(File.pathSeparator, -1))
            .map(
                value ->
                    Path.of(value.isEmpty() ? "." : value).toAbsolutePath().normalize().toString())
            .toList());
  }

  private static void awaitFile(Path file) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < until) {
      if (Files.exists(file) && Files.readString(file).matches("[0-9]+")) {
        return;
      }
      Thread.sleep(10);
    }
    fail("Controlled native process did not start");
  }

  private static void assertDead(Path file) throws Exception {
    awaitFile(file);
    long pid = Long.parseLong(Files.readString(file));
    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < until
        && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
      Thread.sleep(10);
    }
    assertFalse(
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "Process must actually exit: " + file.getFileName());
  }

  public static final class ParentFixture {
    public static void main(String[] args) throws Exception {
      var options =
          new PdfOcrOptions(new ImageOcrOptions(Path.of(args[0]), "eng", "controlled-pdf-v1"));
      try (var parser = new ProcessTextParser(Duration.ofSeconds(60), options)) {
        parser.parse("synthetic.pdf", "application/pdf", PdfOcrCompilerTest.pdf(1, false, null));
      }
    }
  }

  public static final class OcrFixture {
    public static void main(String[] args) throws Exception {
      Path directory = Path.of(args[0]);
      Files.writeString(
          directory.resolve("worker.pid"),
          Long.toString(ProcessHandle.current().parent().orElseThrow().pid()));
      Files.writeString(directory.resolve("ocr.pid"), Long.toString(ProcessHandle.current().pid()));
      if (args[1].equals("hang")) {
        Thread.sleep(60000);
        return;
      }
      var image = ImageIO.read(System.in);
      int width = image.getWidth(), height = image.getHeight();
      Path counter = directory.resolve("calls");
      int calls = Files.exists(counter) ? Integer.parseInt(Files.readString(counter)) : 0;
      Files.writeString(counter, Integer.toString(calls + 1));
      System.out.print(
          "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n1\t1\t0\t0\t0\t0\t0\t0\t"
              + width
              + "\t"
              + height
              + "\t-1\t\n5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t99\tbudget650\n");
      System.out.flush();
    }
  }
}
