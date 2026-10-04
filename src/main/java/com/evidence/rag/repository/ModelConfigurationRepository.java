package com.evidence.rag.repository;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelConfigurationState;
import com.evidence.rag.model.domain.ModelValues;
import java.time.Instant;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Private configuration is separate from the authority database and every database snapshot. */
public final class ModelConfigurationRepository implements AutoCloseable {
  private static final int MAX_BYTES = 131072;
  private static final Set<PosixFilePermission> DIRECTORY_MODE =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(4096)
                          .maxNumberLength(20)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final Path file;
  private final SqliteAuthorityStore store;
  private FileChannel lockChannel;
  private FileLock lock;
  private boolean unavailable;

  public ModelConfigurationRepository(Path privateFile) {
    this(privateFile, null);
  }

  public ModelConfigurationRepository(Path privateFile, SqliteAuthorityStore store) {
    this.store = store;
    Path selected;
    try {
      Path requested = privateFile.toAbsolutePath().normalize();
      Path parent = requested.getParent();
      if (Files.isSymbolicLink(parent)) {
        throw failure();
      }
      Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
      parent = parent.toRealPath();
      check(parent, true);
      selected = parent.resolve(requested.getFileName());
      Path lockPath = parent.resolve(requested.getFileName() + ".lock");
      if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
        check(lockPath, false);
      }
      lockChannel =
          FileChannel.open(
              lockPath,
              Set.of(
                  StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(FILE_MODE));
      check(lockPath, false);
      lock = lockChannel.tryLock();
      if (lock == null) {
        throw failure();
      }
    } catch (IOException | RuntimeException invalid) {
      close();
      throw failure();
    }
    file = selected;
  }

