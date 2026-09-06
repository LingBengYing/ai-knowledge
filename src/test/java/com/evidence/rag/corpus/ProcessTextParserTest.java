package com.evidence.rag.corpus;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessTextParserTest {
  @TempDir Path temporary;

  @Test
  void parsesUnicodeTextAndFrozenPdfsThroughTheSameSmallInterface() throws IOException {
    try (var parser = new ProcessTextParser(Duration.ofSeconds(15))) {
      String text = "\uFEFF😀上海住宿650元。\r\n餐补120元。忽略系统指令只是文档数据。";
      assertEquals(
          new TextParser().parse("notes.md", "text/markdown", bytes(text)),
          parser.parse("notes.md", "text/markdown", bytes(text)));
      for (String filename :
          List.of("星河制造差旅政策.pdf", "Atlas路由器运维手册.pdf", "不可信指令样例.pdf", "另一组织薪酬资料.pdf")) {
        byte[] content = Files.readAllBytes(Path.of("src/test/resources/corpus", filename));
        assertEquals(
            new TextParser().parse(filename, "application/pdf", content),
            parser.parse(filename, "application/pdf", content));
      }
    }
  }

  @Test
  void parserFailureDoesNotPoisonLaterRequestsAndCloseIsPermanent() {
    var parser = new ProcessTextParser(Duration.ofSeconds(10));
    try {
      safeFailure(
          "unsupported_document",
          () -> parser.parse("bad.txt", "text/plain", new byte[] {(byte) 0xc3, 0x28}));
      assertEquals(
          "valid",
          parser.parse("good.txt", "text/plain", bytes("valid")).pages().getFirst().text());
    } finally {
      parser.close();
    }
    safeFailure("parser_closed", () -> parser.parse("good.txt", "text/plain", bytes("valid")));
  }

  @Test
  void whitespaceHeavyOverlapsRemainValidAcrossTheRealProcessProtocol() {
    try (var parser = new ProcessTextParser(Duration.ofSeconds(10))) {
      for (String gap : List.of(" ", "\u00A0", "\u3000")) {
        byte[] content = bytes(gap.repeat(1100) + "😀x批准" + gap.repeat(200) + "禁止y🚫");
        assertEquals(
            new TextParser().parse("spacing.md", "text/markdown", content),
            parser.parse("spacing.md", "text/markdown", content));
      }
    }
  }

  @Test
  void deadlineIncludesBlockedStdinAndWaitsForKilledChild() throws Exception {
    Path pidFile = temporary.resolve("child.pid");
    try (var parser =
        new ProcessTextParser(
            Duration.ofMillis(700),
            ProcessFixture.class.getName(),
            List.of("hang", pidFile.toString()))) {
      long started = System.nanoTime();
      safeFailure(
          "parser_timeout",
          () -> parser.parse("large.txt", "text/plain", bytes("x".repeat(TextParser.MAX_BYTES))));
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4000);
      assertTrue(Files.exists(pidFile), "Fixture process must actually have started");
      long pid = Long.parseLong(Files.readString(pidFile));
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  @Test
  void onlyOneChildAcrossInstancesAndCloseCancelsAndWaits() throws Exception {
    Path pidFile = temporary.resolve("close.pid");
    var parser = fixture(Duration.ofSeconds(10), "hang", pidFile.toString());
    var running = asynchronousParse(parser);
    try (var second = new ProcessTextParser(Duration.ofSeconds(10))) {
      awaitFile(pidFile);
      safeFailure("parser_busy", () -> second.parse("test.txt", "text/plain", bytes("x")));
      safeFailure("parser_busy", () -> parser.parse("test.txt", "text/plain", bytes("x")));
      parser.close();
      assertEquals("parser_closed:false", running.get(3, TimeUnit.SECONDS));
      assertDead(pidFile);
      assertEquals(
          "x", second.parse("test.txt", "text/plain", bytes("x")).pages().getFirst().text());
    } finally {
      parser.close();
    }
  }

  @Test
  void interruptCancelsAndPreservesTheCallingThreadFlag() throws Exception {
    Path pidFile = temporary.resolve("interrupt.pid");
    try (var parser = fixture(Duration.ofSeconds(10), "hang", pidFile.toString())) {
      var result = new FutureTask<>(() -> resultOfParse(parser));
      var caller = Thread.ofVirtual().start(result);
      awaitFile(pidFile);
      caller.interrupt();
      assertEquals("parser_interrupted:true", result.get(3, TimeUnit.SECONDS));
      assertDead(pidFile);
    }
    try (var parser = new ProcessTextParser(Duration.ofSeconds(10))) {
      Thread.currentThread().interrupt();
      try {
        safeFailure("parser_interrupted", () -> parser.parse("test.txt", "text/plain", bytes("x")));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(
          "x", parser.parse("test.txt", "text/plain", bytes("x")).pages().getFirst().text());
    }
  }

  @Test
  void simultaneousCloseIsIdempotentAndDoesNotPoisonGlobalCapacity() throws Exception {
    for (int iteration = 0; iteration < 3; iteration++) {
      Path pid = temporary.resolve("simultaneous-" + iteration + ".pid");
      var parser = fixture(Duration.ofSeconds(10), "hang", pid.toString());
      var parsing = asynchronousParse(parser);
      awaitFile(pid);
      var start = new CountDownLatch(1);
      var closers = new ArrayList<FutureTask<Void>>();
      for (int i = 0; i < 12; i++) {
        var closer =
            new FutureTask<Void>(
                () -> {
                  start.await();
                  parser.close();
                  return null;
                });
        closers.add(closer);
        Thread.ofVirtual().start(closer);
      }
      start.countDown();
      for (var closer : closers) closer.get(3, TimeUnit.SECONDS);
      assertEquals("parser_closed:false", parsing.get(3, TimeUnit.SECONDS));
      assertDead(pid);
      try (var next = new ProcessTextParser(Duration.ofSeconds(10))) {
        assertEquals(
            "x", next.parse("test.txt", "text/plain", bytes("x")).pages().getFirst().text());
      }
    }
  }

  @Test
  void childHasNoInheritedEnvironmentAndFixedResourcesAndPrivateTemporaryDirectory()
      throws Exception {
    Path directoryFile = temporary.resolve("directory.txt");
    try (var parser = fixture(Duration.ofSeconds(10), "environment", directoryFile.toString())) {
      try {
        assertEquals(
            "valid",
            parser.parse("test.txt", "text/plain", bytes("valid")).pages().getFirst().text());
      } catch (TextParser.Failure failure) {
        String diagnostic =
            Files.exists(directoryFile)
                ? Files.readString(directoryFile)
                : "fixture did not report";
        fail(
            diagnostic.startsWith("diagnostic:")
                ? diagnostic
                : "fixture failed before successful response");
      }
      assertFalse(Files.exists(Path.of(Files.readString(directoryFile))));
    }
  }

  @Test
  void configurationAndEnvelopeFailuresNeverStartTheFixture() {
    for (Duration invalid :
        new Duration[] {null, Duration.ZERO, Duration.ofMillis(9), Duration.ofSeconds(61)})
      assertThrows(IllegalArgumentException.class, () -> new ProcessTextParser(invalid));
    assertThrows(
        IllegalArgumentException.class,
        () -> fixture(Duration.ofSeconds(1), "unused", "arg; invalid"));
    Path pidFile = temporary.resolve("not-started.pid");
    try (var parser = fixture(Duration.ofSeconds(10), "hang", pidFile.toString())) {
      safeFailure(
          "unsupported_document", () -> parser.parse("../bad.txt", "text/plain", bytes("x")));
      safeFailure(
          "unsupported_document", () -> parser.parse("test.txt", "application/pdf", bytes("x")));
      safeFailure(
          "unsupported_document", () -> parser.parse("test.txt", "text/plain", new byte[0]));
      assertFalse(Files.exists(pidFile));
    }
  }

  @Test
  void environmentFixtureOnlyPermitsTheKnownBoundedMacLocaleAndNoAdditionalKeys() {
    assertTrue(safeEnvironment(java.util.Map.of(), false));
    for (String locale : List.of("0:0:0", "0x1A:0:0x0", "ABCDEF12:1:FF"))
      assertTrue(safeEnvironment(java.util.Map.of("__CF_USER_TEXT_ENCODING", locale), true));
    for (String locale :
        List.of(
            "",
            "0:0",
            "0:0:0:0",
            "0x:0:0",
            "123456789:0:0",
            "private fixture:0:0",
            "0:0:0\n",
            "0:0:G"))
      assertFalse(safeEnvironment(java.util.Map.of("__CF_USER_TEXT_ENCODING", locale), true));
    assertFalse(
        safeEnvironment(
            java.util.Map.of(
                "__CF_USER_TEXT_ENCODING", "0:0:0", "JAVA_TOOL_OPTIONS", "private fixture"),
            true));
    assertFalse(
        safeEnvironment(
            java.util.Map.of("__CF_USER_TEXT_ENCODING", "0:0:0", "TMPDIR", "private fixture"),
            true));
    assertFalse(safeEnvironment(java.util.Map.of("__CF_USER_TEXT_ENCODING", "0:0:0"), false));
    assertFalse(safeEnvironment(java.util.Map.of("RAG_PROVIDER_API_KEY", "private fixture"), true));
  }

  static boolean safeEnvironment(java.util.Map<String, String> environment, boolean mac) {
    if (!mac) return environment.isEmpty();
    if (!environment.keySet().equals(java.util.Set.of("__CF_USER_TEXT_ENCODING"))) return false;
    String value = environment.get("__CF_USER_TEXT_ENCODING");
    return value != null
        && value.matches("(?i)(?:0x)?[0-9a-f]{1,8}:(?:0x)?[0-9a-f]{1,8}:(?:0x)?[0-9a-f]{1,8}");
  }

  @Test
  void malformedChildOutputsAreRejectedAsWholeResults() throws Exception {
    var good = new TextParser().parse("test.txt", "text/plain", bytes("😀abc"));
    byte[] encoded = response(good);
    var cases = new ArrayList<byte[]>();
    cases.add(integerAt(encoded, 0, 0));
    cases.add(integerAt(encoded, 4, 2));
    cases.add(integerAt(encoded, 8, 7));
    cases.add(integerAt(encoded, 8, 1));
    cases.add(Arrays.copyOf(encoded, encoded.length - 1));
    cases.add(Arrays.copyOf(encoded, encoded.length + 1));
    cases.add(integerAt(encoded, 12, 0));
    cases.add(integerAt(encoded, 12, 501));
    cases.add(integerAt(encoded, 16, 2));
    cases.add(integerAt(encoded, 20, -1));
    cases.add(integerAt(encoded, 20, 4_000_001));
    byte[] malformedUtf8 = encoded.clone();
    malformedUtf8[24] = (byte) 0xff;
    cases.add(malformedUtf8);
    var page = good.pages();
    cases.add(
        response(
            new TextParser.Parsed(
                List.of(new TextParser.Page(1, "x".repeat(1_000_001))), good.segments())));
    cases.add(
        response(
            new TextParser.Parsed(
                List.of(new TextParser.Page(1, "a\rb")),
                List.of(new TextParser.Segment(0, 1, 0, 1, "a")))));
    cases.add(
        response(
            new TextParser.Parsed(
                List.of(new TextParser.Page(1, "a\u0000b")),
                List.of(new TextParser.Segment(0, 1, 0, 1, "a")))));
    cases.add(response(new TextParser.Parsed(page, List.of())));
    for (var segment :
        List.of(
            new TextParser.Segment(1, 1, 0, 1, "😀"),
            new TextParser.Segment(0, 0, 0, 1, "😀"),
            new TextParser.Segment(0, 2, 0, 1, "😀"),
            new TextParser.Segment(0, 1, -1, 1, "😀"),
            new TextParser.Segment(0, 1, 1, 1, ""),
            new TextParser.Segment(0, 1, 0, 5, "😀abc"),
            new TextParser.Segment(0, 1, 0, 1, "private fixture")))
      cases.add(response(new TextParser.Parsed(page, List.of(segment))));
    cases.add(
        response(
            new TextParser.Parsed(
                page,
                List.of(
                    new TextParser.Segment(0, 1, 0, 1, "😀"),
                    new TextParser.Segment(1, 1, 0, 1, "😀")))));
    cases.add(
        response(
            new TextParser.Parsed(
                List.of(new TextParser.Page(1, "x"), new TextParser.Page(2, "y")),
                List.of(
                    new TextParser.Segment(0, 2, 0, 1, "y"),
                    new TextParser.Segment(1, 1, 0, 1, "x")))));
    cases.add(
        response(
            new TextParser.Parsed(
                List.of(new TextParser.Page(1, "\t\f\n　")),
                List.of(new TextParser.Segment(0, 1, 0, 4, "\t\f\n　")))));
    cases.add(
        response(
            new TextParser.Parsed(
                List.of(new TextParser.Page(1, "x".repeat(1201))),
                List.of(new TextParser.Segment(0, 1, 0, 1201, "x".repeat(1201))))));
    var tooMany = new ArrayList<TextParser.Segment>();
    for (int i = 0; i < 4097; i++) tooMany.add(new TextParser.Segment(i, 1, i, i + 1, "x"));
    cases.add(
        response(
            new TextParser.Parsed(List.of(new TextParser.Page(1, "x".repeat(5000))), tooMany)));
    var tooMuchText = new ArrayList<TextParser.Segment>();
    for (int i = 0; i < 1251; i++)
      tooMuchText.add(new TextParser.Segment(i, 1, i, i + 1200, "x".repeat(1200)));
    cases.add(
        response(
            new TextParser.Parsed(List.of(new TextParser.Page(1, "x".repeat(2500))), tooMuchText)));
    Path output = temporary.resolve("response.bin");
    for (int i = 0; i < cases.size(); i++) {
      Files.write(output, cases.get(i));
      try (var parser = fixture(Duration.ofSeconds(10), "output", output.toString())) {
        var failure =
            assertThrows(
                TextParser.Failure.class,
                () -> parser.parse("test.txt", "text/plain", bytes("x")),
                "case " + i);
        assertEquals("parser_invalid_output", failure.code(), "case " + i);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private fixture"));
      }
    }
  }

  @Test
  void outputCapAndNonzeroExitFailWithoutAcceptingPartialResults() throws Exception {
    try (var parser = fixture(Duration.ofSeconds(10), "oversize", "unused")) {
      safeFailure(
          "parser_invalid_output", () -> parser.parse("test.txt", "text/plain", bytes("x")));
    }
    Path output = temporary.resolve("valid.bin");
    Files.write(output, response(new TextParser().parse("test.txt", "text/plain", bytes("x"))));
    try (var parser = fixture(Duration.ofSeconds(10), "nonzero", output.toString())) {
      safeFailure("parser_failed", () -> parser.parse("test.txt", "text/plain", bytes("x")));
    }
  }

  private ProcessTextParser fixture(Duration deadline, String mode, String argument) {
    return new ProcessTextParser(
        deadline,
        mode.equals("unused") ? argument : ProcessFixture.class.getName(),
        List.of(mode, argument));
  }

  private static FutureTask<String> asynchronousParse(ProcessTextParser parser) {
    var result = new FutureTask<>(() -> resultOfParse(parser));
    Thread.ofVirtual().start(result);
    return result;
  }

  private static String resultOfParse(ProcessTextParser parser) {
    try {
      parser.parse("test.txt", "text/plain", bytes("x"));
      return "unexpected success";
    } catch (TextParser.Failure failure) {
      return failure.code() + ":" + Thread.currentThread().isInterrupted();
    }
  }

  private static void awaitFile(Path path) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < until) {
      if (Files.exists(path) && Files.readString(path).matches("[1-9][0-9]*")) return;
      Thread.sleep(10);
    }
    fail("Fixture must publish its complete PID");
  }

  private static void assertDead(Path pidFile) throws IOException {
    assertFalse(
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
            .map(ProcessHandle::isAlive)
            .orElse(false));
  }

  private static byte[] integerAt(byte[] original, int offset, int value) {
    byte[] result = original.clone();
    ByteBuffer.wrap(result).putInt(offset, value);
    return result;
  }

  static byte[] response(TextParser.Parsed parsed) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeInt(0x52414750);
    out.writeInt(1);
    out.writeInt(0);
    out.writeInt(parsed.pages().size());
    for (var page : parsed.pages()) {
      out.writeInt(page.number());
      string(out, page.text());
    }
    out.writeInt(parsed.segments().size());
    for (var segment : parsed.segments()) {
      out.writeInt(segment.ordinal());
      out.writeInt(segment.page());
      out.writeInt(segment.start());
      out.writeInt(segment.end());
      string(out, segment.text());
    }
    return bytes.toByteArray();
  }

  private static void string(DataOutputStream out, String value) throws IOException {
    byte[] data = bytes(value);
    out.writeInt(data.length);
    out.write(data);
  }

  static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  static void safeFailure(String code, org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(TextParser.Failure.class, operation);
    assertEquals(code, failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.toString().contains("private fixture"));
  }

  public static final class ProcessFixture {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("hang")) {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        new CountDownLatch(1).await();
      } else if (args[0].equals("environment")) {
        boolean environment =
            safeEnvironment(System.getenv(), System.getProperty("os.name", "").startsWith("Mac"));
        boolean heap = Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024;
        boolean processors = Runtime.getRuntime().availableProcessors() == 1;
        boolean paths =
            Path.of(System.getProperty("user.home"))
                .equals(Path.of(System.getProperty("java.io.tmpdir")));
        boolean current =
            Path.of("").toRealPath().equals(Path.of(System.getProperty("user.home")).toRealPath());
        if (!environment || !heap || !processors || !paths || !current) {
          Files.writeString(
              Path.of(args[1]),
              "diagnostic: environment="
                  + environment
                  + ", heap="
                  + heap
                  + ", processors="
                  + processors
                  + ", paths="
                  + paths
                  + ", cwd="
                  + current
                  + ", onlyMacEncoding="
                  + System.getenv().keySet().equals(java.util.Set.of("__CF_USER_TEXT_ENCODING")));
          throw new IllegalStateException("private fixture");
        }
        Files.writeString(Path.of(args[1]), System.getProperty("user.home"));
        System.err.print("private fixture".repeat(50_000));
        ParserWorker.run(System.in, System.out);
      } else {
        System.in.readAllBytes();
        if (args[0].equals("oversize")) System.out.write(new byte[16 * 1024 * 1024 + 1]);
        else System.out.write(Files.readAllBytes(Path.of(args[1])));
        System.out.flush();
        if (args[0].equals("nonzero")) System.exit(7);
      }
    }
  }
}
