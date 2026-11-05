package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioTraceSchemaTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String COMPILER = "java-audio-compiler-v1:" + "c".repeat(64);
  private static final String VISUAL = "trace-fixture-visual-v1";
  private static final String NOW = "2026-09-10T08:00:00Z";
  private static final String HASH = "a".repeat(64);
  private static final String FIRST = "😀前段。";
  private static final String LAST = "预算为42万元。";
  private static final String TRANSCRIPT = "\t\n" + FIRST + "\n\u2003\n" + LAST + "\n";
  private static final byte[] ORIGINAL = AudioPcm.wav(new byte[160032], 0, 160032);
  @TempDir Path directory;

  @Test
  void typedAudioTraceHasActualScopedPublicationForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(LibrarySchema.VERSION, store.count("PRAGMA user_version"));
            assertEquals(LibrarySchema.VERSION, store.count("SELECT version FROM format_info"));
            assertEquals(
                16, store.count("SELECT COUNT(*) FROM pragma_table_info('audio_trace_evidence')"));
            assertEquals(
                3,
                store.count(
                    "SELECT COUNT(DISTINCT id) FROM pragma_foreign_key_list('audio_trace_evidence')"));
            assertEquals(
                0,
                store.count(
                    "SELECT COUNT(*) FROM pragma_table_info('audio_trace_evidence') WHERE name IN ('page_number','start_offset','end_offset','quote','question')"));
            assertEquals(
                5,
                store.count(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('audio_trace_evidence_identity','audio_trace_evidence_sealed','audio_trace_evidence_no_replace','audio_trace_evidence_no_update','audio_trace_evidence_no_delete')"));
            return null;
          });
    }
  }

  @Test
  void hydrationReconstructsFullUnicodeTranscriptIncludingSilentSpans() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ids = publishAudio(fixture);
      var snapshot = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      store.transaction(
          () -> {
            assertTrue(repository.findPublishedAudioEvidence(snapshot, List.of()).isEmpty());
            assertTrue(
                repository.findPublishedAudioEvidence(snapshot, List.of("missing")).isEmpty());
            var items = repository.findPublishedAudioEvidence(snapshot, ids);
            assertEquals(2, items.size());
            for (var item : items) {
              assertEquals(TRANSCRIPT, item.transcript().contextText());
              assertEquals(sha(TRANSCRIPT), item.transcript().contextSha256());
              assertEquals(
                  item.publication().publicationId() + "/audio", item.transcript().contextId());
              assertEquals(item.physicalSegmentId(), item.transcript().physicalId());
              assertEquals(item.span().text(), item.transcript().snippet());
              assertEquals("meeting.wav", item.filename());
              assertEquals("audio/wav", item.mediaType());
            }
            var first =
                items.stream().filter(i -> i.span().ordinal() == 1).findFirst().orElseThrow();
            var last =
                items.stream().filter(i -> i.span().ordinal() == 3).findFirst().orElseThrow();
            assertEquals(2, first.transcript().startCodePoint());
            assertEquals(6, first.transcript().endCodePoint());
            assertEquals(9, last.transcript().startCodePoint());
            assertEquals(17, last.transcript().endCodePoint());
            assertEquals(3000, last.span().startMs());
            assertEquals(5000, last.span().endMs());
            byte[] original =
                repository.findAudioOriginal(OWNER, last.publication(), ORIGINAL.length);
            assertArrayEquals(ORIGINAL, original);
            original[0] = 0;
            assertArrayEquals(
                ORIGINAL, repository.findAudioOriginal(OWNER, last.publication(), ORIGINAL.length));
            assertNull(
                repository.findAudioOriginal(OWNER, last.publication(), ORIGINAL.length - 1));
            assertEquals(0, store.count("SELECT COUNT(*) FROM corpus_pages"));
            return null;
          });
    }
  }

  private static List<String> publishAudio(PublishedCorpusFixture fixture) {
    var ingestion = ingestion(fixture);
    var uploaded = ingestion.uploadDocument(OWNER, "meeting.wav", "audio/wav", ORIGINAL);
    var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeAudioIngestion(
            claim,
            new AudioCompilation(
                ModelValues.sha256(ORIGINAL),
                "decoder-v1",
                "asr-v1",
                COMPILER,
                5001,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "\t"),
                    new AudioTranscriptSpan(1, 1000, 2000, FIRST),
                    new AudioTranscriptSpan(2, 2000, 3000, "\u2003"),
                    new AudioTranscriptSpan(3, 3000, 5000, LAST),
                    new AudioTranscriptSpan(4, 5000, 5001, "")))));
    return publish(fixture, uploaded.documentId());
  }

  private static IngestionService ingestion(PublishedCorpusFixture fixture) {
    var store = fixture.authority.store();
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        null,
        new VisualIngestionOptions(VISUAL),
        COMPILER);
  }

  private static List<String> publish(PublishedCorpusFixture fixture, String document) {
    fixture.authority.indexing().createIndexing(OWNER, document, PublishedCorpusFixture.TARGET);
    var claim = fixture.authority.indexing().claimIndexing(OWNER.workspaceId()).orElseThrow();
    var ids = PublishedCorpusFixture.physicalIds(claim);
    var digests = new LinkedHashMap<String, String>();
    ids.forEach(id -> digests.put(id, HASH));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            OWNER.workspaceId(), document, claim.projectionGenerationId(), digests);
    assertTrue(
        fixture
            .authority
            .indexing()
            .completeIndexing(
                claim,
                digests,
                new VerifiedRevision(
                    PublishedCorpusFixture.TARGET.projectionIdentity(),
                    manifest.sha256(),
                    digests.size())));
    return ids;
  }

  private static EvidenceScope scope(PublishedCorpusFixture fixture) {
    return fixture.evidence.snapshot(
        OWNER, DocumentSelection.allDocuments(), PublishedCorpusFixture.TARGET);
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void execute(Path directory, String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute(sql);
    }
  }
}
