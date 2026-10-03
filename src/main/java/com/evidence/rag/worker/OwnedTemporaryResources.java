package com.evidence.rag.worker;

import com.evidence.rag.model.domain.LibraryOperationGate;
import com.evidence.rag.model.domain.LibraryWorkContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Library-owned work directories; unknown or still-running resources are never swept by prefix. */
public final class OwnedTemporaryResources {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final String OWNERS = ".owners";
  private static final int MAX_ENTRIES = 10_000;

  private OwnedTemporaryResources() {}

  public static Path currentRoot() {
    return LibraryWorkContext.currentRoot().orElse(null);
  }

  public static Path createDirectory(String prefix) throws IOException {
    return createDirectory(prefix, currentRoot());
  }

  /** A parent captures its root before dispatching a body to a new thread. */
  public static Path createDirectory(String prefix, Path root) throws IOException {
    if (LibraryWorkContext.isMaintenance()) {
      throw new IOException("Worker creation is forbidden during maintenance");
    }
    if (root == null) {
      // Standalone worker JVMs inherit their parent's private java.io.tmpdir.
      return Files.createTempDirectory(prefix);
    }
    if (prefix == null || !prefix.matches("[a-z][a-z0-9-]{0,60}-")) {
      throw new IOException("Invalid managed work prefix");
    }
    root = checkedRoot(root);
    Path registry = root.resolve(OWNERS);
    privateDirectory(registry);
    Path directory = root.resolve(prefix + UUID.randomUUID());
    Files.createDirectory(
        directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    var owner = new LinkedHashMap<String, Object>();
    owner.put("version", 1);
    owner.put("name", directory.getFileName().toString());
    owner.put("owner_pid", ProcessHandle.current().pid());
    owner.put(
        "owner_started",
        ProcessHandle.current()
            .info()
            .startInstant()
            .orElseThrow(() -> new IOException("Unknown process identity"))
            .toString());
    owner.put("phase", "created");
    owner.put("children", List.of());
    try {
      write(marker(directory), owner);
      return directory;
    } catch (IOException failure) {
      Files.deleteIfExists(directory);
      throw failure;
    }
  }

  public static void launching(Path directory) throws IOException {
    change(directory, null, "launching");
  }

  public static void childStarted(Path directory, Process process) throws IOException {
    change(directory, process, "started");
  }

  private static void change(Path directory, Process process, String phase) throws IOException {
    Path marker = marker(directory);
    if (marker == null || !Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    var value = read(marker);
    var children = new ArrayList<Map<String, Object>>();
    for (JsonNode child : value.path("children")) {
      children.add(
          Map.of(
              "pid", child.path("pid").longValue(), "started", child.path("started").textValue()));
    }
    if (process != null) {
      children.add(
          Map.of(
              "pid",
              process.pid(),
              "started",
              process
                  .info()
                  .startInstant()
                  .orElseThrow(() -> new IOException("Unknown child identity"))
                  .toString()));
    }
    var updated = new LinkedHashMap<String, Object>();
    updated.put("version", 1);
    updated.put("name", value.path("name").textValue());
    updated.put("owner_pid", value.path("owner_pid").longValue());
    updated.put("owner_started", value.path("owner_started").textValue());
    updated.put("phase", phase);
    updated.put("children", children);
    write(marker, updated);
  }

  /** Called only after the existing worker confirms process/writer exit and directory removal. */
  public static void finished(Path directory) throws IOException {
    if (directory == null) {
      return;
    }
    Path marker = marker(directory);
    if (marker == null || !Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Managed work still exists");
    }
    Files.delete(marker);
    syncDirectory(marker.getParent());
  }

  /** Returns false for unknown ownership, an ambiguous launch, or any live child. */
  public static boolean sweep(Path root, LibraryOperationGate.MaintenanceLease lease)
      throws IOException {
    return inspect(root, lease, true);
  }

  public static boolean verifyIdle(Path root, LibraryOperationGate.MaintenanceLease lease)
      throws IOException {
    return inspect(root, lease, false);
  }

  private static boolean inspect(
      Path root, LibraryOperationGate.MaintenanceLease lease, boolean erase) throws IOException {
    if (lease == null
        || !lease.isHeld()
        || !lease.isOwnerThread()
        || !LibraryWorkContext.isMaintenance()
        || !LibraryWorkContext.currentRoot().filter(root::equals).isPresent()) {
      throw new IOException("Maintenance admission required");
    }
    root = checkedRoot(root);
    Path registry = root.resolve(OWNERS);
    privateDirectory(registry);
    List<Path> markers;
    try (var files = Files.list(registry)) {
      markers = files.limit(MAX_ENTRIES + 1L).toList();
    }
    if (markers.size() > MAX_ENTRIES) {
      return false;
    }
    var owned = new ArrayList<Path>();
    for (Path marker : markers) {
      var value = read(marker);
      String name = value.path("name").textValue();
      if (name == null
          || !name.matches("[a-z][a-z0-9-]{0,60}-[a-f0-9-]{36}")
          || !marker.getFileName().toString().equals(name + ".json")
          || !value.path("version").isIntegralNumber()
          || value.path("version").longValue() != 1
          || !value.path("owner_pid").isIntegralNumber()
          || value.path("owner_pid").longValue() <= 0
          || !value.path("children").isArray()
          || "launching".equals(value.path("phase").textValue())) {
        return false;
      }
      if (!List.of("created", "started").contains(value.path("phase").textValue())) {
        return false;
      }
      long owner = value.path("owner_pid").longValue();
      Instant ownerStart = instant(value.path("owner_started").textValue());
      if (alive(owner, ownerStart) && owner != ProcessHandle.current().pid()) {
        return false;
      }
      for (JsonNode child : value.path("children")) {
        if (!child.path("pid").isIntegralNumber()
            || child.path("pid").longValue() <= 0
            || alive(child.path("pid").longValue(), instant(child.path("started").textValue()))) {
          return false;
        }
      }
      if (!value.path("children").isEmpty()) {
        // A dead direct child cannot prove that an orphaned grandchild has also terminated.
        // Normal worker cleanup removes its record only after its existing descendant checks.
        return false;
      }
      owned.add(root.resolve(name));
    }
    try (var entries = Files.list(root)) {
      if (entries
          .limit(MAX_ENTRIES + 1L)
          .anyMatch(p -> !p.equals(registry) && !owned.contains(p))) {
        return false;
      }
    }
    for (Path directory : owned) {
      validateTree(directory);
    }
    if (erase) {
      for (int i = 0; i < owned.size(); i++) {
        erase(owned.get(i));
        Files.delete(markers.get(i));
      }
      syncDirectory(registry);
      syncDirectory(root);
    }
    return true;
  }

  private static boolean alive(long pid, Instant started) {
    return ProcessHandle.of(pid)
        .filter(ProcessHandle::isAlive)
        .flatMap(p -> p.info().startInstant())
        .filter(started::equals)
        .isPresent();
  }

  private static Instant instant(String value) throws IOException {
    try {
      return Instant.parse(value);
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid managed identity");
    }
  }

  private static JsonNode read(Path path) throws IOException {
    if (Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.size(path) > 2 * 1024 * 1024) {
      throw new IOException("Invalid managed ownership record");
    }
    return JSON.readTree(Files.readAllBytes(path));
  }

  private static void write(Path destination, Map<String, Object> record) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(record);
    if (bytes.length > 2 * 1024 * 1024 || Files.isSymbolicLink(destination)) {
      throw new IOException("Invalid managed ownership path");
    }
    try (var channel =
        FileChannel.open(
            destination,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    syncDirectory(destination.getParent());
  }

  public static void syncDirectory(Path directory) throws IOException {
    try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static Path marker(Path directory) {
    if (directory == null || directory.getParent() == null) {
      return null;
    }
    return directory.getParent().resolve(OWNERS).resolve(directory.getFileName() + ".json");
  }

  private static Path checkedRoot(Path root) throws IOException {
    Path normalized = root.toAbsolutePath().normalize();
    privateDirectory(normalized);
    if (!normalized.toRealPath().equals(normalized)) {
      throw new IOException("Unsafe managed work root");
    }
    return normalized;
  }

  private static void privateDirectory(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
            .equals(PosixFilePermissions.fromString("rwx------"))) {
      throw new IOException("Unsafe managed directory");
    }
  }

  private static void validateTree(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          private int entries;

          private void bounded(BasicFileAttributes attributes) throws IOException {
            if (++entries > MAX_ENTRIES || attributes.isSymbolicLink()) {
              throw new IOException("Unsafe managed work contents");
            }
          }

          @Override
          public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes)
              throws IOException {
            bounded(attributes);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
              throws IOException {
            bounded(attributes);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void erase(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          private int entries;

          private void bounded() throws IOException {
            if (++entries > MAX_ENTRIES) {
              throw new IOException("Managed cleanup limit exceeded");
            }
          }

          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
              throws IOException {
            bounded();
            if (attributes.isSymbolicLink()) {
              throw new IOException("Managed work contains a symbolic link");
            }
            Files.delete(path);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path path, IOException failure)
              throws IOException {
            bounded();
            if (failure != null) {
              throw failure;
            }
            Files.delete(path);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
