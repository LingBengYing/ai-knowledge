package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.entity.SynopsisTaskEntity;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoSubtitleMigrationTest {
  private static final List<String> SYNOPSIS_TABLES =
      List.of(
          "synopsis_tasks", "synopsis_input_evidence", "synopsis_entries", "synopsis_references");
  private static final List<String> SUBTITLE_TABLES =
      List.of(
          "video_subtitle_compilations",
          "video_subtitle_tracks",
          "video_subtitle_cues",
          "video_subtitle_publication_entries",
          "video_subtitle_trace_evidence");

  @TempDir Path directory;

  @Test
  void newStoreCreatesVersionFifteenWithAllSubtitleSidecarsAndSynopsisKind() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION,
                store.count("SELECT version FROM format_info"));
            for (String table :
                List.of(
                    "video_subtitle_compilations",
                    "video_subtitle_tracks",
                    "video_subtitle_cues",
                    "video_subtitle_publication_entries",
                    "video_subtitle_trace_evidence")) {
              assertEquals(
                  1,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", table));
            }
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('synopsis_input_evidence') WHERE name='video_subtitle_cue_id'"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void independentSubtitleTablesAndCompleteSealsExistWithoutEnablingRuntime() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                3,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('corpus_revision_video_subtitle_complete','index_publication_video_subtitle_complete','video_subtitle_trace_evidence_identity')"));
            assertEquals(
                1,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='synopsis_input_evidence' AND sql LIKE '%VIDEO_SUBTITLE%' AND sql LIKE '%ordinal BETWEEN 0 AND 4095%'"));
            return null;
          });
    }
  }

  @Test
  void versionFourteenUpgradeKeepsOldSynopsisHistoryAndRestorableBackup() throws Exception {
    FileSynopsis expected;
    SynopsisTaskEntity processing;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var input = SynopsisRepositoryTest.input(fixture);
      expected = SynopsisRepositoryTest.synopsis(input);
      processing =
          fixture
              .authority
              .store()
              .transaction(
                  () -> {
                    var repository = new SynopsisRepository(fixture.authority.store());
                    repository.insertTask(
                        "available-history",
                        SynopsisRepositoryTest.OWNER,
                        input.publication(),
                        SynopsisRepositoryTest.MODEL,
                        SynopsisRepositoryTest.POLICY,
                        SynopsisRepositoryTest.NOW);
                    assertTrue(
                        repository.markProcessing(
                            "available-history",
                            SynopsisRepositoryTest.CLAIM_HASH,
                            input,
                            SynopsisRepositoryTest.NOW));
                    assertTrue(
                        repository.complete(
                            "available-history",
                            SynopsisRepositoryTest.CLAIM_HASH,
                            expected,
                            SynopsisRepositoryTest.NOW));
                    repository.insertTask(
                        "processing-history",
                        SynopsisRepositoryTest.OWNER,
                        input.publication(),
                        SynopsisRepositoryTest.MODEL,
                        SynopsisRepositoryTest.POLICY,
                        SynopsisRepositoryTest.NOW);
                    assertTrue(
                        repository.markProcessing(
                            "processing-history",
                            SynopsisRepositoryTest.CLAIM_HASH,
                            input,
                            SynopsisRepositoryTest.NOW));
                    return repository.findTask("processing-history").orElseThrow();
                  });
    }
    restoreVersionFourteen(directory);
    for (int opening = 0; opening < 2; opening++) {
      try (var store = new SqliteAuthorityStore(directory)) {
        store.transaction(
            () -> {
              assertEquals(
                  HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
              assertEquals(
                  expected,
                  new SynopsisRepository(store).findSynopsis("available-history").orElseThrow());
              assertEquals(
                  processing,
                  new SynopsisRepository(store).findTask("processing-history").orElseThrow());
              assertEquals(
                  18,
                  store.count(
                      "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name NOT GLOB 'cleanup_*' AND tbl_name IN ('synopsis_tasks','synopsis_input_evidence','synopsis_entries','synopsis_references')"));
              assertEquals(
                  List.of(
                      "cleanup_synopsis_entries_insert",
                      "cleanup_synopsis_entries_purge",
                      "cleanup_synopsis_entries_replace"),
                  store
                      .rows(
                          "SELECT name FROM sqlite_master WHERE type='trigger' AND name GLOB 'cleanup_*' AND tbl_name IN ('synopsis_tasks','synopsis_input_evidence','synopsis_entries','synopsis_references') ORDER BY name")
                      .stream()
                      .map(row -> row.get("name"))
                      .toList());
              assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
              return null;
            });
      }
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  path ->
                      path.getFileName().toString().startsWith("java-library.v14-before-v15-")
                          && path.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backups.getFirst());
        var statement = connection.createStatement();
        var version = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(version.next());
      assertEquals(14, version.getInt(1));
    }
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var store = new SqliteAuthorityStore(restored)) {
      store.transaction(
          () -> {
            assertEquals(
                expected,
                new SynopsisRepository(store).findSynopsis("available-history").orElseThrow());
            assertEquals(
                processing,
                new SynopsisRepository(store).findTask("processing-history").orElseThrow());
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  @Test
  void downgradeFixtureRefusesRealSubtitleAuthorityAndPreservesIt() {
    String revision;
    var expected = VideoSubtitleCompilationFixture.compilation(false);
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var ingestion =
          new IngestionService(
              store,
              new IngestionRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              null,
              null,
              null,
              VideoSubtitleCompilationFixture.COMPILER,
              false);
      var owner = new Actor("org", "owner");
      ingestion.uploadDocument(
          owner, "subtitles.mp4", "video/mp4", VideoSubtitleCompilationFixture.ORIGINAL);
      var claim = ingestion.claimIngestion(owner.workspaceId()).orElseThrow();
      assertTrue(ingestion.completeVideoIngestion(claim, expected));
      revision = claim.revisionId();
    }
    assertThrows(AssertionError.class, () -> restoreVersionFourteen(directory));
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(
                HistoricalSchemaV25Fixture.CURRENT_VERSION, store.count("PRAGMA user_version"));
            assertEquals(5, store.count("SELECT COUNT(*) FROM video_subtitle_cues"));
            assertEquals(
                expected.subtitles(),
                IngestionRepository.readVideoCompilation(store, revision)
                    .orElseThrow()
                    .subtitles());
            assertEquals(0, store.count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
            return null;
          });
    }
  }

  /** Test-only downgrade, rejecting real v3/subtitle data instead of silently removing it. */
  static void restoreVersionFourteen(Path directory) throws SQLException {
    QueryTraceMigrationTest.restoreVersionFifteen(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      int current;
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        current = version.getInt(1);
      }
      if (current <= 14) {
        return;
      }
      assertEquals(15, current);
      statement.execute("PRAGMA foreign_keys=ON");
      connection.setAutoCommit(false);
      for (String table : SUBTITLE_TABLES) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
          assertTrue(rows.next());
          assertEquals(0, rows.getInt(1), "Cannot discard subtitle authority");
        }
      }
      try (var rows =
          statement.executeQuery(
              "SELECT COUNT(*) FROM corpus_revisions WHERE parser_revision LIKE 'java-video-compiler-v3:%'")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "Cannot discard v3 preparation contract");
      }
      try (var rows =
          statement.executeQuery(
              "SELECT COUNT(*) FROM synopsis_input_evidence WHERE kind='VIDEO_SUBTITLE' OR video_subtitle_cue_id IS NOT NULL")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1), "Cannot discard subtitle synopsis history");
      }
      var definitions = new LinkedHashMap<String, String>();
      var synopsisTriggers = new LinkedHashMap<String, String>();
      var sharedTriggers = new LinkedHashMap<String, String>();
      var subtitleTriggers = new ArrayList<String>();
      var indexes = new ArrayList<String>();
      try (var rows =
          statement.executeQuery(
              "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name")) {
        while (rows.next()) {
          String type = rows.getString(1);
          String name = rows.getString(2);
          String table = rows.getString(3);
          String sql = rows.getString(4);
          if (SYNOPSIS_TABLES.contains(table)) {
            switch (type) {
              case "table" -> definitions.put(name, sql);
              case "trigger" -> synopsisTriggers.put(name, sql);
              case "index" -> indexes.add(sql);
              default -> throw new IllegalStateException("Unexpected fixture object");
            }
          } else if (type.equals("trigger")) {
            if (name.startsWith("video_subtitle_")
                || name.endsWith("_no_subtitle_collision")
                || name.equals("corpus_revision_video_subtitle_complete")
                || name.equals("index_publication_video_subtitle_complete")) {
              subtitleTriggers.add(name);
            } else if (sql.contains("video_subtitle_")
                || sql.contains("java-video-compiler-v[123]")
                || name.equals("video_ocr_compilations_identity")) {
              sharedTriggers.put(name, sql);
            }
          }
        }
      }
      for (String table : SYNOPSIS_TABLES) {
        String sql = definitions.get(table);
        var columns = new ArrayList<String>();
        try (var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
          while (rows.next()) {
            if (!rows.getString("name").equals("video_subtitle_cue_id")) {
              columns.add(rows.getString("name"));
            }
          }
        }
        String columnList = String.join(",", columns);
        if (table.equals("synopsis_input_evidence")) {
          sql =
              sql.replace("'VIDEO_OCR','VIDEO_SUBTITLE')", "'VIDEO_OCR')")
                  .replace(",video_subtitle_cue_id TEXT", "")
                  .replace("+(video_subtitle_cue_id IS NOT NULL)", "")
                  .replace(
                      "FOREIGN KEY(publication_id,video_subtitle_cue_id) REFERENCES video_subtitle_publication_entries(publication_id,video_subtitle_cue_id) ON DELETE RESTRICT, ",
                      "");
        }
        for (String name : SYNOPSIS_TABLES) {
          sql = sql.replace(name, "fixture_v14_" + name);
        }
        statement.execute(sql);
        statement.execute(
            "INSERT INTO fixture_v14_"
                + table
                + "("
                + columnList
                + ") SELECT "
                + columnList
                + " FROM "
                + table);
        for (String comparison :
            List.of(
                "SELECT "
                    + columnList
                    + " FROM "
                    + table
                    + " EXCEPT SELECT "
                    + columnList
                    + " FROM fixture_v14_"
                    + table,
                "SELECT "
                    + columnList
                    + " FROM fixture_v14_"
                    + table
                    + " EXCEPT SELECT "
                    + columnList
                    + " FROM "
                    + table)) {
          try (var rows = statement.executeQuery(comparison)) {
            assertTrue(!rows.next(), "Fixture must preserve every old synopsis row");
          }
        }
      }
      for (String name : synopsisTriggers.keySet()) {
        statement.execute("DROP TRIGGER " + name);
      }
      for (String name : subtitleTriggers) {
        statement.execute("DROP TRIGGER " + name);
      }
      for (String name : sharedTriggers.keySet()) {
        statement.execute("DROP TRIGGER " + name);
      }
      for (String table : SYNOPSIS_TABLES.reversed()) {
        statement.execute("DROP TABLE " + table);
      }
      for (String table : SYNOPSIS_TABLES) {
        statement.execute("ALTER TABLE fixture_v14_" + table + " RENAME TO " + table);
      }
      for (String table : SUBTITLE_TABLES.reversed()) {
        statement.execute("DROP TABLE " + table);
      }
      for (String sql : indexes) {
        statement.execute(sql);
      }
      for (var entry : synopsisTriggers.entrySet()) {
        String sql = entry.getValue();
        if (entry.getKey().equals("synopsis_input_evidence_identity")) {
          int start = sql.indexOf(" OR (NEW.kind='VIDEO_SUBTITLE'");
          int end = sql.indexOf("\nBEGIN", start);
          assertTrue(start > 0 && end > start);
          sql = sql.substring(0, start) + ")" + sql.substring(end);
        }
        statement.execute(sql);
      }
      for (String sql : sharedTriggers.values()) {
        sql =
            sql.replace("GLOB 'java-video-compiler-v[123]:*'", "GLOB 'java-video-compiler-v[12]:*'")
                .replace("GLOB 'java-video-compiler-v[23]:*'", "LIKE 'java-video-compiler-v2:%'")
                .replace(
                    "+(SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=r.id AND index_ordinal IS NOT NULL)",
                    "")
                .replace(
                    "+(SELECT COUNT(*) FROM video_subtitle_publication_entries WHERE publication_id=p.id)",
                    "")
                .replace(
                    "+(SELECT COUNT(*) FROM video_subtitle_trace_evidence WHERE trace_id=NEW.id)",
                    "")
                .replace(
                    " UNION ALL SELECT citation_ordinal FROM video_subtitle_trace_evidence WHERE trace_id=NEW.id",
                    "");
        assertTrue(!sql.contains("video_subtitle_"));
        statement.execute(sql);
      }
      try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
        assertTrue(!rows.next());
      }
      statement.execute("UPDATE format_info SET version=14");
      statement.execute("PRAGMA user_version=14");
      connection.commit();
    }
  }
}
