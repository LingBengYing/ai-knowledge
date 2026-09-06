package com.evidence.rag.management;

import com.evidence.rag.shared.Actor;
import com.evidence.rag.shared.Problem;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Authoritative, isolated management Module. No model calls or Python database access. */
public final class ManagementModule implements AutoCloseable {
  private static final Set<String> ROLES = Set.of("reader", "editor", "owner");
  private static final Set<String> TYPES = Set.of("document", "image", "audio", "video");
  private static final Set<String> STATUS =
      Set.of("ready", "processing", "failed", "cancelled", "deleting");
  private static final String FORMAT = "evidence-rag-java-management-v1";
  private Connection connection;
  private FileChannel lockChannel;
  private FileLock writerLock;

  public ManagementModule(Path directory) {
    try {
      Files.createDirectories(directory);
      if (Files.isSymbolicLink(directory)
          || Files.exists(directory.resolve("rag.db"))
          || Files.exists(directory.resolve("authority.db"))) {
        throw new IllegalStateException("An isolated Java data directory is required");
      }
      // Canonicalize parent aliases so SQLite and the lifetime lock name the same directory.
      Path canonicalDirectory = directory.toRealPath();
      lockChannel =
          FileChannel.open(
              canonicalDirectory.resolve(".java-library.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      writerLock = lockChannel.tryLock();
      if (writerLock == null) throw new IllegalStateException("Java library already has a writer");
      // Inspect format only after winning the lock: a previous owner may just have initialized it.
      Path database = canonicalDirectory.resolve("java-library.db");
      boolean exists = Files.exists(database, LinkOption.NOFOLLOW_LINKS);
      if (exists && !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Unsafe Java database path");
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
      if (exists) verifyFormat();
      execute("PRAGMA foreign_keys=ON");
      execute("PRAGMA busy_timeout=5000");
      if (!exists) initialize();
    } catch (IOException | SQLException | RuntimeException error) {
      close();
      throw new IllegalStateException("Cannot open isolated Java library", error);
    }
  }

  /** Explicit local/test fixture Interface only; never mapped to an HTTP endpoint. */
  public record SyntheticDocument(
      String documentId,
      String filename,
      String type,
      String mimeType,
      String revisionId,
      String sha256,
      long sizeBytes) {}

  public synchronized void registerSyntheticDocument(
      Actor owner, SyntheticDocument document, Map<String, String> grants) {
    if (document == null || grants == null) throw invalid();
    identifier(document.documentId(), 100);
    bounded(document.filename(), 255);
    identifier(document.revisionId(), 100);
    label(document.mimeType(), 100);
    if (document.filename().isBlank()) throw invalid();
    if (!TYPES.contains(document.type())
        || document.sha256() == null
        || !document.sha256().matches("[a-f0-9]{64}")
        || document.sizeBytes() < 0) throw invalid();
    for (var grant : grants.entrySet()) {
      new Actor(owner.workspaceId(), grant.getKey());
      if (!ROLES.contains(grant.getValue())
          || (grant.getKey().equals(owner.principalId()) && !"owner".equals(grant.getValue())))
        throw invalid();
    }
    transaction(
        () -> {
          if (!rows("SELECT id FROM documents WHERE id=?", document.documentId()).isEmpty())
            throw new Problem(409, "document_conflict", "合成资料标识已存在。");
          execute(
              "INSERT INTO documents VALUES(?,?,?,?,?,?,?,?,?,?,NULL)",
              document.documentId(),
              owner.workspaceId(),
              document.filename(),
              document.type(),
              document.mimeType(),
              document.revisionId(),
              document.sha256(),
              document.sizeBytes(),
              Instant.now().toString(),
              document.filename());
          var permissions = new TreeMap<>(grants);
          permissions.put(owner.principalId(), "owner");
          for (var grant : permissions.entrySet())
            execute(
                "INSERT INTO document_acl VALUES(?,?,?)",
                document.documentId(),
                grant.getKey(),
                grant.getValue());
          return null;
        });
  }

  public synchronized Map<String, Object> listDocuments(Actor actor, Map<String, String> query) {
    if (query == null) throw invalid();
    var allowed = Set.of("q", "type", "status", "folder_id", "tag", "sort", "page", "page_size");
    if (!allowed.containsAll(query.keySet())) throw invalid();
    int page = integer(query.getOrDefault("page", "1"), 1, 1_000_000);
    int size = integer(query.getOrDefault("page_size", "20"), 1, 100);
    String q = bounded(query.getOrDefault("q", ""), 200);
    String type = query.get("type"), status = query.get("status");
    if ((type != null && !TYPES.contains(type)) || (status != null && !STATUS.contains(status)))
      throw invalid();
    String folder = query.get("folder_id"), tag = query.get("tag");
    if (folder != null) bounded(folder, 100);
    if (tag != null) bounded(tag, 40);
    String order =
        switch (query.getOrDefault("sort", "updated_desc")) {
          case "updated_desc" -> "d.updated_at DESC";
          case "updated_asc" -> "d.updated_at ASC";
          case "name_asc" -> "d.display_name COLLATE NOCASE ASC";
          case "name_desc" -> "d.display_name COLLATE NOCASE DESC";
          default -> throw invalid();
        };
    return transaction(
        () -> {
          var args = new ArrayList<Object>(List.of(actor.workspaceId(), actor.principalId()));
          var predicate = new StringBuilder(" WHERE d.workspace_id=? AND acl.principal_id=?");
          if (!q.isEmpty()) {
            predicate.append(
                " AND (instr(lower(d.filename),lower(?))>0 OR instr(lower(d.display_name),lower(?))>0)");
            args.add(q);
            args.add(q);
          }
          if (type != null) {
            predicate.append(" AND d.document_type=?");
            args.add(type);
          }
          if (status != null && !status.equals("ready")) predicate.append(" AND 0=1");
          if ("unfiled".equals(folder)) predicate.append(" AND d.folder_id IS NULL");
          else if (folder != null && !folder.isEmpty()) {
            predicate.append(" AND d.folder_id=?");
            args.add(folder);
          }
          if (tag != null) {
            predicate.append(
                " AND EXISTS(SELECT 1 FROM document_tags t WHERE t.document_id=d.id AND t.tag=?)");
            args.add(tag);
          }
          String from = " FROM documents d JOIN document_acl acl ON acl.document_id=d.id";
          long total = count("SELECT COUNT(*)" + from + predicate, args.toArray());
          args.add(size);
          args.add((long) (page - 1) * size);
          var selected =
              rows(
                  "SELECT d.*,acl.role AS current_role,f.name AS folder_name"
                      + from
                      + " LEFT JOIN folders f ON f.id=d.folder_id AND f.workspace_id=d.workspace_id"
                      + predicate
                      + " ORDER BY "
                      + order
                      + ",d.id ASC LIMIT ? OFFSET ?",
                  args.toArray());
          var items = new ArrayList<Map<String, Object>>();
          for (var row : selected) items.add(documentView(row));
          return map(
              "items",
              items,
              "total",
              total,
              "page",
              page,
              "page_size",
              size,
              "total_pages",
              (total + size - 1) / size);
        });
  }

  public synchronized Map<String, Object> updateDocument(
      Actor actor, String id, Map<String, Object> body) {
    var patch = validatePatch(body);
    return transaction(() -> patchDocument(actor, id, patch, false));
  }

  public synchronized Map<String, Object> listFolders(Actor actor) {
    return transaction(() -> map("items", folderViews(actor)));
  }

  public synchronized Map<String, Object> createFolder(Actor actor, Map<String, Object> body) {
    String name = folderName(body);
    return transaction(
        () -> {
          checkFolderConflict(actor, name, "");
          String id = UUID.randomUUID().toString();
          execute(
              "INSERT INTO folders VALUES(?,?,?,?,?,?)",
              id,
              actor.workspaceId(),
              actor.principalId(),
              name,
              fold(name),
              Instant.now().toString());
          audit(actor, id, "folder_created", null, map("name", name), Set.of("name"));
          return map("folder_id", id, "name", name, "document_count", 0L, "can_edit", true);
        });
  }

  public synchronized Map<String, Object> renameFolder(
      Actor actor, String id, Map<String, Object> body) {
    String name = folderName(body);
    return transaction(
        () -> {
          var previous = visibleFolder(actor, id, true);
          checkFolderConflict(actor, name, id);
          execute(
              "UPDATE folders SET name=?,name_key=?,updated_at=? WHERE id=?",
              name,
              fold(name),
              Instant.now().toString(),
              id);
          audit(
              actor,
              id,
              "folder_renamed",
              map("name", previous.get("name")),
              map("name", name),
              Set.of("name"));
          return folderViews(actor).stream()
              .filter(item -> item.get("folder_id").equals(id))
              .findFirst()
              .orElseThrow();
        });
  }

  public synchronized Map<String, Object> removeFolder(Actor actor, String id) {
    return transaction(
        () -> {
          var previous = visibleFolder(actor, id, true);
          if (count("SELECT COUNT(*) FROM documents WHERE folder_id=?", id) != 0)
            throw new Problem(409, "folder_not_empty", "目录仍有资料，请先移出资料再删除目录。");
          execute("DELETE FROM folders WHERE id=?", id);
          audit(
              actor,
              id,
              "folder_deleted",
              map("name", previous.get("name")),
              map("name", null),
              Set.of("name"));
          return map("folder_id", id, "status", "removed");
        });
  }

  public synchronized Map<String, Object> listTags(Actor actor) {
    return transaction(
        () ->
            map(
                "items",
                rows(
                        """
        SELECT DISTINCT t.tag FROM document_tags t JOIN documents d ON d.id=t.document_id
        JOIN document_acl acl ON acl.document_id=d.id
        WHERE d.workspace_id=? AND acl.principal_id=? ORDER BY t.tag
        """,
                        actor.workspaceId(),
                        actor.principalId())
                    .stream()
                    .map(row -> row.get("tag"))
                    .toList()));
  }

  public synchronized Map<String, Object> documentActions(Actor actor, Map<String, Object> body) {
    requireFields(body, Set.of("document_ids", "action", "folder_id", "tags"));
    if (!(body.get("document_ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 100)
      throw invalid();
    var identifiers = new LinkedHashSet<String>();
    for (Object id : ids) {
      String value = identifier(id, 100);
      if (!identifiers.add(value)) throw invalid();
    }
    if (!(body.get("action") instanceof String action)) throw invalid();
    if (action.equals("delete") || action.equals("reindex"))
      throw new Problem(501, "migration_incomplete", "Java 版尚未移植资料生命周期操作。");
    var patch = new LinkedHashMap<String, Object>();
    if (action.equals("move")) {
      if (!body.containsKey("folder_id") || body.get("tags") != null) throw invalid();
      patch.put("folder_id", body.get("folder_id"));
    } else if (action.equals("tag")) {
      if (body.get("folder_id") != null) throw invalid();
      var tags = tags(body.get("tags"));
      if (tags.isEmpty()) throw invalid();
      patch.put("tags", tags);
    } else throw invalid();
    var validated = validatePatch(patch);
    var items = new ArrayList<Map<String, Object>>();
    for (String id : identifiers) {
      try {
        transaction(() -> patchDocument(actor, id, validated, action.equals("tag")));
        items.add(
            map(
                "document_id",
                id,
                "ok",
                true,
                "receipt",
                map("document_id", id, "status", "updated")));
      } catch (Problem problem) {
        items.add(
            map(
                "document_id",
                id,
                "ok",
                false,
                "error_code",
                problem.code(),
                "detail",
                problem.getMessage()));
      }
    }
    return map("items", items);
  }

  /** Bounded, actor-local hash-only audit Interface; not a public HTTP endpoint. */
  public synchronized Map<String, Object> auditEvents(Actor actor) {
    return transaction(
        () ->
            map(
                "items",
                rows(
                    "SELECT * FROM management_audit WHERE workspace_id=? AND actor_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
                    actor.workspaceId(),
                    actor.principalId())));
  }

  private Map<String, Object> patchDocument(
      Actor actor, String id, Map<String, Object> patch, boolean append) throws SQLException {
    var found =
        rows(
            """
        SELECT d.*,acl.role AS current_role,f.name AS folder_name FROM documents d
        JOIN document_acl acl ON acl.document_id=d.id LEFT JOIN folders f ON f.id=d.folder_id
        WHERE d.id=? AND d.workspace_id=? AND acl.principal_id=? AND acl.role IN ('owner','editor')
        """,
            id,
            actor.workspaceId(),
            actor.principalId());
    if (found.isEmpty()) throw notFound();
    var document = found.getFirst();
    var previous =
        map(
            "display_name",
            document.get("display_name"),
            "folder_id",
            document.get("folder_id"),
            "tags",
            documentTags(id));
    var next = new LinkedHashMap<>(previous);
    next.putAll(patch);
    if (next.get("folder_id") != null) {
      var folder = visibleFolder(actor, (String) next.get("folder_id"), false);
      document.put("folder_name", folder.get("name"));
    } else document.put("folder_name", null);
    if (append) {
      var merged = new LinkedHashSet<>(documentTags(id));
      merged.addAll(tags(next.get("tags")));
      if (merged.size() > 20) throw new Problem(409, "tag_limit_reached", "每份资料最多保留20个标签。");
      next.put("tags", new ArrayList<>(merged));
    }
    String when = Instant.now().toString();
    execute(
        "UPDATE documents SET display_name=?,folder_id=?,updated_at=? WHERE id=? AND workspace_id=?",
        next.get("display_name"),
        next.get("folder_id"),
        when,
        id,
        actor.workspaceId());
    if (patch.containsKey("tags")) {
      execute("DELETE FROM document_tags WHERE document_id=?", id);
      var values = tags(next.get("tags"));
      for (int index = 0; index < values.size(); index++)
        execute("INSERT INTO document_tags VALUES(?,?,?)", id, values.get(index), index);
    }
    audit(actor, id, "document_updated", previous, next, patch.keySet());
    document.putAll(next);
    document.put("updated_at", when);
    return documentView(document);
  }

  private List<String> documentTags(String id) throws SQLException {
    return rows("SELECT tag FROM document_tags WHERE document_id=? ORDER BY ordinal,tag", id)
        .stream()
        .map(row -> (String) row.get("tag"))
        .toList();
  }

  private Map<String, Object> documentView(Map<String, Object> row) throws SQLException {
    return map(
        "document_id",
        row.get("id"),
        "filename",
        row.get("filename"),
        "status",
        "ready",
        "active_revision_id",
        row.get("active_revision_id"),
        "segment_count",
        0,
        "updated_at",
        row.get("updated_at"),
        "modalities",
        List.of(),
        "media_info",
        map(
            "mime_type",
            row.get("mime_type"),
            "size_bytes",
            row.get("size_bytes"),
            "sha256",
            row.get("source_sha256")),
        "display_name",
        row.get("display_name"),
        "folder_id",
        row.get("folder_id"),
        "folder_name",
        row.get("folder_name"),
        "tags",
        documentTags((String) row.get("id")),
        "current_role",
        row.get("current_role"),
        "can_edit",
        !"reader".equals(row.get("current_role")),
        "can_delete",
        false,
        "can_reindex",
        false,
        "latest_job",
        null,
        "synthetic_fixture",
        true,
        "document_type",
        row.get("document_type"));
  }

  private List<Map<String, Object>> folderViews(Actor actor) throws SQLException {
    var selected =
        rows(
            """
        SELECT f.id AS folder_id,f.name,f.owner_id,COUNT(acl.document_id) AS document_count
        FROM folders f LEFT JOIN documents d ON d.folder_id=f.id AND d.workspace_id=f.workspace_id
        LEFT JOIN document_acl acl ON acl.document_id=d.id AND acl.principal_id=?
        WHERE f.workspace_id=? GROUP BY f.id HAVING f.owner_id=? OR COUNT(acl.document_id)>0
        ORDER BY f.name COLLATE NOCASE,f.id
        """,
            actor.principalId(),
            actor.workspaceId(),
            actor.principalId());
    var views = new ArrayList<Map<String, Object>>();
    for (var row : selected)
      views.add(
          map(
              "folder_id",
              row.get("folder_id"),
              "name",
              row.get("name"),
              "document_count",
              ((Number) row.get("document_count")).longValue(),
              "can_edit",
              actor.principalId().equals(row.get("owner_id"))));
    return views;
  }

  private Map<String, Object> visibleFolder(Actor actor, String id, boolean ownerOnly)
      throws SQLException {
    var found =
        rows(
            """
        SELECT f.* FROM folders f WHERE f.id=? AND f.workspace_id=? AND (f.owner_id=? OR
        (?=0 AND EXISTS(SELECT 1 FROM documents d JOIN document_acl acl ON acl.document_id=d.id
        WHERE d.folder_id=f.id AND d.workspace_id=f.workspace_id AND acl.principal_id=?)))
        """,
            id,
            actor.workspaceId(),
            actor.principalId(),
            ownerOnly ? 1 : 0,
            actor.principalId());
    if (found.isEmpty()) throw notFound();
    return found.getFirst();
  }

  private void checkFolderConflict(Actor actor, String name, String except) throws SQLException {
    if (count(
            "SELECT COUNT(*) FROM folders WHERE workspace_id=? AND owner_id=? AND name_key=? AND id!=?",
            actor.workspaceId(),
            actor.principalId(),
            fold(name),
            except)
        != 0) throw new Problem(409, "folder_name_conflict", "你已创建同名目录。");
  }

  private static String fold(String value) {
    return value.toLowerCase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ");
  }

  private static Map<String, Object> validatePatch(Map<String, Object> body) {
    requireFields(body, Set.of("display_name", "folder_id", "tags"));
    if (body.isEmpty()) throw invalid();
    var patch = new LinkedHashMap<String, Object>();
    if (body.containsKey("display_name"))
      patch.put("display_name", label(body.get("display_name"), 255));
    if (body.containsKey("folder_id"))
      patch.put(
          "folder_id",
          body.get("folder_id") == null ? null : identifier(body.get("folder_id"), 100));
    if (body.containsKey("tags")) patch.put("tags", tags(body.get("tags")));
    return patch;
  }

  private static String folderName(Map<String, Object> body) {
    requireFields(body, Set.of("name"));
    return label(body.get("name"), 80);
  }

  private static void requireFields(Map<String, Object> body, Set<String> allowed) {
    if (body == null || !allowed.containsAll(body.keySet())) throw invalid();
  }

  private static List<String> tags(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 20) throw invalid();
    var result = new LinkedHashSet<String>();
    for (Object tag : list) result.add(label(tag, 40));
    return new ArrayList<>(result);
  }

  private static String label(Object value, int maximum) {
    if (!(value instanceof String text)) throw invalid();
    String cleaned = text.strip();
    if (cleaned.isEmpty()) throw invalid();
    return bounded(cleaned, maximum);
  }

  private static String identifier(Object value, int maximum) {
    if (!(value instanceof String text) || text.isEmpty()) throw invalid();
    return bounded(text, maximum);
  }

  private static String bounded(String value, int maximum) {
    if (value == null
        || value.codePointCount(0, value.length()) > maximum
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)))
      throw invalid();
    return value;
  }

  private static int integer(String value, int minimum, int maximum) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < minimum || parsed > maximum) throw invalid();
      return parsed;
    } catch (NumberFormatException error) {
      throw invalid();
    }
  }

