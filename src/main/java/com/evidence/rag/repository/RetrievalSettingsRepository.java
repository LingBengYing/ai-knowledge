package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Atomic workspace settings storage owned by the single-writer application. */
public final class RetrievalSettingsRepository {
  private static final int MAX_BYTES = 16 * 1024;
  private static final String FORMAT = "java-retrieval-settings-v1";
  private static final Set<String> FIELDS =
      Set.of(
          "version",
          "search_method",
          "ranking_mode",
          "dense_weight",
          "top_k",
          "score_threshold_enabled",
          "score_threshold");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_BYTES)
                          .maxNestingDepth(5)
                          .maxStringLength(MAX_BYTES)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Path file;
  private final String workspaceId;

  public RetrievalSettingsRepository(Path file, String workspaceId) {
    this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
    this.workspaceId = ModelValues.identifier(workspaceId, 200);
  }

  public RetrievalSettings read() {
    synchronized (RetrievalSettingsRepository.class) {
      return readFile();
    }
  }

  public RetrievalSettings save(RetrievalSettings requested) {
    Objects.requireNonNull(requested);
    // The authority store owns the process lock; this monitor also serializes distinct local
    // repository instances so each compare-and-swap covers the entire atomic replacement.
    synchronized (RetrievalSettingsRepository.class) {
      var current = readFile();
      if (requested.version() != current.version()
          || current.version() == RetrievalSettings.MAX_VERSION) {
        throw new ApplicationException(
            FailureKind.CONFLICT, "retrieval_settings_conflict", "检索设置已更新，请读取最新版本后再保存。");
      }
      var saved =
          new RetrievalSettings(
              current.version() + 1,
              requested.searchMethod(),
              requested.rankingMode(),
              requested.denseWeight(),
              requested.topK(),
              requested.scoreThresholdEnabled(),
              requested.scoreThreshold());
      writeFile(saved);
      return saved;
    }
  }

  private RetrievalSettings readFile() {
    try {
      checkParent();
      if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
        return RetrievalSettings.defaults();
      }
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
        throw unavailable();
      }
      byte[] bytes;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES) {
        throw unavailable();
      }
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var root = JSON.readTree(text);
      exact(root, Set.of("format", "workspace_id", "settings"));
      if (!FORMAT.equals(string(root.path("format")))
          || !workspaceId.equals(string(root.path("workspace_id")))) {
        throw unavailable();
      }
      var settings = root.path("settings");
      exact(settings, FIELDS);
      long topK = integer(settings.path("top_k"));
      if (topK < 1 || topK > 20 || !settings.path("score_threshold_enabled").isBoolean()) {
        throw unavailable();
      }
      return new RetrievalSettings(
          integer(settings.path("version")),
          string(settings.path("search_method")),
          string(settings.path("ranking_mode")),
          number(settings.path("dense_weight")),
          (int) topK,
          settings.path("score_threshold_enabled").booleanValue(),
          number(settings.path("score_threshold")));
    } catch (IOException | RuntimeException invalid) {
      throw unavailable();
    }
  }

  private void writeFile(RetrievalSettings settings) {
    Path temporary = null;
    try {
      checkParent();
      Files.createDirectories(
          file.getParent(),
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      checkParent();
      var values = new LinkedHashMap<String, Object>();
      values.put("version", settings.version());
      values.put("search_method", settings.searchMethod());
      values.put("ranking_mode", settings.rankingMode());
      values.put("dense_weight", settings.denseWeight());
      values.put("top_k", settings.topK());
      values.put("score_threshold_enabled", settings.scoreThresholdEnabled());
      values.put("score_threshold", settings.scoreThreshold());
      byte[] bytes =
          JSON.writeValueAsBytes(
              Map.of("format", FORMAT, "workspace_id", workspaceId, "settings", values));
      temporary =
          Files.createTempFile(
              file.getParent(),
              ".retrieval-settings-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      try (var channel =
          FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException invalid) {
      throw unavailable();
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // Never remove or truncate the live configuration if temporary-file cleanup fails.
        }
      }
    }
  }

  private void checkParent() {
    var parent = file.getParent();
    if (parent == null
        || Files.isSymbolicLink(parent)
        || (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))) {
      throw unavailable();
    }
  }

  private static void exact(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields)) {
      throw unavailable();
    }
  }

  private static String string(JsonNode node) {
    if (!node.isString()) {
      throw unavailable();
    }
    return node.stringValue();
  }

  private static long integer(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw unavailable();
    }
    return node.longValue();
  }

  private static double number(JsonNode node) {
    if (!node.isNumber() || !Double.isFinite(node.doubleValue())) {
      throw unavailable();
    }
    return node.doubleValue();
  }

  private static ApplicationException unavailable() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "retrieval_settings_unavailable", "检索设置暂不可用；原配置文件保持不变。");
  }
}
