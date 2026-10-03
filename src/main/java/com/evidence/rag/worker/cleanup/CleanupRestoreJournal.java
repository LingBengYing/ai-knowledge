package com.evidence.rag.worker.cleanup;

import com.evidence.rag.model.domain.CleanupPlan;
import com.evidence.rag.worker.OwnedTemporaryResources;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fsynced hash-only denial intent outside restorable SQLite snapshots. */
public final class CleanupRestoreJournal {
  public static final String FILENAME = ".cleanup-restore-journal.jsonl";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> FIELDS =
      Set.of("library_id", "cleanup_id", "document_id", "plan_sha256", "source_sha256");

  private CleanupRestoreJournal() {}

  public static void intent(Path libraryDirectory, String libraryIdentity, CleanupPlan plan)
      throws IOException {
    Path requestedDirectory = libraryDirectory.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(requestedDirectory)
        || !Files.isDirectory(requestedDirectory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Unsafe restore journal root");
    }
    Path directory = requestedDirectory.toRealPath();
    Path file = directory.resolve(FILENAME);
    if (Files.isSymbolicLink(file)
        || (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
      throw new IOException("Unsafe restore journal");
    }
    var entry = new LinkedHashMap<String, String>();
    entry.put("library_id", libraryIdentity);
    entry.put("cleanup_id", plan.cleanupId());
    entry.put("document_id", plan.documentId());
    entry.put("plan_sha256", plan.manifestSha256());
    entry.put("source_sha256", plan.sourceSha256());
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.size(file) > 4 * 1024 * 1024) {
        throw new IOException("Restore journal limit exceeded");
      }
      for (String line : Files.readAllLines(file)) {
        JsonNode old = JSON.readTree(line);
        var names = new HashSet<String>();
        names.addAll(old.propertyNames());
        if (!old.isObject()
            || !names.equals(FIELDS)
            || !libraryIdentity.equals(old.path("library_id").textValue())) {
          throw new IOException("Restore journal identity changed");
        }
        if (plan.cleanupId().equals(old.path("cleanup_id").textValue())) {
          if (!JSON.valueToTree(entry).equals(old)) {
            throw new IOException("Restore journal intent changed");
          }
          return;
        }
      }
    }
    byte[] bytes = (JSON.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    long priorSize = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
    if (priorSize > 4 * 1024 * 1024 - bytes.length) {
      throw new IOException("Restore journal limit exceeded");
    }
    try (var channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    OwnedTemporaryResources.syncDirectory(directory);
  }
}
