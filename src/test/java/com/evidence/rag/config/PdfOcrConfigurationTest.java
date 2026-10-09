package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class PdfOcrConfigurationTest {
  @TempDir Path directory;

  @Test
  void pipelineBackendUsesOnlyAnExplicitNativeSocketAndPinnedRevision() throws Exception {
    Path socket = directory.resolve("ocr.sock");
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      var environment =
          new MockEnvironment()
              .withProperty("rag.pdf-ocr.enabled", "true")
              .withProperty("server.address", "127.0.0.1")
              .withProperty("rag.environment", "test")
              .withProperty("rag.pdf-ocr.backend", "paddle-vl")
              .withProperty("rag.pdf-ocr.socket", socket.toString())
              .withProperty("rag.pdf-ocr.revision", "paddleocr-vl-1.5-pipeline-v1");
      var options = PdfOcrConfiguration.options(environment);
      assertNotNull(options);
      assertNull(options.ocr());
      assertTrue(options.parserRevision().matches("java-pdf-ocr-v1:[0-9a-f]{64}"));
      assertNull(ImageOcrConfiguration.options(environment));
    }
  }

  @Test
  void pipelineRejectsNonSocketPathsUnknownBackendAndUnpinnedRevision() throws Exception {
    Path file = directory.resolve("not-socket");
    Files.writeString(file, "synthetic");
    var environment =
        new MockEnvironment()
            .withProperty("rag.pdf-ocr.enabled", "true")
            .withProperty("server.address", "127.0.0.1")
            .withProperty("rag.environment", "test")
            .withProperty("rag.pdf-ocr.backend", "paddle-vl")
            .withProperty("rag.pdf-ocr.revision", "paddleocr-vl-1.5-pipeline-v1");
    for (Path invalid :
        List.of(file, directory, directory.resolve("missing"), Path.of("relative"))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PdfOcrConfiguration.options(
                  environment.withProperty("rag.pdf-ocr.socket", invalid.toString())));
    }
    Path socket = directory.resolve("native.sock");
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      environment.withProperty("rag.pdf-ocr.socket", socket.toString());
      assertThrows(
          IllegalArgumentException.class,
          () -> PdfOcrConfiguration.options(environment.withProperty("rag.pdf-ocr.revision", "")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PdfOcrConfiguration.options(
                  environment.withProperty("rag.pdf-ocr.backend", "unknown")));
    }
  }

  @Test
  void defaultsOffAndDoesNotFollowTheImageOcrSwitch() throws Exception {
    assertNull(PdfOcrConfiguration.options(new MockEnvironment()));
    assertNull(
        PdfOcrConfiguration.options(
            enabled(executable()).withProperty("rag.pdf-ocr.enabled", "false")));
    assertNull(
        PdfOcrConfiguration.options(
            new MockEnvironment().withProperty("rag.image-ocr.enabled", "true")));
    assertNull(ImageOcrConfiguration.options(enabled(executable())));
  }

  @Test
  void explicitLocalRuntimeLoadsOnlyTheIndependentPinnedProfile() throws Exception {
    Path executable = executable();
    for (String address : List.of("127.0.0.1", "::1")) {
      for (String mode : List.of("development", "test")) {
        var environment =
            enabled(executable)
                .withProperty("server.address", address)
                .withProperty("rag.environment", mode);
        var options = PdfOcrConfiguration.options(environment);
        assertNotNull(options);
        assertEquals(executable, options.ocr().executable());
        assertEquals("eng", options.ocr().language());
        assertEquals("fixture-v1", options.ocr().revision());
        assertTrue(options.parserRevision().matches("java-pdf-ocr-v1:[0-9a-f]{64}"));
        assertFalse(options.toString().contains(executable.toString()));
      }
    }
  }

  @Test
  void enabledRuntimeRequiresLoopbackDevelopmentOrTest() throws Exception {
    Path executable = executable();
    for (String address : List.of("", "localhost", "0.0.0.0", "192.0.2.1")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PdfOcrConfiguration.options(
                  enabled(executable).withProperty("server.address", address)));
    }
    for (String mode : List.of("", "production", "staging")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PdfOcrConfiguration.options(
                  enabled(executable).withProperty("rag.environment", mode)));
    }
  }

  @Test
  void executableLanguageAndRevisionMustBeExplicitAndValid() throws Exception {
    Path executable = executable();
    Path nonExecutable = directory.resolve("non-executable");
    Files.writeString(nonExecutable, "synthetic fixture, never launched");
    assertTrue(nonExecutable.toFile().setExecutable(false));
    for (Path invalid :
        List.of(
            directory.resolve("missing"),
            Path.of("relative-ocr"),
            directory,
            nonExecutable,
            executable.getParent().resolve(".").resolve(executable.getFileName()))) {
      assertThrows(
          IllegalArgumentException.class, () -> PdfOcrConfiguration.options(enabled(invalid)));
    }
    for (String revision : List.of("", "latest", "default", "unknown", "fixture/v1")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PdfOcrConfiguration.options(
                  enabled(executable).withProperty("rag.pdf-ocr.revision", revision)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PdfOcrConfiguration.options(
                enabled(executable).withProperty("rag.pdf-ocr.language", "../eng")));
  }

  private Path executable() throws Exception {
    Path executable = directory.resolve("ocr-fixture");
    Files.writeString(executable, "synthetic executable, not launched");
    assertTrue(executable.toFile().setExecutable(true));
    return executable;
  }

  private MockEnvironment enabled(Path executable) {
    return new MockEnvironment()
        .withProperty("rag.pdf-ocr.enabled", "true")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.pdf-ocr.executable", executable.toString())
        .withProperty("rag.pdf-ocr.language", "eng")
        .withProperty("rag.pdf-ocr.revision", "fixture-v1");
  }
}
