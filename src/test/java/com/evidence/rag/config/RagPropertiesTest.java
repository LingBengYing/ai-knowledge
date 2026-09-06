package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagPropertiesTest {
  @TempDir Path directory;

  @Test
  void modesAndSecretsFailClosed() {
    assertDoesNotThrow(
        () ->
            config("development", "workspace", "development_headers", null, null, null, directory));
    assertDoesNotThrow(
        () -> config("test", "workspace", "jwt", "a".repeat(32), "issuer", "audience", directory));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                "production", "workspace", "jwt", "a".repeat(32), "issuer", "audience", directory));
    assertThrows(
        IllegalArgumentException.class,
        () -> config(null, "workspace", "jwt", "a".repeat(32), "issuer", "audience", directory));
    assertThrows(
        IllegalArgumentException.class,
        () -> config("test", "workspace", "none", null, null, null, directory));
    for (String secret : new String[] {null, "short", "replace-" + "a".repeat(32)}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> config("test", "workspace", "jwt", secret, "issuer", "audience", directory));
    }
    for (String empty : new String[] {null, " "}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> config("test", "workspace", "jwt", "a".repeat(32), empty, "audience", directory));
      assertThrows(
          IllegalArgumentException.class,
          () -> config("test", "workspace", "jwt", "a".repeat(32), "issuer", empty, directory));
    }
  }

  @Test
  void boundsAndLegacyDatabaseAreProtected() throws Exception {
    for (String invalid : new String[] {null, " ", "a".repeat(201), "a\nb", "a\u007fb"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> config("test", invalid, "development_headers", null, null, null, directory));
      assertThrows(Problem.class, () -> new Actor(invalid, "principal"));
      assertThrows(Problem.class, () -> new Actor("workspace", invalid));
    }
    assertEquals(
        200, new Actor("😀".repeat(200), "principal").workspaceId().codePointCount(0, 400));
    assertThrows(
        IllegalArgumentException.class,
        () -> config("test", "workspace", "development_headers", null, null, null, null));
    Files.createFile(directory.resolve("rag.db"));
    assertThrows(
        IllegalArgumentException.class,
        () -> config("test", "workspace", "development_headers", null, null, null, directory));
  }

  private RagProperties config(
      String environment,
      String workspace,
      String mode,
      String secret,
      String issuer,
      String audience,
      Path dataDirectory) {
    return new RagProperties(environment, workspace, mode, secret, issuer, audience, dataDirectory);
  }
}
