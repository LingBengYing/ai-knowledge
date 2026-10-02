package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioIngestionServiceTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String PROFILE = "java-audio-compiler-v1:" + "a".repeat(64);
  @TempDir Path directory;

  @Test
  void audioUploadUsesOriginalAudioIdentityAndExistingDurableTask() {
    byte[] source = original();
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, PROFILE);
      assertEquals("audio/wav", service.prepareUpload("meeting.wav"));
      var task = service.uploadDocument(OWNER, "meeting.wav", "application/octet-stream", source);
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      assertEquals(task.taskId(), claim.jobId());
      assertEquals(PROFILE, claim.parserRevision());
      assertEquals("audio/wav", claim.mimeType());
      assertArrayEquals(source, claim.content());
      var document =
          store.transaction(
              () ->
                  new ManagementRepository(store)
                      .findAuthorizedDocument(OWNER, task.documentId(), false)
                      .orElseThrow());
      assertEquals("audio", document.documentType());
      assertEquals("meeting.wav", document.filename());
      assertEquals(ModelValues.sha256(source), document.sourceSha256());
    }
  }

  @Test
  void completeAudioClaimPreservesSilentSpanAndReopensWithoutFakeTextPages() {
    String revision;
    AudioCompilation expected = compilation(PROFILE, ModelValues.sha256(original()));
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, PROFILE);
      var claim = seedClaim(store, service, PROFILE);
      revision = claim.revisionId();
      assertTrue(service.completeAudioIngestion(claim, expected));
      assertEquals("parsed", service.ingestionStatus(OWNER, claim.jobId()).state());
      var parsed = service.parsedEvidence(OWNER, claim.documentId());
      assertTrue(parsed.pages().isEmpty());
      assertTrue(parsed.segments().isEmpty());
      assertFalse(service.completeAudioIngestion(claim, expected), "Completed claim is fenced");
    }
    try (var reopened = new SqliteAuthorityStore(directory)) {
      var stored =
          reopened.transaction(
              () -> new IngestionRepository(reopened).findAudioCompilation(revision).orElseThrow());
      assertEquals(expected, stored);
      assertEquals("", stored.spans().get(1).text());
      assertEquals(2001, stored.spans().getLast().endMs());
    }
  }

  @Test
  void wrongCompilerOrSourceCannotCompleteTheCurrentClaim() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, PROFILE);
      var claim = seedClaim(store, service, PROFILE);
      for (var bad :
          List.of(
              compilation(
                  "java-audio-compiler-v1:" + "b".repeat(64), ModelValues.sha256(original())),
              compilation(PROFILE, ModelValues.sha256(new byte[] {4})))) {
        var failure =
            assertThrows(
                ApplicationException.class, () -> service.completeAudioIngestion(claim, bad));
        assertEquals("parser_output_invalid", failure.code());
        assertEquals("processing", service.ingestionStatus(OWNER, claim.jobId()).state());
        assertTrue(
            store.transaction(
                () ->
                    new IngestionRepository(store)
                        .findAudioCompilation(claim.revisionId())
                        .isEmpty()));
      }
    }
  }

  @Test
  void cancellationBeforeCompletionCannotLeaveAudioEvidence() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, PROFILE);
      var claim = seedClaim(store, service, PROFILE);
      service.cancelIngestion(OWNER, claim.jobId());
      assertFalse(
          service.completeAudioIngestion(
              claim, compilation(PROFILE, ModelValues.sha256(original()))));
      assertTrue(
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findAudioCompilation(claim.revisionId())
                      .isEmpty()));
      assertEquals("cancelled", service.ingestionStatus(OWNER, claim.jobId()).state());
    }
  }

  @Test
  void taskProcessorUsesCompleteCompilerAndPersistsAllChunks() {
    var calls = new AtomicInteger();
    try (var store = new SqliteAuthorityStore(directory)) {
      AudioDecoder decoder =
          new AudioDecoder() {
            public String revision() {
              return "decoder-v1";
            }

            public DecodedAudio decode(String filename, String mime, byte[] source) {
              assertEquals("meeting.wav", filename);
              return new DecodedAudio(ModelValues.sha256(source), revision(), new byte[64_002]);
            }

            public void close() {}
          };
      AudioModels models =
          new AudioModels() {
            public String revision() {
              return "asr-v1";
            }

            public Transcript transcribe(byte[] wav) {
              int ordinal = calls.getAndIncrement();
              return new Transcript(List.of("项目代号为云杉。", "", "预算为42万元。").get(ordinal));
            }

            public void close() {}
          };
      var compiler = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(5));
      var service = service(store, compiler.revision());
      var claim = seedClaim(store, service, compiler.revision());
      var processor =
          new IngestionTaskProcessor(
              service, OWNER.workspaceId(), Duration.ofSeconds(5), null, null, compiler);
      processor.process(claim);
      assertEquals("parsed", service.ingestionStatus(OWNER, claim.jobId()).state());
      assertEquals(3, calls.get());
      var stored =
          store.transaction(
              () ->
                  new IngestionRepository(store)
                      .findAudioCompilation(claim.revisionId())
                      .orElseThrow());
      assertEquals(3, stored.spans().size());
      assertEquals(compiler.revision(), stored.compilerRevision());
    }
  }

  private static IngestionService service(SqliteAuthorityStore store, String profile) {
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        null,
        null,
        profile);
  }

  private static IngestionClaim seedClaim(
      SqliteAuthorityStore store, IngestionService service, String profile) {
    String document = UUID.randomUUID().toString(),
        revision = UUID.randomUUID().toString(),
        job = UUID.randomUUID().toString();
    byte[] original = original();
    String now = Instant.now().toString();
    store.transaction(
        () -> {
          var management = new ManagementRepository(store);
          var ingestion = new IngestionRepository(store);
          management.insertDocument(
              OWNER,
              new SyntheticDocument(
                  document,
                  "meeting.wav",
                  "audio",
                  "audio/wav",
                  revision,
                  ModelValues.sha256(original),
                  original.length),
              now);
          management.insertGrant(document, OWNER.principalId(), "owner");
          ingestion.insertOriginal(
              document, revision, profile, ModelValues.sha256(original), original, now);
          ingestion.insertJob(job, document, revision, OWNER.principalId(), now);
          return null;
        });
    return service.claimIngestion(OWNER.workspaceId()).orElseThrow();
  }

  private static byte[] original() {
    return AudioPcm.wav(new byte[64_002], 0, 64_002);
  }

  private static AudioCompilation compilation(String profile, String sourceSha) {
    return new AudioCompilation(
        sourceSha,
        "decoder-v1",
        "asr-v1",
        profile,
        2001,
        List.of(
            new AudioTranscriptSpan(0, 0, 1000, "项目代号为云杉。"),
            new AudioTranscriptSpan(1, 1000, 2000, ""),
            new AudioTranscriptSpan(2, 2000, 2001, "预算为42万元。")));
  }
}
