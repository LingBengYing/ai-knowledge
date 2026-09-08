package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.web.HttpProblemMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementServiceTest {
  @TempDir Path directory;
  private final Actor owner = new Actor("workspace", "owner");
  private final Actor reader = new Actor("workspace", "reader");
  private final Actor editor = new Actor("workspace", "editor");
  private final Actor stranger = new Actor("workspace", "stranger");
  private final Actor other = new Actor("other", "owner");

  private void seed(
      AuthorityTestContext module, String id, String filename, Map<String, String> acl) {
    module.registerSyntheticDocument(
        owner,
        new SyntheticDocument(
            id, filename, "document", "application/pdf", "revision-" + id, "a".repeat(64), 123),
        acl);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> items(Map<String, Object> response) {
    return (List<Map<String, Object>>) response.get("items");
  }

  private ApplicationException fails(int status, Runnable action) {
    var problem = assertThrows(ApplicationException.class, action::run);
    assertEquals(status, HttpProblemMapper.status(problem));
    return problem;
  }

  @Test
  void persistsMetadataWithoutChangingImmutableIdentity() {
    String folder;
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "Original.pdf", Map.of("reader", "reader", "editor", "editor"));
      folder = (String) module.createFolder(owner, Map.of("name", "  项目资料  ")).get("folder_id");
      var changed =
          module.updateDocument(
              editor, "a", Map.of("display_name", "新标题", "tags", List.of("分类", "分类", "测试")));
      assertEquals("Original.pdf", changed.get("filename"));
      assertEquals("revision-a", changed.get("registered_revision_id"));
      assertNull(changed.get("active_revision_id"));
      assertNull(changed.get("index_publication_id"));
      assertEquals(List.of("分类", "测试"), changed.get("tags"));
      assertEquals(false, changed.get("can_delete"));
      assertEquals(false, changed.get("can_reindex"));
      module.updateDocument(owner, "a", Map.of("folder_id", folder));
    }
    try (var module = new AuthorityTestContext(directory)) {
      var result = items(module.listDocuments(reader, Map.of())).getFirst();
      assertEquals("新标题", result.get("display_name"));
      assertEquals(folder, result.get("folder_id"));
      assertEquals("Original.pdf", result.get("filename"));
      assertEquals("revision-a", result.get("registered_revision_id"));
      assertNull(result.get("active_revision_id"));
      assertNull(result.get("index_publication_id"));
      assertEquals(false, result.get("can_edit"));
      assertEquals(2, items(module.auditEvents(owner)).size());
      var audit = items(module.auditEvents(editor)).getFirst();
      assertEquals("document_updated", audit.get("action"));
      assertFalse(audit.toString().contains("新标题"));
      assertFalse(audit.get("before_sha256").equals(audit.get("after_sha256")));
    }
  }

  @Test
  void authorizationPrecedesPaginationCountsTagsAndFolderVisibility() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "Same.pdf", Map.of("reader", "reader"));
      seed(module, "b", "Same.pdf", Map.of());
      seed(module, "c", "z-last.pdf", Map.of("reader", "reader"));
      var folder = module.createFolder(owner, Map.of("name", "共享"));
      var empty = module.createFolder(owner, Map.of("name", "私有空目录"));
      for (String id : List.of("a", "b", "c")) {
        module.updateDocument(
            owner,
            id,
            Map.of(
                "folder_id",
                folder.get("folder_id"),
                "tags",
                List.of(id.equals("b") ? "secret" : "public")));
      }
      var first = module.listDocuments(reader, Map.of("page_size", "1", "sort", "name_asc"));
      assertEquals(2L, first.get("total"));
      assertEquals(2L, first.get("total_pages"));
      assertEquals("a", items(first).getFirst().get("document_id"));
      assertEquals(
          "c",
          items(
                  module.listDocuments(
                      reader, Map.of("page_size", "1", "page", "2", "sort", "name_asc")))
              .getFirst()
              .get("document_id"));
      assertEquals(List.of("public"), module.listTags(reader).get("items"));
      var folders = items(module.listFolders(reader));
      assertEquals(1, folders.size());
      assertEquals(2L, folders.getFirst().get("document_count"));
      assertEquals(false, folders.getFirst().get("can_edit"));
      assertTrue(items(module.listFolders(stranger)).isEmpty());
      assertTrue(items(module.listDocuments(other, Map.of())).isEmpty());
      assertTrue(items(module.listFolders(other)).isEmpty());
      assertEquals(List.of(), module.listTags(other).get("items"));
      fails(404, () -> module.updateDocument(reader, "a", Map.of("display_name", "bad")));
      fails(404, () -> module.updateDocument(other, "a", Map.of("display_name", "bad")));
      fails(404, () -> module.updateDocument(reader, "b", Map.of("display_name", "bad")));
      fails(
          404,
          () ->
              module.renameFolder(reader, (String) folder.get("folder_id"), Map.of("name", "bad")));
      fails(404, () -> module.removeFolder(reader, (String) folder.get("folder_id")));
      fails(
          404,
          () -> module.updateDocument(stranger, "a", Map.of("folder_id", empty.get("folder_id"))));
    }
  }

  @Test
  void patchOmissionNullAndBoundsAreStrictAndAtomic() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "a.pdf", Map.of());
      var folder = module.createFolder(owner, Map.of("name", "folder"));
      module.updateDocument(
          owner, "a", Map.of("folder_id", folder.get("folder_id"), "tags", List.of("old")));
      var patch = new HashMap<String, Object>();
      patch.put("folder_id", null);
      var result = module.updateDocument(owner, "a", patch);
      assertNull(result.get("folder_id"));
      assertEquals(List.of("old"), result.get("tags"));
      result = module.updateDocument(owner, "a", Map.of("tags", List.of()));
      assertEquals(List.of(), result.get("tags"));
      for (Map<String, Object> invalid :
          List.<Map<String, Object>>of(
              Map.of(),
              Map.of("unknown", true),
              Map.of("display_name", " "),
              Map.of("display_name", "a\nb"),
              Map.of("display_name", "x".repeat(256)),
              Map.of("tags", List.of("x\u0000y")),
              Map.of("display_name", "x\u0085y"),
              Map.of("display_name", "x\uD800y"),
              Map.of("tags", List.of("x".repeat(41))),
              Map.of("tags", "bad"),
              Map.of("folder_id", 12),
              Map.of("display_name", 42),
              Map.of("folder_id", ""))) {
        fails(422, () -> module.updateDocument(owner, "a", invalid));
      }
      for (String field : List.of("display_name", "tags")) {
        var invalid = new HashMap<String, Object>();
        invalid.put(field, null);
        fails(422, () -> module.updateDocument(owner, "a", invalid));
      }
      var before = items(module.auditEvents(owner)).size();
      fails(
          404,
          () ->
              module.updateDocument(
                  owner, "a", Map.of("display_name", "should-not-commit", "folder_id", "missing")));
      assertEquals(before, items(module.auditEvents(owner)).size());
      assertEquals(
          "a.pdf", items(module.listDocuments(owner, Map.of())).getFirst().get("display_name"));
      assertEquals(
          "😀".repeat(255),
          module
              .updateDocument(owner, "a", Map.of("display_name", "😀".repeat(255)))
              .get("display_name"));
    }
  }

  @Test
  void batchHasPartialResultsAppendsTagsAndRejectsUnsafeCommands() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "a.pdf", Map.of("reader", "reader"));
      module.updateDocument(owner, "a", Map.of("tags", List.of("old")));
      var result =
          items(
              module.documentActions(
                  owner,
                  Map.of(
                      "document_ids",
                      List.of("a", "missing"),
                      "action",
                      "tag",
                      "tags",
                      List.of("new", "old"))));
      assertEquals(true, result.getFirst().get("ok"));
      assertEquals(false, result.get(1).get("ok"));
      assertEquals("not_found", result.get(1).get("error_code"));
      assertEquals(
          List.of("old", "new"),
          items(module.listDocuments(owner, Map.of())).getFirst().get("tags"));
      var opaqueId =
          items(
              module.documentActions(
                  owner,
                  Map.of(
                      "document_ids",
                      List.of(" a "),
                      "action",
                      "tag",
                      "tags",
                      List.of("must-not-add"))));
      assertEquals(false, opaqueId.getFirst().get("ok"));
      assertEquals(
          List.of("old", "new"),
          items(module.listDocuments(owner, Map.of())).getFirst().get("tags"));
      var tooMany = new ArrayList<String>();
      for (int i = 0; i < 20; i++) {
        tooMany.add("tag" + i);
      }
      result =
          items(
              module.documentActions(
                  owner, Map.of("document_ids", List.of("a"), "action", "tag", "tags", tooMany)));
      assertEquals("tag_limit_reached", result.getFirst().get("error_code"));
      assertEquals(
          List.of("old", "new"),
          items(module.listDocuments(owner, Map.of())).getFirst().get("tags"));
      for (Map<String, Object> command :
          List.<Map<String, Object>>of(
              Map.of("document_ids", List.of("a", "a"), "action", "tag", "tags", List.of("x")),
              Map.of("document_ids", List.of("a"), "action", "tag", "tags", List.of()),
              Map.of("document_ids", List.of("a"), "action", "move"),
              Map.of("document_ids", List.of(), "action", "move", "folder_id", "f"),
              Map.of(
                  "document_ids",
                  List.of("a"),
                  "action",
                  "move",
                  "folder_id",
                  "f",
                  "tags",
                  List.of("x")))) {
        fails(422, () -> module.documentActions(owner, command));
      }
      fails(
          501,
          () ->
              module.documentActions(
                  owner, Map.of("document_ids", List.of("a"), "action", "delete")));
      fails(
          501,
          () ->
              module.documentActions(
                  owner, Map.of("document_ids", List.of("a"), "action", "reindex")));
    }
  }

  @Test
  void folderConflictsOwnershipAndNonEmptyProtection() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "a.pdf", Map.of());
      String folder =
          (String) module.createFolder(owner, Map.of("name", "Folder")).get("folder_id");
      fails(409, () -> module.createFolder(owner, Map.of("name", "folder")));
      module.createFolder(reader, Map.of("name", "folder"));
      var second = module.createFolder(owner, Map.of("name", "Second"));
      fails(
          409,
          () ->
              module.renameFolder(
                  owner, (String) second.get("folder_id"), Map.of("name", "FOLDER")));
      module.updateDocument(owner, "a", Map.of("folder_id", folder));
      assertEquals("folder_not_empty", fails(409, () -> module.removeFolder(owner, folder)).code());
      assertEquals(
          "Changed", module.renameFolder(owner, folder, Map.of("name", "Changed")).get("name"));
      var unfile = new HashMap<String, Object>();
      unfile.put("folder_id", null);
      module.updateDocument(owner, "a", unfile);
      assertEquals("removed", module.removeFolder(owner, folder).get("status"));
      fails(404, () -> module.removeFolder(owner, folder));
    }
  }

  @Test
  void queryFiltersSortingAndInvalidInput() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "Needle.pdf", Map.of());
      seed(module, "b", "B.pdf", Map.of());
      assertEquals(
          1L,
          module
              .listDocuments(
                  owner,
                  Map.of(
                      "q", "needle", "type", "document", "status", "ready", "folder_id", "unfiled"))
              .get("total"));
      assertEquals(0L, module.listDocuments(owner, Map.of("q", "%")).get("total"));
      assertEquals(0L, module.listDocuments(owner, Map.of("tag", "not-here")).get("total"));
      assertEquals(
          "a",
          items(module.listDocuments(owner, Map.of("sort", "name_desc")))
              .getFirst()
              .get("document_id"));
      assertEquals(
          "a",
          items(module.listDocuments(owner, Map.of("sort", "updated_asc")))
              .getFirst()
              .get("document_id"));
      for (Map<String, String> query :
          List.of(
              Map.of("page", "0"),
              Map.of("page", "1000001"),
              Map.of("page", "oops"),
              Map.of("page_size", "101"),
              Map.of("sort", "DROP TABLE"),
              Map.of("type", "unknown"),
              Map.of("status", "unknown"),
              Map.of("q", "x".repeat(201)))) {
        fails(422, () -> module.listDocuments(owner, query));
      }
    }
  }

  @Test
  void databaseRejectsForeignFormatAndConcurrentWriter() throws Exception {
    try (var module = new AuthorityTestContext(directory)) {
      assertEquals(0L, module.listDocuments(owner, Map.of()).get("total"));
      assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(directory));
    }
    var foreign = Files.createDirectory(directory.resolve("foreign"));
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + foreign.resolve("java-library.db"))) {
      connection.createStatement().execute("CREATE TABLE documents (secret TEXT)");
    }
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(foreign));
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + foreign.resolve("java-library.db"))) {
      try (var rows =
          connection
              .createStatement()
              .executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void failedAuditInsertRollsBackMetadataAndDoesNotLeakInternalErrors() throws Exception {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "original.pdf", Map.of());
      try (var connection =
          DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"))) {
        connection
            .createStatement()
            .execute(
                "CREATE TRIGGER injected_failure BEFORE INSERT ON management_audit BEGIN SELECT RAISE(ABORT,'internal-secret-message'); END");
      }
      var error =
          fails(
              503,
              () ->
                  module.updateDocument(
                      owner, "a", Map.of("display_name", "changed", "tags", List.of("new"))));
      assertFalse(error.getMessage().contains("internal-secret-message"));
      var document = items(module.listDocuments(owner, Map.of())).getFirst();
      assertEquals("original.pdf", document.get("display_name"));
      assertEquals(List.of(), document.get("tags"));
      assertTrue(items(module.auditEvents(owner)).isEmpty());
    }
  }

  @Test
  void foldersDoNotGrantPermissionAndBulkMoveMayUnfile() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "a.pdf", Map.of("editor", "editor"));
      seed(module, "b", "b.pdf", Map.of());
      String folder =
          (String) module.createFolder(owner, Map.of("name", "owner-folder")).get("folder_id");
      fails(404, () -> module.updateDocument(editor, "a", Map.of("folder_id", folder)));
      module.updateDocument(owner, "a", Map.of("folder_id", folder));
      module.updateDocument(owner, "b", Map.of("folder_id", folder));
      assertEquals(1L, module.listDocuments(editor, Map.of("folder_id", folder)).get("total"));
      var patch = new HashMap<String, Object>();
      patch.put("document_ids", List.of("a", "b"));
      patch.put("action", "move");
      patch.put("folder_id", null);
      var result = items(module.documentActions(editor, patch));
      assertEquals(true, result.getFirst().get("ok"));
      assertEquals(false, result.get(1).get("ok"));
      assertTrue(items(module.listFolders(editor)).isEmpty());
      fails(409, () -> module.removeFolder(owner, folder));
      assertEquals(1L, module.listDocuments(owner, Map.of("folder_id", folder)).get("total"));
    }
  }

  @Test
  void fixtureValidationNoOverwriteAndClosedModule() {
    var module = new AuthorityTestContext(directory);
    seed(module, "a", "a.pdf", Map.of());
    fails(409, () -> seed(module, "a", "overwrite.pdf", Map.of()));
    fails(422, () -> seed(module, "b", "b.pdf", Map.of("reader", "admin")));
    fails(422, () -> seed(module, "b", "b.pdf", Map.of("owner", "reader")));
    fails(422, () -> module.registerSyntheticDocument(owner, null, Map.of()));
    fails(
        422,
        () ->
            module.registerSyntheticDocument(
                owner,
                new SyntheticDocument("b", "b.pdf", "unknown", "x", "rev", "a".repeat(64), 0),
                Map.of()));
    fails(
        422,
        () ->
            module.registerSyntheticDocument(
                owner,
                new SyntheticDocument("b", "b.pdf", "document", "x", "rev", "bad", 0),
                Map.of()));
    fails(
        422,
        () ->
            module.registerSyntheticDocument(
                owner,
                new SyntheticDocument("b", "b.pdf", "document", "x", "rev", "a".repeat(64), -1),
                Map.of()));
    module.close();
    fails(503, () -> module.listDocuments(owner, Map.of()));
    module.close();
  }

  @Test
  void rejectsMalformedCollectionsFieldsAndFolderCommands() {
    try (var module = new AuthorityTestContext(directory)) {
      seed(module, "a", "a.pdf", Map.of());
      for (Map<String, Object> body :
          List.<Map<String, Object>>of(
              Map.of(),
              Map.of("name", 1),
              Map.of("name", "ok", "unexpected", "no"),
              Map.of("name", "x".repeat(81)))) {
        fails(422, () -> module.createFolder(owner, body));
      }
      var many = new ArrayList<String>();
      for (int i = 0; i < 21; i++) {
        many.add("tag" + i);
      }
      fails(422, () -> module.updateDocument(owner, "a", Map.of("tags", many)));
      var badTag = new ArrayList<Object>();
      badTag.add(null);
      fails(422, () -> module.updateDocument(owner, "a", Map.of("tags", badTag)));
      for (Map<String, Object> body :
          List.<Map<String, Object>>of(
              Map.of(),
              Map.of("document_ids", "a", "action", "tag"),
              Map.of("document_ids", List.of(1), "action", "tag"),
              Map.of("document_ids", List.of("a"), "action", 1),
              Map.of("document_ids", List.of("a"), "action", "unknown"),
              Map.of(
                  "document_ids",
                  List.of("a"),
                  "action",
                  "tag",
                  "tags",
                  List.of("x"),
                  "folder_id",
                  "folder"),
              Map.of("document_ids", List.of("a"), "action", "tag", "unknown", true))) {
        fails(422, () -> module.documentActions(owner, body));
      }
      fails(422, () -> module.listDocuments(owner, Map.of("unknown", "x")));
      fails(422, () -> module.listDocuments(owner, Map.of("folder_id", "x".repeat(101))));
      fails(422, () -> module.listDocuments(owner, Map.of("tag", "x".repeat(41))));
      assertEquals(0L, module.listDocuments(owner, Map.of("status", "processing")).get("total"));
      assertEquals(0L, module.listDocuments(owner, Map.of("type", "video")).get("total"));
      assertEquals(1L, module.listDocuments(owner, Map.of("folder_id", "")).get("total"));
      module.updateDocument(owner, "a", Map.of("tags", List.of("real")));
      assertEquals(1L, module.listDocuments(owner, Map.of("tag", "real")).get("total"));
    }
  }

  @Test
  void refusesLegacyDatabaseAndSymlink() throws Exception {
    var legacy = Files.createDirectory(directory.resolve("legacy"));
    Files.createFile(legacy.resolve("rag.db"));
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(legacy));
    var alias = directory.resolve("alias");
    Files.createSymbolicLink(alias, legacy);
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(alias));
    var unsafe = Files.createDirectory(directory.resolve("unsafe"));
    Files.createSymbolicLink(unsafe.resolve("java-library.db"), legacy.resolve("rag.db"));
    assertThrows(IllegalStateException.class, () -> new AuthorityTestContext(unsafe));
    assertEquals(0, Files.size(legacy.resolve("rag.db")));
  }

  @Test
  void canonicalParentAliasUsesSameDatabaseAndLifetimeLock() throws Exception {
    var parent = Files.createDirectory(directory.resolve("real"));
    var alias = directory.resolve("parent-alias");
    Files.createSymbolicLink(alias, parent);
    try (var module = new AuthorityTestContext(parent.resolve("library"))) {
      seed(module, "a", "a.pdf", Map.of());
      assertThrows(
          IllegalStateException.class, () -> new AuthorityTestContext(alias.resolve("library")));
    }
    try (var reopened = new AuthorityTestContext(alias.resolve("library"))) {
      assertEquals(1L, reopened.listDocuments(owner, Map.of()).get("total"));
    }
  }
}
