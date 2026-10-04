package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTraceEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.EvidenceScope;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublishedAudioEvidence;
import com.evidence.rag.model.domain.TraceDraft;
import com.evidence.rag.model.domain.TraceEvidence;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.domain.VisualTraceEvidence;
import com.evidence.rag.model.entity.TraceAudioCitationEntity;
import com.evidence.rag.model.entity.TraceCitationEntity;
import com.evidence.rag.model.entity.TraceImageCitationEntity;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.IngestionService;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.AudioPcm;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioTraceMigrationTest {
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
  void versionNineAddsOnlyTypedAudioTraceWithActualScopedPublicationForeignKeys() {
    try (var store = new SqliteAuthorityStore(directory)) {
      store.transaction(
          () -> {
            assertEquals(25, store.count("PRAGMA user_version"));
            assertEquals(25, store.count("SELECT version FROM format_info"));
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
  void versionEightUpgradePreservesAudioPublicationAndMakesOneRestorableBackup() throws Exception {
    EvidenceScope before;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      publishAudio(fixture);
      before = scope(fixture);
    }
    restoreVersionEight(directory);
    assertEquals(8, scalar(directory.resolve("java-library.db"), "PRAGMA user_version"));
    for (int opening = 0; opening < 2; opening++) {
      try (var fixture = new PublishedCorpusFixture(directory)) {
        assertEquals(before, scope(fixture));
        fixture
            .authority
            .store()
            .transaction(
                () -> {
                  var repository = new EvidenceRepository(fixture.authority.store());
                  assertEquals(before.publications(), repository.findAudioPublications(before));
                  return null;
                });
      }
    }
    List<Path> backups;
    try (var files = Files.list(directory)) {
      backups =
          files
              .filter(
                  p ->
                      p.getFileName().toString().startsWith("java-library.v8-before-v9-")
                          && p.toString().endsWith(".db"))
              .toList();
    }
    assertEquals(1, backups.size());
    assertEquals(8, scalar(backups.getFirst(), "PRAGMA user_version"));
    assertEquals(5, scalar(backups.getFirst(), "SELECT COUNT(*) FROM audio_spans"));
    assertEquals(2, scalar(backups.getFirst(), "SELECT COUNT(*) FROM audio_publication_entries"));
    assertEquals(
        0,
        scalar(
            backups.getFirst(),
            "SELECT COUNT(*) FROM sqlite_master WHERE name='audio_trace_evidence'"));
    Path restored = Files.createDirectory(directory.resolve("restored"));
    Files.copy(backups.getFirst(), restored.resolve("java-library.db"));
    try (var fixture = new PublishedCorpusFixture(restored)) {
      assertEquals(before, scope(fixture));
    }
  }

  @Test
  void hydrationReconstructsFullUnicodeTranscriptIncludingSilentSpansAndCountsItOnce() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ids = publishAudio(fixture);
      var snapshot = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      store.transaction(
          () -> {
            assertEquals(snapshot.publications(), repository.findAudioPublications(snapshot));
            assertEquals(
                TRANSCRIPT.getBytes(StandardCharsets.UTF_8).length,
                repository.publishedAudioTranscriptBytes(snapshot, ids));
            assertEquals(
                TRANSCRIPT.getBytes(StandardCharsets.UTF_8).length,
                repository.publishedAudioTranscriptBytes(snapshot, List.of(ids.getLast())));
            assertEquals(0, repository.publishedAudioTranscriptBytes(snapshot, List.of()));
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

  @Test
  void mixedTraceSealsAllThreeKindsAndReopensTheExactAudioExcerptIdentity() throws Exception {
    TraceAudioCitationEntity expected;
    EvidenceScope snapshot;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var textClaim = fixture.publish(OWNER, "文字事实。");
      var imageIds = publishImage(fixture);
      var audioIds = publishAudio(fixture);
      snapshot = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      expected =
          store.transaction(
              () -> {
                var text =
                    repository
                        .findPublishedEvidence(
                            snapshot, PublishedCorpusFixture.physicalIds(textClaim))
                        .getFirst();
                var image = repository.findPublishedImageEvidence(snapshot, imageIds).getFirst();
                var audio =
                    repository
                        .findPublishedAudioEvidence(snapshot, List.of(audioIds.getLast()))
                        .getFirst();
                var textLocator =
                    new TraceEvidence(1, text.physicalSegmentId(), 0, 5, 0.6, 0.7, List.of(HASH));
                var imageLocator =
                    new VisualTraceEvidence(
                        2,
                        image.physicalSegmentId(),
                        0.6,
                        0.7,
                        List.of(HASH),
                        "vision-v1",
                        "visual-proof-v1");
                var audioCitation = audioCitation(audio, 3);
                var draft =
                    new TraceDraft(
                        HASH,
                        HASH,
                        "answered",
                        null,
                        "answer-v1",
                        "prompt-v1",
                        "proof-v1",
                        List.of(textLocator),
                        List.of(imageLocator),
                        List.of(audioCitation.evidence()));
                repository.insertTrace(
                    "mixed-trace",
                    snapshot,
                    draft,
                    List.of(
                        new TraceCitationEntity(
                            textLocator,
                            text.publication().publicationId(),
                            text.segment().segmentId(),
                            text.segment().page(),
                            text.segment().textSha256(),
                            text.pageSha256(),
                            sha("文字事实。"))),
                    List.of(
                        new TraceImageCitationEntity(
                            imageLocator,
                            image.publication().publicationId(),
                            image.image().id(),
                            image.publication().sourceSha256())),
                    List.of(audioCitation),
                    NOW);
                assertEquals(
                    3, store.count("SELECT scope_count FROM query_traces WHERE id='mixed-trace'"));
                assertEquals(
                    3,
                    store.count("SELECT citation_count FROM query_traces WHERE id='mixed-trace'"));
                assertEquals(1, store.count("SELECT COUNT(*) FROM audio_trace_evidence"));
                assertEquals(
                    audioCitation, repository.findTraceAudioCitation(OWNER, "mixed-trace", 3));
                assertNull(repository.findTraceAudioCitation(OWNER, "mixed-trace", 1));
                assertNull(
                    repository.findTraceAudioCitation(new Actor("org", "other"), "mixed-trace", 3));
                return audioCitation;
              });
    }
    for (String mutation :
        List.of(
            "UPDATE audio_trace_evidence SET start_ms=start_ms+1",
            "DELETE FROM audio_trace_evidence",
            "INSERT OR REPLACE INTO audio_trace_evidence SELECT * FROM audio_trace_evidence",
            "INSERT INTO audio_trace_evidence SELECT trace_id,4,publication_id,audio_span_id,physical_segment_id,start_code_point,end_code_point,start_ms,end_ms,source_sha256,text_sha256,transcript_sha256,quote_sha256,retrieval_score,rerank_score,fact_sha256 FROM audio_trace_evidence")) {
      assertThrows(SQLException.class, () -> execute(directory, mutation));
    }
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      store.transaction(
          () -> {
            var repository = new EvidenceRepository(store);
            assertEquals(snapshot, repository.findTraceScope(OWNER, "mixed-trace"));
            assertEquals(expected, repository.findTraceAudioCitation(OWNER, "mixed-trace", 3));
            return null;
          });
    }
  }

  @Test
  void traceRejectsInventedTimeForeignSourceAndExcerptOutsideItsOriginalSpan() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ids = publishAudio(fixture);
      var snapshot = scope(fixture);
      var store = fixture.authority.store();
      var repository = new EvidenceRepository(store);
      var saved =
          store.transaction(
              () ->
                  audioCitation(
                      repository
                          .findPublishedAudioEvidence(snapshot, List.of(ids.getLast()))
                          .getFirst(),
                      1));
      var shifted =
          new AudioTraceEvidence(
              1, saved.evidence().physicalSegmentId(), 2, 6, 0.6, 0.7, List.of(HASH));
      for (var invalid :
          List.of(
              new TraceAudioCitationEntity(
                  saved.evidence(),
                  saved.publicationId(),
                  saved.audioSpanId(),
                  3001,
                  5000,
                  saved.sourceSha256(),
                  saved.textSha256(),
                  saved.transcriptSha256(),
                  saved.quoteSha256()),
              new TraceAudioCitationEntity(
                  saved.evidence(),
                  saved.publicationId(),
                  saved.audioSpanId(),
                  3000,
                  5000,
                  "b".repeat(64),
                  saved.textSha256(),
                  saved.transcriptSha256(),
                  saved.quoteSha256()),
              new TraceAudioCitationEntity(
                  shifted,
                  saved.publicationId(),
                  saved.audioSpanId(),
                  3000,
                  5000,
                  saved.sourceSha256(),
                  saved.textSha256(),
                  saved.transcriptSha256(),
                  saved.quoteSha256()))) {
        var failure =
            assertThrows(
                ApplicationException.class,
                () ->
                    store.transaction(
                        () -> {
                          repository.insertTrace(
                              "invalid-trace",
                              snapshot,
                              draft(invalid.evidence()),
                              List.of(),
                              List.of(),
                              List.of(invalid),
                              NOW);
                          return null;
                        }));
        assertEquals("management_unavailable", failure.code());
      }
      store.transaction(
          () -> {
            assertEquals(0, store.count("SELECT COUNT(*) FROM query_traces"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM query_trace_documents"));
            assertEquals(0, store.count("SELECT COUNT(*) FROM audio_trace_evidence"));
            repository.insertTrace(
                "valid-trace",
                snapshot,
                draft(saved.evidence()),
                List.of(),
                List.of(),
                List.of(saved),
                NOW);
            assertEquals(saved, repository.findTraceAudioCitation(OWNER, "valid-trace", 1));
            return null;
          });
    }
  }

  @Test
  void audioTraceValuesRetainImmutableFactsAndShareTheGlobalCitationOrdinalContract() {
    var facts = new ArrayList<>(List.of(HASH));
    var audio = new AudioTraceEvidence(1, "physical-audio", 9, 17, 0.6, 0.7, facts);
    facts.clear();
    assertEquals(List.of(HASH), audio.factSha256());
    assertThrows(UnsupportedOperationException.class, () -> audio.factSha256().clear());
    assertEquals(List.of(audio), draft(audio).audioEvidence());
    assertEquals("AudioTraceEvidence[redacted]", audio.toString());
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () -> new AudioTraceEvidence(1, "physical-audio", 9, 9, 0.6, 0.7, List.of(HASH)))
            .code());
    var text = new TraceEvidence(1, "physical-text", 0, 1, 0.6, 0.7, List.of(HASH));
    assertEquals(
        "invalid_request",
        assertThrows(
                ApplicationException.class,
                () ->
                    new TraceDraft(
                        HASH,
                        HASH,
                        "answered",
                        null,
                        "answer",
                        "prompt",
                        "policy",
                        List.of(text),
                        List.of(),
                        List.of(audio)))
            .code());
    var refusal =
        new TraceDraft(
            HASH, null, "abstained", "no_evidence", "answer", "prompt", "policy", List.of());
    assertTrue(refusal.audioEvidence().isEmpty());
  }

  private static TraceDraft draft(AudioTraceEvidence audio) {
    return new TraceDraft(
        HASH,
        HASH,
        "answered",
        null,
        "answer-v1",
        "prompt-v1",
        "proof-v1",
        List.of(),
        List.of(),
        List.of(audio));
  }

  private static TraceAudioCitationEntity audioCitation(PublishedAudioEvidence audio, int ordinal) {
    var locator =
        new AudioTraceEvidence(
            ordinal,
            audio.physicalSegmentId(),
            audio.transcript().startCodePoint(),
            audio.transcript().endCodePoint(),
            0.6,
            0.7,
            List.of(HASH));
    return new TraceAudioCitationEntity(
        locator,
        audio.publication().publicationId(),
        audio.span().id(),
        audio.span().startMs(),
        audio.span().endMs(),
        audio.publication().sourceSha256(),
        audio.span().textSha256(),
        audio.transcript().contextSha256(),
        sha(audio.transcript().snippet()));
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

  private static List<String> publishImage(PublishedCorpusFixture fixture) throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes));
    var ingestion = ingestion(fixture);
    var uploaded = ingestion.uploadDocument(OWNER, "shapes.png", "image/png", bytes.toByteArray());
    var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        ingestion.completeVisualIngestion(
            claim, new ImageRecall("Synthetic black square.", VISUAL)));
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

  /** Remove only v9 before inherited fixtures exercise their original v1-v8 upgrade. */
  static void restoreVersionEight(Path directory) throws SQLException {
    VideoLibraryMigrationTest.restoreVersionNine(directory);
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute("DROP TABLE audio_trace_evidence");
      statement.execute("DROP TRIGGER query_traces_complete");
      statement.execute(
          """
          CREATE TRIGGER query_traces_complete BEFORE INSERT ON query_traces WHEN
            NEW.scope_count!=(SELECT COUNT(*) FROM query_trace_documents WHERE trace_id=NEW.id)
            OR NEW.citation_count!=(SELECT COUNT(*) FROM query_trace_evidence WHERE trace_id=NEW.id)
              +(SELECT COUNT(*) FROM image_trace_evidence WHERE trace_id=NEW.id)
            OR (NEW.scope_count>0 AND ((SELECT MIN(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=0
              OR (SELECT MAX(ordinal) FROM query_trace_documents WHERE trace_id=NEW.id)!=NEW.scope_count-1))
            OR (NEW.citation_count>0 AND (SELECT COUNT(DISTINCT citation_ordinal)!=NEW.citation_count
              OR MIN(citation_ordinal)!=1 OR MAX(citation_ordinal)!=NEW.citation_count
              FROM (SELECT citation_ordinal FROM query_trace_evidence WHERE trace_id=NEW.id
                UNION ALL SELECT citation_ordinal FROM image_trace_evidence WHERE trace_id=NEW.id)))
            OR EXISTS(SELECT 1 FROM query_trace_documents q JOIN index_publications p ON p.id=q.publication_id
              JOIN documents d ON d.id=p.document_id WHERE q.trace_id=NEW.id AND d.workspace_id!=NEW.workspace_id)
          BEGIN SELECT RAISE(ABORT,'incomplete query trace'); END
          """);
      statement.execute("UPDATE format_info SET version=8");
      statement.execute("PRAGMA user_version=8");
    }
  }

  private static long scalar(Path database, String sql) throws SQLException {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
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
