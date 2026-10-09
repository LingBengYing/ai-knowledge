package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real PDF worker protocol and parent lifetime. Only the controlled child receives the existing
 * JaCoCo agent; the application keeps its unchanged clean-environment JVM resource model.
 */
class PdfWorkerLifetimeProtocolTest {
  @TempDir Path directory;

  @Test
  void validParentReturnsCompleteLocatorsAndStopsBeforeTheCoverageSessionIsAppended()
      throws Exception {
    var parsed = ParserProtocol.decode(run(validArguments(), true));
    assertEquals(2, parsed.pages().size());
    assertEquals(2, parsed.segments().size());
    assertTrue(parsed.pages().stream().allMatch(page -> page.text().equals("budget650\n")));
    assertEquals(1, parsed.segments().getFirst().page());
    assertEquals(2, parsed.segments().getLast().page());
    assertEquals(0, parsed.segments().getLast().start());
    assertEquals(9, parsed.segments().getLast().end());
    assertEquals("2", Files.readString(directory.resolve("calls")));
    assertDead(directory.resolve("ocr.pid"));
    assertDead(directory.resolve("worker.pid"));
  }

  @Test
  void invalidLifecycleArgumentsAndParentIdentityFailBeforeAnyNativeOcrIsStarted()
      throws Exception {
    var valid = validArguments();
    var cases = new ArrayList<List<String>>();
    cases.add(List.of("--pdf-ocr"));
    cases.add(replace(valid, 0, "--unknown-mode"));
    cases.add(replace(valid, 4, "0"));
    cases.add(replace(valid, 7, "9"));
    cases.add(replace(valid, 7, "300001"));
    cases.add(replace(valid, 4, Long.toString(ProcessHandle.current().pid() + 1)));
    int nanos = Integer.parseInt(valid.get(6));
    cases.add(replace(valid, 6, Integer.toString((nanos + 1) % 1_000_000_000)));
    for (var arguments : cases) {
      assertArrayEquals(ParserProtocol.failure(), run(arguments, false));
      assertFalse(Files.exists(directory.resolve("calls")));
      assertFalse(Files.exists(directory.resolve("ocr.pid")));
    }
  }

  @Test
  void measurementRejectsCoverageDestinationsOutsideTheConfiguredBuildDirectory() {
    assertThrows(AssertionError.class, () -> measurement(directory.toString()));
  }

