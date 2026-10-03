package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.TextParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessImageOcrTest {
  private static final ImageOcrOptions OPTIONS =
      new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "5.5.3");

  @Test
  void freezesConfiguredRevisionAndSharesValidWordlessOutputWithStrictLegacyParsing()
      throws Exception {
    var image = image();
    try (var parser = parser("empty", image, Duration.ofSeconds(5))) {
      ImageOcr ocr = parser;
      assertEquals(OPTIONS.parserRevision(), ocr.revision());
      assertTrue(ocr.read(image).isEmpty());
      assertEquals(
          "parser_invalid_output",
          assertThrows(
                  TextParser.Failure.class,
                  () -> parser.parse("frame.png", image.mediaType(), image.content()))
              .code());
      assertTrue(ocr.read(image).isEmpty());
    }
  }

  @Test
  void sendsTheOriginalBytesAndReturnsIdenticalNonemptyOutputThroughBothInterfaces()
      throws Exception {
    var image = image();
    try (var parser = parser("words", image, Duration.ofSeconds(5))) {
      var read = parser.read(image).orElseThrow();
      assertEquals("Budget 42\n", read.text().pages().getFirst().text());
      assertEquals(parser.parse("frame.png", image.mediaType(), image.content()), read);
      assertEquals(2, read.regions().size());
    }
  }

  @Test
  void malformedChildOutputIsFailureRatherThanBlankAndReleasesCapacity() throws Exception {
    var image = image();
    try (var parser = parser("truncated", image, Duration.ofSeconds(5))) {
      assertEquals(
          "parser_invalid_output",
          assertThrows(TextParser.Failure.class, () -> parser.read(image)).code());
    }
    try (var parser = parser("empty", image, Duration.ofSeconds(5))) {
      assertTrue(parser.read(image).isEmpty());
    }
  }

  @Test
  void timeoutConfirmsChildCleanupBeforeAnotherOcrCallCanStart() throws Exception {
    var image = image();
    try (var parser = parser("sleep", image, Duration.ofMillis(200))) {
      assertEquals(
          "parser_timeout",
          assertThrows(TextParser.Failure.class, () -> parser.read(image)).code());
    }
    try (var parser = parser("words", image, Duration.ofSeconds(5))) {
      assertTrue(parser.read(image).isPresent());
    }
  }

  @Test
  void closeCancelsARealChildAndBothEntryPointsShareAdmission(@TempDir Path directory)
      throws Exception {
    var image = image();
    Path marker = directory.resolve("started.pid");
    var parser =
        new ProcessImageParser(
            OPTIONS,
            Duration.ofSeconds(5),
            OcrFixture.class.getName(),
            List.of("sleep", image.sha256(), marker.toString()));
    var running = new FutureTask<>(() -> parser.read(image));
    Thread.ofVirtual().start(running);
    try {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!Files.exists(marker) && !running.isDone() && System.nanoTime() < until) {
        Thread.sleep(10);
      }
      assertTrue(Files.exists(marker), "The real child must have started before cancellation");
      long pid = Long.parseLong(Files.readString(marker));
      assertEquals(
          "parser_busy",
          assertThrows(
                  TextParser.Failure.class,
                  () -> parser.parse("frame.png", image.mediaType(), image.content()))
              .code());
      parser.close();
      var failed = assertThrows(ExecutionException.class, () -> running.get(3, TimeUnit.SECONDS));
      assertEquals("parser_closed", ((TextParser.Failure) failed.getCause()).code());
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
      assertEquals(
          "parser_closed", assertThrows(TextParser.Failure.class, () -> parser.read(image)).code());
    } finally {
      parser.close();
    }
    try (var next = parser("empty", image, Duration.ofSeconds(5))) {
      assertTrue(next.read(image).isEmpty());
    }
  }

  private static ProcessImageParser parser(String mode, VisualImage image, Duration deadline) {
    return new ProcessImageParser(
        OPTIONS, deadline, OcrFixture.class.getName(), List.of(mode, image.sha256()));
  }

  private static VisualImage image() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(
          ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", output));
    }
    return new VisualImage("image/png", bytes.toByteArray());
  }

  /** Real process fixture for byte/lifecycle contracts, not a Tesseract quality claim. */
  public static final class OcrFixture {
    public static void main(String[] args) throws Exception {
      byte[] bytes = System.in.readNBytes(10 * 1024 * 1024 + 1);
      if (!new VisualImage("image/png", bytes).sha256().equals(args[1])
          || System.getenv().keySet().stream()
              .anyMatch(key -> !key.equals("__CF_USER_TEXT_ENCODING"))) {
        System.exit(3);
      }
      if (args.length == 3) {
        Path marker = Path.of(args[2]);
        Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
        Files.writeString(pending, Long.toString(ProcessHandle.current().pid()));
        Files.move(pending, marker, StandardCopyOption.ATOMIC_MOVE);
      }
      if (args[0].equals("sleep")) {
        Thread.sleep(30_000);
      }
      String tsv =
          "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
              + "1\t1\t0\t0\t0\t0\t0\t0\t100\t80\t-1\t\n";
      if (args[0].equals("words")) {
        tsv +=
            "5\t1\t1\t1\t1\t1\t2\t3\t40\t10\t99\tBudget\n"
                + "5\t1\t1\t1\t1\t2\t50\t3\t20\t10\t99\t42\n";
      }
      if (args[0].equals("truncated")) {
        tsv = tsv.substring(0, tsv.length() - 1);
      }
      System.out.write(tsv.getBytes(StandardCharsets.UTF_8));
    }
  }
}