  private ModelConfigurationState readPrivate() {
    requireOpen();
    try {
      check(file.getParent(), true);
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        if (lockChannel.size() != 0) {
          throw failure();
        }
        return ModelConfigurationState.empty();
      }
      check(file, false);
      if (Files.size(file) > MAX_BYTES) {
        throw failure();
      }
      byte[] bytes;
      try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        var buffer = ByteBuffer.allocate(MAX_BYTES + 1);
        while (input.read(buffer) > 0) {
          if (!buffer.hasRemaining()) {
            throw failure();
          }
        }
        bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
      }
      var root = JSON.readTree(bytes);
      String format = text(root.path("format"));
      boolean providers =
          "java-text-configuration-v3".equals(format)
              || "java-text-configuration-v4".equals(format)
              || "java-text-configuration-v5".equals(format);
      boolean anchored =
          "java-text-configuration-v2".equals(format)
              || "java-text-configuration-v4".equals(format)
              || "java-text-configuration-v5".equals(format);
      if (anchored) {
        exact(
            root, Set.of("format", "version", "draft", "active_version", "active", "index_anchor"));
      } else if ("java-text-configuration-v1".equals(format)
          || "java-text-configuration-v3".equals(format)) {
        exact(root, Set.of("format", "version", "draft", "active_version", "active"));
      } else {
        throw failure();
      }
      TextModelConfiguration active = configuration(root.path("active"), providers);
      TextIndexAnchor anchor = anchored
          ? anchor(root.path("index_anchor"), providers, "java-text-configuration-v5".equals(format))
          : null;
      if (anchored && !"java-text-configuration-v5".equals(format)
          && active != null && anchor == null) {
        throw failure();
      }
      return new ModelConfigurationState(
          number(root.path("version")),
          configuration(root.path("draft"), providers),
          root.path("active_version").isNull() ? null : number(root.path("active_version")),
          active,
          anchor);
    } catch (IOException | RuntimeException invalid) {
      throw failure();
    }
  }

  public synchronized ModelConfigurationState read() {
    var stored = readPrivate();
    if (store == null) {
      return stored;
    }
    var selections = new TextRuntimeSelectionRepository(store);
    var selection = store.transaction(selections::read);
    if (!selection.initialized()) {
      var legacy = stored.activeVersion() == null ? null
          : seal(stored.activeVersion(), stored.active(), stored.indexAnchor());
      store.transaction(() -> {
        if (!selections.read().initialized()) {
          selections.initialize(stored.activeVersion(),
              legacy == null ? null : legacy.configurationSha256(),
              legacy == null ? null : legacy.anchorSha256(), Instant.now().toString());
        }
        return null;
      });
      selection = store.transaction(selections::read);
    }
    if (selection.activeVersion() == null) {
      return new ModelConfigurationState(stored.version(), stored.draft(), null, null, null);
    }
    var active = sealed(selection.activeVersion(), selection.configurationSha256(),
        selection.anchorSha256());
    return new ModelConfigurationState(stored.version(), stored.draft(), active.version(),
        active.configuration(), active.anchor());
  }

  /** A private immutable payload, selected only through the authority version and two digests. */
  public record SealedVersion(long version, TextModelConfiguration configuration,
      TextIndexAnchor anchor, String configurationSha256, String anchorSha256) {
    @Override
    public String toString() {
      return "SealedVersion[redacted]";
    }
  }

  public synchronized SealedVersion seal(
      long version, TextModelConfiguration configuration, TextIndexAnchor anchor) {
    requireOpen();
    if (version < 1 || version > ModelConfigurationState.MAX_VERSION || configuration == null
        || (anchor != null && (!anchor.matchesEmbedding(configuration)
            || anchor.originatingVersion() > version))) {
      throw failure();
    }
    Path temporary = null;
    try {
      String configSha = ModelValues.sha256(JSON.writeValueAsBytes(configuration));
      String anchorSha = ModelValues.sha256(JSON.writeValueAsBytes(
          anchor == null ? null : anchorValue(anchor)));
      Path sealed = sealedPath(version, configSha, anchorSha);
      var result = new SealedVersion(version, configuration, anchor, configSha, anchorSha);
      if (Files.exists(sealed, LinkOption.NOFOLLOW_LINKS)) {
        if (!result.equals(sealed(version, configSha, anchorSha))) {
          throw failure();
        }
        return result;
      }
      var value = new LinkedHashMap<String, Object>();
      value.put("format", "java-text-sealed-v1");
      value.put("version", version);
      value.put("configuration", configuration);
      value.put("index_anchor", anchor == null ? null : anchorValue(anchor));
      byte[] bytes = JSON.writeValueAsBytes(value);
      if (bytes.length > MAX_BYTES) {
        throw failure();
      }
      temporary = Files.createTempFile(file.getParent(), ".text-sealed-", ".partial",
          PosixFilePermissions.asFileAttribute(FILE_MODE));
      try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      Files.move(temporary, sealed, StandardCopyOption.ATOMIC_MOVE);
      forceDirectory();
      return result;
    } catch (IOException | RuntimeException invalid) {
      throw failure();
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // Never expose private payloads through exception messages.
        }
      }
    }
  }

  public synchronized SealedVersion sealed(long version, String configSha, String anchorSha) {
    requireOpen();
    try {
      Path path = sealedPath(version, configSha, anchorSha);
      check(file.getParent(), true);
      check(path, false);
      if (Files.size(path) > MAX_BYTES) {
        throw failure();
      }
      byte[] bytes;
      try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES) {
        throw failure();
      }
      var value = JSON.readTree(bytes);
      exact(value, Set.of("format", "version", "configuration", "index_anchor"));
      if (!"java-text-sealed-v1".equals(text(value.path("format")))
          || number(value.path("version")) != version) {
        throw failure();
      }
      var configuration = configuration(value.path("configuration"), true);
      var anchor = anchor(value.path("index_anchor"), true, true);
      if (configuration == null
          || !configSha.equals(ModelValues.sha256(JSON.writeValueAsBytes(configuration)))
          || !anchorSha.equals(ModelValues.sha256(JSON.writeValueAsBytes(
              anchor == null ? null : anchorValue(anchor))))) {
        throw failure();
      }
      return new SealedVersion(version, configuration, anchor, configSha, anchorSha);
    } catch (IOException | RuntimeException invalid) {
      throw failure();
    }
  }

  private Path sealedPath(long version, String configSha, String anchorSha) {
    if (version < 1 || version > ModelConfigurationState.MAX_VERSION
        || configSha == null || !configSha.matches("[a-f0-9]{64}")
        || anchorSha == null || !anchorSha.matches("[a-f0-9]{64}")) {
      throw failure();
    }
    return file.getParent().resolve("text-version-" + version + "-" + configSha + "-"
        + anchorSha + ".json");
  }

  private void select(ModelConfigurationState next) {
    if (store == null) {
      write(next);
      return;
    }
    var payload = seal(next.activeVersion(), next.active(), next.indexAnchor());
    var selections = new TextRuntimeSelectionRepository(store);
    store.transaction(() -> {
      var expected = selections.read();
      selections.select(expected, payload.version(), payload.configurationSha256(),
          payload.anchorSha256(), null, Instant.now().toString());
      return null;
    });
  }

  public synchronized ModelConfigurationState save(long baseVersion, TextModelConfiguration draft) {
    var before = read();
    if (before.version() != baseVersion) {
      throw conflict();
    }
    if (baseVersion >= ModelConfigurationState.MAX_VERSION || draft == null) {
      throw failure();
    }
    var next =
        new ModelConfigurationState(
            baseVersion + 1, draft, before.activeVersion(), before.active(), before.indexAnchor());
    write(next);
    return next;
  }

  public synchronized ModelConfigurationState activate(long version) {
    var before = read();
    if (before.version() != version || before.draft() == null || before.indexAnchor() != null) {
      throw conflict();
    }
    var next = new ModelConfigurationState(version, before.draft(), version, before.draft());
    select(next);
    return next;
  }

  public synchronized ModelConfigurationState activate(long version, TextIndexAnchor anchor) {
    var before = read();
    if (before.version() != version || before.draft() == null || anchor == null) {
      throw conflict();
    }
    if (before.indexAnchor() != null) {
      if (!before.indexAnchor().equals(anchor)) {
        throw conflict();
      }
    } else {
      long originatingVersion = before.activeVersion() == null ? version : before.activeVersion();
      TextModelConfiguration original = before.active() == null ? before.draft() : before.active();
      if (anchor.originatingVersion() != originatingVersion
          || !anchor.matchesOriginalRoles(original)) {
        throw conflict();
      }
    }
    var next =
        new ModelConfigurationState(version, before.draft(), version, before.draft(), anchor);
    select(next);
    return next;
  }

  public synchronized ModelConfigurationState bootstrapIfAbsent(
      TextModelConfiguration configuration) {
    var before = read();
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return before;
    }
    return save(0, configuration);
  }

  private void write(ModelConfigurationState state) {
    requireOpen();
    Path temporary = null;
    boolean replaced = false;
    try {
      check(file.getParent(), true);
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        check(file, false);
      }
      var value = new LinkedHashMap<String, Object>();
      value.put("format", "java-text-configuration-v5");
      value.put("version", state.version());
      value.put("draft", state.draft());
      value.put("active_version", state.activeVersion());
      value.put("active", state.active());
      value.put("index_anchor", state.indexAnchor() == null ? null : anchorValue(state.indexAnchor()));
      byte[] bytes = JSON.writeValueAsBytes(value);
      if (bytes.length > MAX_BYTES) {
        throw failure();
      }
      forceDirectory();
      temporary =
          Files.createTempFile(
              file.getParent(),
              ".text-model-",
              ".partial",
              PosixFilePermissions.asFileAttribute(FILE_MODE));
      try (var output =
          FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      replaced = true;
      forceDirectory();
      lockChannel.position(0);
      lockChannel.write(ByteBuffer.wrap(new byte[] {1}));
      lockChannel.force(true);
    } catch (IOException | RuntimeException failed) {
      unavailable |= replaced;
      throw failure();
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          /* No secret content is logged. */
        }
      }
    }
  }

  private static LinkedHashMap<String, Object> anchorValue(TextIndexAnchor anchor) {
    var target = new LinkedHashMap<String, Object>();
    target.put("embedding_identity", anchor.target().embeddingIdentity());
    target.put("projection_identity", anchor.target().projectionIdentity());
    target.put("model_revision", anchor.target().modelRevision());
    target.put("dimensions", anchor.target().dimensions());
    var value = new LinkedHashMap<String, Object>();
    value.put("originating_version", anchor.originatingVersion());
    value.put("provider_base_url", anchor.providerBaseUrl());
    value.put("embedding_model", anchor.embeddingModel());
    value.put("embedding_revision", anchor.embeddingRevision());
    value.put("dimensions", anchor.dimensions());
    value.put("rerank_model", anchor.rerankModel());
    value.put("generation_model", anchor.generationModel());
    value.put("target", target);
    value.put("rerank_provider_base_url", anchor.rerankProviderBaseUrl());
    value.put("generation_provider_base_url", anchor.generationProviderBaseUrl());
    value.put("projection_collection", anchor.projectionCollection());
    return value;
  }

  private static TextIndexAnchor anchor(JsonNode value, boolean providers, boolean collection) {
    if (value.isNull()) {
      return null;
    }
    var fields =
        Set.of(
            "originating_version",
            "provider_base_url",
            "embedding_model",
            "embedding_revision",
            "dimensions",
            "rerank_model",
            "generation_model",
            "target");
    if (providers) {
      var extended = new HashSet<>(fields);
      extended.add("rerank_provider_base_url");
      extended.add("generation_provider_base_url");
      if (collection) {
        extended.add("projection_collection");
      }
      exact(value, extended);
    } else {
      exact(value, fields);
    }
    var target = value.path("target");
    exact(
        target,
        Set.of("embedding_identity", "projection_identity", "model_revision", "dimensions"));
    long dimensions = number(value.path("dimensions"));
    long targetDimensions = number(target.path("dimensions"));
    if (dimensions > 8192 || targetDimensions > 8192) {
      throw failure();
    }
    return new TextIndexAnchor(
        number(value.path("originating_version")),
        text(value.path("provider_base_url")),
        text(value.path("embedding_model")),
        text(value.path("embedding_revision")),
        (int) dimensions,
        text(value.path("rerank_model")),
        text(value.path("generation_model")),
        new IndexTarget(
            text(target.path("embedding_identity")),
            text(target.path("projection_identity")),
            text(target.path("model_revision")),
            (int) targetDimensions),
        providers
            ? text(value.path("rerank_provider_base_url"))
            : text(value.path("provider_base_url")),
        providers
            ? text(value.path("generation_provider_base_url"))
            : text(value.path("provider_base_url")),
        collection && !value.path("projection_collection").isNull()
            ? text(value.path("projection_collection")) : null);
  }

  private static TextModelConfiguration configuration(JsonNode value, boolean providers) {
    if (value.isNull()) {
      return null;
    }
    exact(value, Set.of("embedding", "rerank", "generation"));
    var embedding = value.path("embedding");
    exact(
        embedding,
        providers
            ? Set.of("model", "apiKey", "dimensions", "revision", "provider")
            : Set.of("model", "apiKey", "dimensions", "revision"));
    long dimensions = number(embedding.path("dimensions"));
    if (dimensions > 8192) {
      throw failure();
    }
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            text(embedding.path("model")),
            text(embedding.path("apiKey")),
            (int) dimensions,
            text(embedding.path("revision")),
            providers ? text(embedding.path("provider")) : "siliconflow"),
        role(value.path("rerank"), providers),
        role(value.path("generation"), providers));
  }

  private static TextModelConfiguration.Role role(JsonNode value, boolean providers) {
    exact(value, providers ? Set.of("model", "apiKey", "provider") : Set.of("model", "apiKey"));
    return new TextModelConfiguration.Role(
        text(value.path("model")),
        text(value.path("apiKey")),
        providers ? text(value.path("provider")) : "siliconflow");
  }

  private static void exact(JsonNode value, Set<String> fields) {
    if (value == null || !value.isObject() || !Set.copyOf(value.propertyNames()).equals(fields)) {
      throw failure();
    }
  }

  private static String text(JsonNode value) {
    if (!value.isString()) {
      throw failure();
    }
    return value.stringValue();
  }

  private static long number(JsonNode value) {
    if (!value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0
        || value.longValue() > ModelConfigurationState.MAX_VERSION) {
      throw failure();
    }
    return value.longValue();
  }

  private static void check(Path path, boolean directory) throws IOException {
    if (Files.isSymbolicLink(path)
        || (directory
            ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            .equals(directory ? DIRECTORY_MODE : FILE_MODE)) {
      throw failure();
    }
  }

  private void forceDirectory() throws IOException {
    try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
      directory.force(true);
    }
  }

  private void requireOpen() {
    if (unavailable || lock == null || !lock.isValid()) {
      throw failure();
    }
  }

  public static ApplicationException conflict() {
    return new ApplicationException(
        FailureKind.CONFLICT, "configuration_conflict", "配置版本已变化，请重新读取。");
  }

  private static ApplicationException failure() {
    return new ApplicationException(
        FailureKind.UNAVAILABLE, "model_configuration_unavailable", "私有模型配置暂不可用。");
  }

  @Override
  public synchronized void close() {
    try {
      if (lock != null) {
        lock.release();
      }
    } catch (IOException ignored) {
      /* Close the channel next. */
    }
    try {
      if (lockChannel != null) {
        lockChannel.close();
      }
    } catch (IOException ignored) {
      /* No private data is logged. */
    }
    lock = null;
    lockChannel = null;
  }
}