  private byte[] run(List<String> arguments, boolean request) throws Exception {
    var measurement = measurement();
    long prefixSize =
        Files.exists(measurement.destination()) ? Files.size(measurement.destination()) : 0;
    assertTrue(prefixSize < 64L * 1024 * 1024, "Bounded execution data fixture");
    byte[] prefix = prefix(measurement.destination(), (int) prefixSize);
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-XX:ActiveProcessorCount=1",
                measurement.argument(),
                "-cp",
                classpath(),
                ParserWorker.class.getName()));
    command.addAll(arguments);
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().clear();
    var child = builder.start();
    try {
      try (var input = child.getOutputStream()) {
        if (request) {
          ParserProtocol.writeRequest(
              input, "synthetic.pdf", "application/pdf", PdfOcrCompilerTest.pdf(2, false, null));
        }
      }
      var output =
          new FutureTask<>(() -> child.getInputStream().readNBytes(ParserProtocol.MAX_OUTPUT + 1));
      Thread.ofVirtual().name("instrumented-pdf-response").start(output);
      byte[] response = output.get(8, TimeUnit.SECONDS);
      assertTrue(
          child.waitFor(3, TimeUnit.SECONDS), "Worker must complete before the next exec append");
      assertEquals(0, child.exitValue());
      assertTrue(response.length <= ParserProtocol.MAX_OUTPUT);
      // This Surefire JVM dumps only on final exit. Each isolated worker has completely exited
      // before another worker appends; no production JVM option or environment is inherited.
      assertTrue(
          Files.size(measurement.destination()) > prefixSize,
          "Child coverage must actually be recorded");
      assertArrayEquals(
          prefix,
          prefix(measurement.destination(), (int) prefixSize),
          "JaCoCo append must preserve all earlier execution sessions");
      return response;
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(3, TimeUnit.SECONDS)) {
          child.descendants().forEach(ProcessHandle::destroyForcibly);
          child.destroyForcibly();
          assertTrue(child.waitFor(3, TimeUnit.SECONDS));
        }
      }
      child.getInputStream().close();
      child.getOutputStream().close();
      child.getErrorStream().close();
    }
  }

  private List<String> validArguments() throws Exception {
    Path executable = directory.resolve("ocr-executable");
    Files.writeString(
        executable,
        "#!/bin/sh\n[ \"$7\" = tsv ] || exit 2\nexec "
            + quote(Path.of(System.getProperty("java.home"), "bin", "java").toString())
            + " -Xmx96m -cp "
            + quote(classpath())
            + " "
            + quote(ProcessPdfOcrTest.OcrFixture.class.getName())
            + " "
            + quote(directory.toString())
            + " valid\n");
    assertTrue(executable.toFile().setExecutable(true));
    var parent = ProcessHandle.current();
    var started = parent.info().startInstant().orElseThrow();
    return List.of(
        "--pdf-ocr",
        executable.toString(),
        "eng",
        "controlled-pdf-v1",
        Long.toString(parent.pid()),
        Long.toString(started.getEpochSecond()),
        Integer.toString(started.getNano()),
        "5000");
  }

  private static Measurement measurement() {
    return measurement(System.getProperty("rag.test.build.directory"));
  }

  private static Measurement measurement(String configuredBuildDirectory) {
    assertNotNull(configuredBuildDirectory, "Surefire must supply the Maven build directory");
    assertFalse(configuredBuildDirectory.isBlank());
    Path buildDirectory = Path.of(configuredBuildDirectory);
    assertTrue(buildDirectory.isAbsolute(), "Maven build directory must be absolute");
    var agents =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(
                argument ->
                    argument.startsWith("-javaagent:")
                        && argument.contains("org.jacoco.agent")
                        && argument.contains("-runtime.jar="))
            .toList();
    assertEquals(1, agents.size(), "This fixture requires the Maven-configured JaCoCo agent");
    String original = agents.getFirst();
    int split = original.indexOf('=');
    String path = original.substring("-javaagent:".length(), split);
    assertTrue(Files.isRegularFile(Path.of(path)));
    Map<String, String> options =
        Arrays.stream(original.substring(split + 1).split(","))
            .map(option -> option.split("=", 2))
            .collect(Collectors.toMap(option -> option[0], option -> option[1]));
    assertEquals("true", options.getOrDefault("append", "true"));
    assertEquals("true", options.getOrDefault("dumponexit", "true"));
    assertEquals("file", options.getOrDefault("output", "file"));
    Path destination = Path.of(options.get("destfile")).toAbsolutePath().normalize();
    assertEquals(buildDirectory.resolve("jacoco.exec").normalize(), destination);
    // Retain only this explicitly verified agent, with append made explicit. No other JVM
    // arguments from the Surefire process are copied to the parser JVM.
    String argument = original;
    if (!options.containsKey("append")) {
      argument += ",append=true";
    }
    return new Measurement(argument, destination);
  }

  private record Measurement(String argument, Path destination) {}

  private static byte[] prefix(Path file, int bytes) throws Exception {
    if (bytes == 0) {
      return MessageDigest.getInstance("SHA-256").digest(new byte[0]);
    }
    try (var input = Files.newInputStream(file)) {
      byte[] content = input.readNBytes(bytes);
      assertEquals(bytes, content.length);
      return MessageDigest.getInstance("SHA-256").digest(content);
    }
  }

  private static List<String> replace(List<String> values, int index, String replacement) {
    var copy = new ArrayList<>(values);
    copy.set(index, replacement);
    return List.copyOf(copy);
  }

  private static String quote(String text) {
    return "'" + text.replace("'", "'\"'\"'") + "'";
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

  private static void assertDead(Path file) throws Exception {
    long pid = Long.parseLong(Files.readString(file));
    long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (System.nanoTime() < until
        && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
      Thread.sleep(10);
    }
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
  }
}
