package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.repository.SqliteAuthorityStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

class SoundAuthorityBoundaryTest {
  @TempDir Path directory;

  @Test
  void independentSoundRegistrationDoesNotAcceptATextOriginal() {
    try (var fixture = new SoundTestFixture(directory)) {
      byte[] bytes = new byte[] {65};
      var text =
          new DocumentOriginal(
              "note",
              "note-v1",
              "note.txt",
              "document",
              "text/plain",
              ModelValues.sha256(bytes),
              bytes.length,
              bytes);
      assertThrows(
          ApplicationException.class,
          () ->
              fixture.store.transaction(
                  () -> {
                    fixture.repository.insertOriginal(text, "2026-10-03T00:00:00Z");
                    return null;
                  }));
      assertEquals(0, fixture.count("SELECT count(*) FROM sound_originals"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"revision", "source-sha", "filename", "media-type"})
  void aSavedPublicationIsNotReusableForChangedOriginalIdentity(String field) {
    try (var fixture = new SoundTestFixture(directory)) {
      var original = fixture.register("library", SoundTestFixture.pcm(32000, 1), true);
      byte[] content = original.content();
      if (field.equals("source-sha")) {
        content[content.length - 1] ^= 1;
      }
      var changed =
          new DocumentOriginal(
              original.documentId(),
              field.equals("revision") ? "new-source" : original.revisionId(),
              field.equals("filename") ? "renamed.wav" : original.filename(),
              "audio",
              field.equals("media-type") ? "audio/flac" : original.mediaType(),
              ModelValues.sha256(content),
              content.length,
              content);
      assertTrue(
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.findPublication(
                          changed, SoundTestFixture.TARGET, SoundTestFixture.PROFILE))
              .isEmpty());
      assertTrue(
          fixture
              .store
              .transaction(
                  () ->
                      fixture.repository.findPublication(
                          original, SoundTestFixture.TARGET, SoundTestFixture.PROFILE))
              .isPresent());
    }
  }

  private static void corrupt(SoundTestFixture fixture, String table, String sql) {
    // Only this fresh synthetic @TempDir database: emulate an offline damaged restore, not an
    // authorized write. Normal immutability and CHECK guards remain covered by schema tests.
    var blocked = assertThrows(AssertionError.class, () -> ordinarySql(fixture.store, sql));
    assertTrue(blocked.getCause() instanceof SQLException);
    assertFalse(blocked.getCause().getMessage().contains("no such function"));
    ordinarySql(fixture.store, "DROP TRIGGER " + table + "_no_update");
    if (table.equals("sound_trace_evidence") && sql.contains("facts_json=")) {
      ordinarySql(fixture.store, "DROP TRIGGER cleanup_sound_trace_evidence_purge");
    }
    ordinarySql(fixture.store, sql);
  }

  private static void ordinarySql(SqliteAuthorityStore store, String sql) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.libraryPath())) {
      // This direct connection has no authorized replacement transaction. Register the v26
      // predicate as false so the actual immutable-identity guard rejects ordinary writes.
      Function.create(
          connection,
          "java_replacement_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
      Function.create(
          connection,
          "java_cleanup_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
      try (var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    } catch (SQLException failure) {
      throw new AssertionError(failure);
    }
  }
}