  private static Problem invalid() {
    return new Problem(422, "invalid_request", "请求字段、长度或取值无效。");
  }

  private static Problem notFound() {
    return new Problem(404, "not_found", "资料不存在、无操作权限或当前状态不可操作。");
  }

  private void audit(
      Actor actor, String entityId, String action, Object before, Object after, Set<String> fields)
      throws SQLException {
    String fieldJson =
        "["
            + String.join(",", fields.stream().sorted().map(field -> "\"" + field + "\"").toList())
            + "]";
    execute(
        "INSERT INTO management_audit VALUES(?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor.workspaceId(),
        actor.principalId(),
        entityId,
        action,
        fieldJson,
        digest(before),
        digest(after),
        Instant.now().toString());
  }

  private static String digest(Object object) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical(object).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String canonical(Object value) {
    if (value == null) return "N";
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put((String) key, item));
      var output = new StringBuilder("M").append(sorted.size()).append(':');
      sorted.forEach((key, item) -> output.append(canonical(key)).append(canonical(item)));
      return output.toString();
    }
    if (value instanceof List<?> list) {
      var output = new StringBuilder("L").append(list.size()).append(':');
      list.forEach(item -> output.append(canonical(item)));
      return output.toString();
    }
    String text = value.toString();
    return "S" + text.length() + ":" + text;
  }

  private static Map<String, Object> map(Object... pairs) {
    var output = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) output.put((String) pairs[i], pairs[i + 1]);
    return output;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run() throws SQLException;
  }

  private <T> T transaction(Work<T> work) {
    if (connection == null) throw new Problem(503, "management_unavailable", "资料管理暂不可用。");
    try {
      execute("BEGIN IMMEDIATE");
      T result = work.run();
      execute("COMMIT");
      return result;
    } catch (SQLException | RuntimeException failure) {
      try {
        execute("ROLLBACK");
      } catch (SQLException ignored) {
        /* Original failure wins. */
      }
      if (failure instanceof Problem problem) throw problem;
      throw new Problem(503, "management_unavailable", "资料管理暂不可用。");
    }
  }

  private PreparedStatement prepare(String sql, Object... args) throws SQLException {
    var statement = connection.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    return statement;
  }

  private void execute(String sql, Object... args) throws SQLException {
    try (var statement = prepare(sql, args)) {
      statement.execute();
    }
  }

  private long count(String sql, Object... args) throws SQLException {
    try (var statement = prepare(sql, args);
        var result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private List<Map<String, Object>> rows(String sql, Object... args) throws SQLException {
    try (var statement = prepare(sql, args);
        var result = statement.executeQuery()) {
      var output = new ArrayList<Map<String, Object>>();
      while (result.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= result.getMetaData().getColumnCount(); i++)
          row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
        output.add(row);
      }
      return output;
    }
  }

  private void verifyFormat() throws SQLException {
    if (count("PRAGMA application_id") != 1163280711
        || count("PRAGMA user_version") != 1
        || count("SELECT COUNT(*) FROM format_info WHERE format=? AND version=1", FORMAT) != 1)
      throw new IllegalStateException("Unsupported Java database format");
  }

  private void initialize() {
    transaction(
        () -> {
          execute("PRAGMA application_id=1163280711");
          execute("PRAGMA user_version=1");
          execute("CREATE TABLE format_info(format TEXT PRIMARY KEY,version INTEGER NOT NULL)");
          execute("INSERT INTO format_info VALUES(?,1)", FORMAT);
          execute(
              "CREATE TABLE folders(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,owner_id TEXT NOT NULL,name TEXT NOT NULL,name_key TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(workspace_id,owner_id,name_key))");
          execute(
              "CREATE TABLE documents(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,filename TEXT NOT NULL,document_type TEXT NOT NULL,mime_type TEXT NOT NULL,active_revision_id TEXT NOT NULL,source_sha256 TEXT NOT NULL,size_bytes INTEGER NOT NULL,updated_at TEXT NOT NULL,display_name TEXT NOT NULL,folder_id TEXT REFERENCES folders(id) ON DELETE RESTRICT)");
          execute(
              "CREATE TABLE document_acl(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,principal_id TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('owner','editor','reader')),PRIMARY KEY(document_id,principal_id))");
          execute(
              "CREATE TABLE document_tags(document_id TEXT REFERENCES documents(id) ON DELETE CASCADE,tag TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(document_id,tag))");
          execute(
              "CREATE TABLE management_audit(id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,actor_id TEXT NOT NULL,entity_id TEXT NOT NULL,action TEXT NOT NULL,fields_json TEXT NOT NULL,before_sha256 TEXT NOT NULL,after_sha256 TEXT NOT NULL,created_at TEXT NOT NULL)");
          execute("CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)");
          execute("CREATE INDEX documents_folder ON documents(folder_id,workspace_id)");
          execute("CREATE INDEX acl_principal ON document_acl(principal_id,document_id)");
          execute("CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)");
          execute(
              "CREATE TRIGGER audit_no_update BEFORE UPDATE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END");
          execute(
              "CREATE TRIGGER audit_no_delete BEFORE DELETE ON management_audit BEGIN SELECT RAISE(ABORT,'immutable audit'); END");
          execute(
              "CREATE TRIGGER immutable_identity BEFORE UPDATE OF filename,workspace_id,document_type,mime_type,active_revision_id,source_sha256,size_bytes ON documents BEGIN SELECT RAISE(ABORT,'immutable source identity'); END");
          return null;
        });
  }

  @Override
  public synchronized void close() {
    try {
      if (connection != null) connection.close();
    } catch (SQLException ignored) {
      /* Close remaining resources. */
    }
    connection = null;
    try {
      if (writerLock != null) writerLock.release();
    } catch (IOException ignored) {
      /* Channel close releases lock. */
    }
    try {
      if (lockChannel != null) lockChannel.close();
    } catch (IOException ignored) {
      /* Shutdown best effort. */
    }
  }
}
