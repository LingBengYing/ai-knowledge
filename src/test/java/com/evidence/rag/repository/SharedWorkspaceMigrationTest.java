package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundSpan;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Physical v29 -> v30 preservation and shared-workspace trace contracts, with zero remote calls.
 */
class SharedWorkspaceMigrationTest {
  private static final String SHA = "a".repeat(64);
  private static final String NOW = "2026-10-08T00:00:00Z";
  private static final List<String> TABLES =
      List.of(
          "knowledge_answer_traces",
          "knowledge_answer_documents",
          "knowledge_answer_citations",
          "query_traces",
          "query_trace_documents",
          "sound_traces",
          "sound_trace_documents",
          "video_av_traces",
          "video_av_trace_documents");
  @TempDir Path directory;

  @Test
  void freshSchemaKeepsIdentityAndSealingButNotBusinessCaps() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION,
                store.count("SELECT version FROM format_info"));
            for (String table : TABLES) {
              String sql = sql(store, "table", table);
              assertFalse(sql.contains("scope_count BETWEEN 0 AND 128"), table);
              assertFalse(sql.contains("ordinal BETWEEN 0 AND 127"), table);
              if (table.startsWith("knowledge_answer_")) {
                assertFalse(sql.contains("citation_count BETWEEN 0 AND 32"));
                assertFalse(sql.contains("ordinal BETWEEN 1 AND 32"));
                assertFalse(sql.contains("end_offset-start_offset<=1200"));
              }
              assertEquals(
                  2,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (?,?)",
                      table + "_no_update",
                      table + "_no_delete"));
            }
            for (String name : List.of("sound_traces_complete", "video_av_traces_complete")) {
              String guard = sql(store, "trigger", name);
              assertFalse(guard.contains("document_acl"));
              assertFalse(guard.contains("a.principal_id"));
              assertTrue(guard.contains("d.workspace_id=NEW.workspace_id"));
              assertTrue(guard.contains("d.active_revision_id=p.source_revision_id"));
              assertTrue(guard.contains("d.source_sha256=p.source_sha256"));
              assertTrue(guard.contains("document_tombstones"));
            }
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void physicalV29UpgradePreservesHistoryAndCreatesOneRestorableBackup() throws Exception {
    Path target = createVersionTwentyNine("upgrade");
    Path database = target.resolve("java-library.db");
    var before = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table : TABLES) {
      before.put(table, ReindexVersion23Fixture.rows(database, "SELECT * FROM " + table));
    }
    var guards =
        ReindexVersion23Fixture.rows(
            database,
            "SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name NOT LIKE 'wiki_%' AND (name LIKE '%_sealed' OR name LIKE '%_no_update' OR name LIKE '%_no_delete' OR name LIKE '%_no_replace') ORDER BY name");
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(target)) {
        store.transaction(
            () -> {
              assertEquals(
                  HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
              for (String table : TABLES) {
                assertEquals(before.get(table), store.rows("SELECT * FROM " + table), table);
              }
              assertEquals(
                  guards,
                  store.rows(
                      "SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name NOT LIKE 'wiki_%' AND (name LIKE '%_sealed' OR name LIKE '%_no_update' OR name LIKE '%_no_delete' OR name LIKE '%_no_replace') ORDER BY name"));
              assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              assertEquals(1, store.count("PRAGMA foreign_keys"));
              return null;
            });
        HistoricalSchemaV25Fixture.assertMigrationBackups(
            target, store.managedBackups().files(), 29);
        assertThrows(
            RuntimeException.class,
            () ->
                store.transaction(
                    () -> {
                      store.execute(
                          "UPDATE knowledge_answer_traces SET actor_id='changed' WHERE id='legacy-knowledge'");
                      return null;
                    }));
      }
    }
    Path backup;
    try (var files = Files.list(target)) {
      var backups =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v29-before-v30-")
                          && path.toString().endsWith(".db"))
              .toList();
      assertEquals(1, backups.size());
      backup = backups.getFirst();
    }
    assertEquals(29, scalar(backup, "PRAGMA user_version"));
    assertEquals(
        before.get("knowledge_answer_traces"),
        ReindexVersion23Fixture.rows(backup, "SELECT * FROM knowledge_answer_traces"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backup, restored.resolve("java-library.db"));
    try (var store = new SqliteAuthorityStore(restored)) {
      assertEquals(
          HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
    }
  }

  @Test
  void sealsRealFullLibraryAbove128DocumentsAndMoreThan32KnowledgeCitations() throws Exception {
    try (var authority = new AuthorityTestContext(directory)) {
      var publications = new java.util.ArrayList<ReindexSqlFixture.Published>();
      for (int index = 0; index < 129; index++) {
        publications.add(ReindexSqlFixture.publish(authority));
      }
      var store = authority.store();
      store.transaction(
          () -> {
            for (int ordinal = 0; ordinal < publications.size(); ordinal++) {
              String publication = publications.get(ordinal).publicationId();
              store.execute(
                  "INSERT INTO knowledge_answer_documents VALUES(?,?,?)",
                  "large-knowledge",
                  ordinal,
                  publication);
              store.execute(
                  "INSERT INTO query_trace_documents VALUES(?,?,?)",
                  "large-query",
                  ordinal,
                  publication);
              if (ordinal < 33) {
                store.execute(
                    """
                INSERT INTO knowledge_answer_citations
                SELECT ?,?,e.publication_id,'DOCUMENT_TEXT',e.physical_segment_id,s.start_offset,s.end_offset,
                  pg.text_sha256,s.text_sha256,s.page_number,NULL,NULL
                FROM index_publication_entries e JOIN corpus_segments s ON s.id=e.source_segment_id
                  JOIN corpus_pages pg ON pg.revision_id=s.revision_id AND pg.page_number=s.page_number
                WHERE e.publication_id=? ORDER BY s.ordinal LIMIT 1
                """,
                    "large-knowledge",
                    ordinal + 1,
                    publication);
              }
            }
            store.execute(
                "INSERT INTO knowledge_answer_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "large-knowledge",
                ReindexSqlFixture.OWNER.workspaceId(),
                "member-2",
                1,
                129,
                33,
                SHA,
                SHA,
                "answered",
                null,
                "model-v1",
                "prompt-v1",
                "policy-v1",
                NOW);
            store.execute(
                "INSERT INTO query_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "large-query",
                ReindexSqlFixture.OWNER.workspaceId(),
                "member-2",
                1,
                129,
                0,
                SHA,
                null,
                "abstained",
                "no_evidence",
                "model-v1",
                "prompt-v1",
                "policy-v1",
                NOW);
            assertEquals(
                129,
                store.count(
                    "SELECT COUNT(*) FROM knowledge_answer_documents WHERE trace_id='large-knowledge'"));
            assertEquals(
                33,
                store.count(
                    "SELECT COUNT(*) FROM knowledge_answer_citations WHERE trace_id='large-knowledge'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "DELETE FROM knowledge_answer_citations WHERE trace_id='large-knowledge'");
                    return null;
                  }));
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      assertEquals(
          33,
          reopened.transaction(
              () ->
                  reopened.count(
                      "SELECT citation_count FROM knowledge_answer_traces WHERE id='large-knowledge'")));
    }
  }

  @Test
  void soundTraceAcceptsSecondMemberWithoutAclButStillRejectsWrongWorkspaceOrDeletedSource() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication = registerSound(store);
      sealSound(store, "shared", "org", publication.id());
      assertThrows(
          RuntimeException.class, () -> sealSound(store, "wrong", "other", publication.id()));
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO document_tombstones VALUES(?,?,?,?)",
                publication.documentId(),
                "org",
                "member-2",
                NOW);
            return null;
          });
      assertThrows(
          RuntimeException.class, () -> sealSound(store, "deleted", "org", publication.id()));
    }
  }

  @Test
  void videoTraceAcceptsSecondMemberWithoutAclAndStillRejectsMissingScope() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var claim = VideoAvTestFixture.claim(false);
      var original = claim.original();
      var publication = VideoAvTestFixture.publication(claim);
      store.transaction(
          () -> {
            registerDocument(store, original);
            new VideoAvRepository(store).insertOriginal(original, NOW);
            new VideoAvRepository(store).insertPublication(publication);
            store.execute(
                "INSERT INTO video_av_trace_documents VALUES(?,?,?)",
                "shared",
                0,
                publication.id());
            insertVideoTrace(store, "shared", "org", 1, publication.analysisModelRevision());
            return null;
          });
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    insertVideoTrace(
                        store, "omitted", "org", 0, publication.analysisModelRevision());
                    return null;
                  }));
      assertThrows(
          RuntimeException.class,
          () ->
              store.transaction(
                  () -> {
                    store.execute(
                        "INSERT INTO video_av_trace_documents VALUES(?,?,?)",
                        "wrong",
                        0,
                        publication.id());
                    insertVideoTrace(
                        store, "wrong", "other", 1, publication.analysisModelRevision());
                    return null;
                  }));
    }
  }

  @Test
  void alteredSealingGuardIsRejectedWithoutRewritingHistoryOrVersion() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      assertEquals(
          HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path database = directory.resolve("java-library.db");
    ReindexVersion23Fixture.execute(database, "DROP TRIGGER knowledge_answer_citations_sealed");
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
    assertEquals(
        HistoricalSchemaV25Fixture.CURRENT_VERSION, scalar(database, "PRAGMA user_version"));
  }

  private Path createVersionTwentyNine(String name) throws Exception {
    Path seed = directory.resolve(name + "-seed");
    IngestionMigrationTest.versionOne(seed);
    Path seedDatabase = seed.resolve("java-library.db");
    for (String statement :
        List.of(
            "CREATE INDEX documents_workspace ON documents(workspace_id,updated_at,id)",
            "CREATE INDEX documents_folder ON documents(folder_id,workspace_id)",
            "CREATE INDEX acl_principal ON document_acl(principal_id,document_id)",
            "CREATE INDEX audit_actor ON management_audit(workspace_id,actor_id,created_at)")) {
      ReindexVersion23Fixture.execute(seedDatabase, statement);
    }
    try (var store = new SqliteAuthorityStore(seed)) {
      assertEquals(
          HistoricalSchemaV25Fixture.CURRENT_VERSION,
          store.transaction(() -> store.count("PRAGMA user_version")));
    }
    Path backup;
    try (var files = Files.list(seed)) {
      backup =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v29-before-v30-")
                          && path.toString().endsWith(".db"))
              .findFirst()
              .orElseThrow();
    }
    Path target = Files.createDirectory(directory.resolve(name));
    Path database = target.resolve("java-library.db");
    Files.copy(backup, database);
    assertEquals(29, scalar(database, "PRAGMA user_version"));
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      for (String table : List.of("knowledge_answer_traces", "query_traces")) {
        statement.execute(
            "INSERT INTO "
                + table
                + " VALUES('legacy-"
                + (table.startsWith("knowledge") ? "knowledge" : "query")
                + "','org','owner',1,0,0,'"
                + SHA
                + "',NULL,'abstained','no_evidence','model-v1','prompt-v1','policy-v1','"
                + NOW
                + "')");
      }
      statement.execute(
          "INSERT INTO sound_traces VALUES('legacy-sound','org','owner',1,0,0,'"
              + SHA
              + "',NULL,'abstained','no_evidence','sound-v1','java-sound-answer-v1','"
              + NOW
              + "')");
      statement.execute(
          "INSERT INTO video_av_traces VALUES('legacy-video','org','owner',1,0,0,'VISUAL','"
              + SHA
              + "',NULL,'abstained','no_evidence','analysis-v1','java-video-av-answer-v1','"
              + NOW
              + "')");
    }
    return target;
  }

  private static String sql(SqliteAuthorityStore store, String type, String name) {
    return (String)
        store
            .rows("SELECT sql FROM sqlite_master WHERE type=? AND name=?", type, name)
            .getFirst()
            .get("sql");
  }

  private static long scalar(Path database, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static void registerDocument(SqliteAuthorityStore store, DocumentOriginal original) {
    new ManagementRepository(store)
        .insertDocument(
            new Actor("org", "owner"),
            new SyntheticDocument(
                original.documentId(),
                original.filename(),
                original.documentType(),
                original.mediaType(),
                original.revisionId(),
                original.sourceSha256(),
                original.sizeBytes()),
            NOW);
  }

  private static SoundPublication registerSound(SqliteAuthorityStore store) {
    byte[] bytes = AudioPcm.wav(new byte[2], 0, 2);
    var original =
        new DocumentOriginal(
            "sound-doc",
            "sound-rev",
            "tone.wav",
            "audio",
            "audio/wav",
            ModelValues.sha256(bytes),
            bytes.length,
            bytes);
    String generation = UUID.randomUUID().toString();
    var target = new IndexTarget("embedding-v1", SHA, "embedding-v1", 2);
    String id = SoundProfile.spanId(original.revisionId(), 0);
    var spans =
        List.of(
            new SoundSpan(
                id,
                0,
                0,
                1,
                "b".repeat(64),
                "",
                SoundProfile.physicalSegmentId(generation, id),
                "c".repeat(64)));
    var publication =
        new SoundPublication(
            "sound-pub",
            "org",
            original.documentId(),
            original.revisionId(),
            original.sourceSha256(),
            original.filename(),
            original.mediaType(),
            original.sizeBytes(),
            generation,
            target,
            "sound-v1",
            "decoder-v1",
            1,
            1,
            spans,
            SoundProfile.manifestSha256("org", original.documentId(), generation, spans),
            SoundProfile.fingerprint(target, "sound-v1", "decoder-v1", 1),
            NOW);
    store.transaction(
        () -> {
          registerDocument(store, original);
          new SoundRepository(store).insertOriginal(original, NOW);
          new SoundRepository(store).insertPublication(publication);
          return null;
        });
    return publication;
  }

  private static void sealSound(
      SqliteAuthorityStore store, String id, String workspace, String publication) {
    store.transaction(
        () -> {
          store.execute("INSERT INTO sound_trace_documents VALUES(?,?,?)", id, 0, publication);
          store.execute(
              "INSERT INTO sound_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              workspace,
              "member-2",
              1,
              1,
              0,
              SHA,
              null,
              "abstained",
              "no_evidence",
              "sound-v1",
              "java-sound-answer-v1",
              NOW);
          return null;
        });
  }

  private static void insertVideoTrace(
      SqliteAuthorityStore store, String id, String workspace, int scopeCount, String revision) {
    store.execute(
        "INSERT INTO video_av_traces VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        workspace,
        "member-2",
        1,
        scopeCount,
        0,
        "VISUAL",
        SHA,
        null,
        "abstained",
        "no_evidence",
        revision,
        "java-video-av-answer-v1",
        NOW);
  }
}
