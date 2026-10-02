package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AudioTranscriptionServiceTest {
  private static final String SOURCE =
      ModelValues.sha256("synthetic parent video".getBytes(StandardCharsets.UTF_8));

  @Test
  void completePcmTranscriptKeepsSilentMiddleAndFinalSampleWithoutDecodingAgain() {
    var decoded = decoded(16 * 32_000 + 2);
    var models = new Models("星港项目的识别码为A-42。", "\n", "预算为47万元。");
    var service = new AudioTranscriptionService(models, 8, Duration.ofSeconds(5));

    var transcript = service.transcribe(decoded, () -> true);

    assertEquals(SOURCE, transcript.sourceSha256());
    assertEquals(decoded.decoderRevision(), transcript.decoderRevision());
    assertEquals(models.revision(), transcript.modelRevision());
    assertEquals(service.revision(), transcript.transcriptionRevision());
    assertEquals(256_001, transcript.sampleCount());
    assertEquals(16_001, transcript.durationMs());
    assertEquals(
        List.of(0, 1, 2), transcript.spans().stream().map(AudioTranscriptSpan::ordinal).toList());
    assertEquals(
        List.of(0L, 8000L, 16000L),
        transcript.spans().stream().map(AudioTranscriptSpan::startMs).toList());
    assertEquals(
        List.of(8000L, 16000L, 16001L),
        transcript.spans().stream().map(AudioTranscriptSpan::endMs).toList());
    assertEquals(
        List.of("星港项目的识别码为A-42。", "\n", "预算为47万元。"),
        transcript.spans().stream().map(AudioTranscriptSpan::text).toList());
    byte[] pcm = decoded.pcm();
    assertEquals(3, models.received.size());
    assertArrayEquals(AudioPcm.wav(pcm, 0, 256_000), models.received.get(0));
    assertArrayEquals(AudioPcm.wav(pcm, 256_000, 512_000), models.received.get(1));
    assertArrayEquals(AudioPcm.wav(pcm, 512_000, 512_002), models.received.get(2));
    assertEquals(0, models.closeCalls, "The caller retains ownership of AudioModels");
  }

  @Test
  void entirelyBlankTrackRemainsACompleteTranscriptWithoutInventingSpeech() {
    var models = new Models("", "\n");
    var transcript =
        new AudioTranscriptionService(models, 1, Duration.ofSeconds(5))
            .transcribe(decoded(64_000), () -> true);
    assertEquals(32_000, transcript.sampleCount());
    assertEquals(2000, transcript.durationMs());
    assertEquals(
        List.of("", "\n"), transcript.spans().stream().map(AudioTranscriptSpan::text).toList());
  }

  @Test
  void unavailableScopeBeforeTranscriptionMakesNoModelRequest() {
    var models = new Models("fact");
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    assertEquals(
        "parser_cancelled",
        assertThrows(
                TextParser.Failure.class, () -> service.transcribe(decoded(32_000), () -> false))
            .code());
    assertTrue(models.received.isEmpty());
    assertEquals(
        "parser_cancelled",
        assertThrows(
                TextParser.Failure.class,
                () ->
                    service.transcribe(
                        decoded(32_000),
                        () -> {
                          throw new IllegalStateException("synthetic authority unavailable");
                        }))
            .code());
    assertTrue(models.received.isEmpty());
  }

  @Test
  void revocationAfterAChunkNeverRequestsTheTailOrReturnsAPartialTranscript() {
    var current = new AtomicBoolean(true);
    var models = new Models("first fact", "second fact");
    models.after = () -> current.set(false);
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    assertEquals(
        "parser_cancelled",
        assertThrows(
                TextParser.Failure.class, () -> service.transcribe(decoded(64_000), current::get))
            .code());
    assertEquals(1, models.received.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"parser_timeout", "parser_interrupted", "audio_profile_changed"})
  void preservesSafeOuterCompilationFailureInsteadOfReclassifyingIt(String code) {
    var models = new Models("fact");
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    var outerFailure = new TextParser.Failure(code);
    assertSame(
        outerFailure,
        assertThrows(
            TextParser.Failure.class,
            () ->
                service.transcribe(
                    decoded(32_000),
                    () -> {
                      throw outerFailure;
                    })));
    assertTrue(models.received.isEmpty());
  }

  @Test
  void providerFailureOnTheTailIsNotRetriedOrReclassified() {
    var models = new Models("first fact");
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    assertSame(
        models.exhausted,
        assertThrows(
            TextModels.Failure.class, () -> service.transcribe(decoded(64_000), () -> true)));
    assertEquals(2, models.received.size());
    assertEquals(0, models.closeCalls);
  }

  @Test
  void changedModelProfileStopsBeforeTheNextChunk() {
    var models = new Models("first fact", "second fact");
    models.after = () -> models.modelRevision = "changed-model";
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    assertEquals(
        "audio_profile_changed",
        assertThrows(
                TextParser.Failure.class, () -> service.transcribe(decoded(64_000), () -> true))
            .code());
    assertEquals(1, models.received.size());
  }

  @Test
  void expiredTranscriptionBudgetCannotReturnTheFirstFactOrRequestTheTail() {
    var models = new Models("first fact", "second fact");
    models.after =
        () -> {
          try {
            Thread.sleep(200);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          }
        };
    var service = new AudioTranscriptionService(models, 1, Duration.ofMillis(150));
    assertEquals(
        "parser_timeout",
        assertThrows(
                TextParser.Failure.class, () -> service.transcribe(decoded(64_000), () -> true))
            .code());
    assertEquals(1, models.received.size());
  }

  @Test
  void profileBindsModelAndChunkingButNotParentSourceOrDecoder() {
    var models = new Models("fact", "fact");
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    String revision = service.revision();
    assertTrue(revision.matches("java-audio-transcription-v1:[a-f0-9]{64}"));
    var first = service.transcribe(decoded(32_000), () -> true);
    var second =
        service.transcribe(
            new DecodedAudio("b".repeat(64), "different-decoder", new byte[32_000]), () -> true);
    assertEquals(first.transcriptionRevision(), second.transcriptionRevision());
    assertEquals(revision, service.revision());
    assertNotEquals(
        revision, new AudioTranscriptionService(models, 2, Duration.ofSeconds(5)).revision());
    models.modelRevision = "changed-model";
    assertNotEquals(
        revision, new AudioTranscriptionService(models, 1, Duration.ofSeconds(5)).revision());
  }

  @Test
  void validatesRequiredPcmAndBoundedConfigurationBeforeModelWork() {
    var models = new Models("fact");
    assertThrows(
        RuntimeException.class,
        () -> new AudioTranscriptionService(null, 1, Duration.ofSeconds(5)));
    for (int chunks : new int[] {0, 31}) {
      assertThrows(
          RuntimeException.class,
          () -> new AudioTranscriptionService(models, chunks, Duration.ofSeconds(5)));
    }
    for (Duration budget :
        new Duration[] {null, Duration.ofMillis(9), Duration.ofMillis(600_001)}) {
      assertThrows(RuntimeException.class, () -> new AudioTranscriptionService(models, 1, budget));
    }
    var service = new AudioTranscriptionService(models, 1, Duration.ofSeconds(5));
    assertThrows(RuntimeException.class, () -> service.transcribe(null, () -> true));
    assertThrows(RuntimeException.class, () -> service.transcribe(decoded(32_000), null));
    assertTrue(models.received.isEmpty());
  }

  private static DecodedAudio decoded(int bytes) {
    var pcm = new byte[bytes];
    for (int index = 0; index < pcm.length; index++) {
      pcm[index] = (byte) (index * 31 + 17);
    }
    return new DecodedAudio(SOURCE, "video-decoder-v1", pcm);
  }

  private static final class Models implements AudioModels {
    private final List<String> responses;
    private final List<byte[]> received = new ArrayList<>();
    private final TextModels.Failure exhausted = new TextModels.Failure("model_invalid_response");
    private String modelRevision = "synthetic-asr-v1";
    private Runnable after = () -> {};
    private int closeCalls;

    private Models(String... responses) {
      this.responses = List.of(responses);
    }

    @Override
    public String revision() {
      return modelRevision;
    }

    @Override
    public Transcript transcribe(byte[] wav) {
      received.add(wav.clone());
      if (received.size() > responses.size()) {
        throw exhausted;
      }
      var transcript = new Transcript(responses.get(received.size() - 1));
      after.run();
      return transcript;
    }

    @Override
    public void close() {
      closeCalls++;
    }
  }
}
