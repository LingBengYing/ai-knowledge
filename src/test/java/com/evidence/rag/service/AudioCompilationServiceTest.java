package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AudioCompilationServiceTest {
  private static final byte[] SOURCE =
      "synthetic encoded source".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @Test
  void completeTranscriptUsesDecodedSamplesAndExactChunkBytesNotProviderTime() {
    var decoder = new Decoder(16 * 32_000 + 2);
    var models = new Models("蓝港项目代号为云杉。", "\n", "预算为42万元。");
    var service = new AudioCompilationService(decoder, models, 8, Duration.ofSeconds(5));
    var result = service.compile("meeting.wav", "audio/wav", SOURCE, () -> true);
    assertEquals(16_001, result.durationMs());
    assertEquals(
        List.of(0L, 8000L, 16000L),
        result.spans().stream().map(AudioTranscriptSpan::startMs).toList());
    assertEquals(
        List.of(8000L, 16000L, 16001L),
        result.spans().stream().map(AudioTranscriptSpan::endMs).toList());
    assertEquals("预算为42万元。", result.spans().getLast().text());
    assertEquals("\n", result.spans().get(1).text(), "Silent spans preserve the complete timeline");
    assertEquals(ModelValues.sha256(SOURCE), result.sourceSha256());
    assertEquals(service.revision(), result.compilerRevision());
    assertArrayEquals(AudioPcm.wav(decoder.pcm, 0, 256000), models.received.get(0));
    assertArrayEquals(AudioPcm.wav(decoder.pcm, 256000, 512000), models.received.get(1));
    assertArrayEquals(AudioPcm.wav(decoder.pcm, 512000, 512002), models.received.get(2));
    assertFalse(result.toString().contains("42"));
    assertFalse(result.spans().getLast().toString().contains("预算"));
  }

  @Test
  void revokedBeforeDecodeDoesNoDecoderOrModelWork() {
    var decoder = new Decoder(32_000);
    var models = new Models("fact");
    var service = new AudioCompilationService(decoder, models, 15, Duration.ofSeconds(5));
    assertThrows(
        TextParser.Failure.class,
        () -> service.compile("meeting.wav", "audio/wav", SOURCE, () -> false));
    assertEquals(0, decoder.calls);
    assertTrue(models.received.isEmpty());
  }

  @Test
  void revocationAfterFirstTranscriptionDoesNotCallTheNextChunkOrReturnPartialOutput() {
    var decoder = new Decoder(32_000 * 3);
    var current = new AtomicBoolean(true);
    var models = new Models("蓝港项目代号为云杉。", "预算为42万元。", "第三事实。");
    models.after = () -> current.set(false);
    var service = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(5));
    assertThrows(
        TextParser.Failure.class,
        () -> service.compile("meeting.wav", "audio/wav", SOURCE, current::get));
    assertEquals(1, models.received.size());
  }

  @Test
  void failedTailCannotReturnEarlierFactsAndIsNotRetried() {
    var models = new Models("蓝港项目代号为云杉。");
    var service =
        new AudioCompilationService(new Decoder(64_000), models, 1, Duration.ofSeconds(5));
    assertThrows(
        TextModels.Failure.class,
        () -> service.compile("meeting.wav", "audio/wav", SOURCE, () -> true));
    assertEquals(2, models.received.size());
  }

  @Test
  void allSilenceDoesNotBecomeEvidence() {
    var models = new Models("", "\n");
    var service =
        new AudioCompilationService(new Decoder(64_000), models, 1, Duration.ofSeconds(5));
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () -> service.compile("meeting.wav", "audio/wav", SOURCE, () -> true));
    assertEquals("audio_no_speech", failure.code());
  }

  @Test
  void modelRevisionChangePreventsFurtherRequests() {
    var models = new Models("事实一。", "事实二。");
    models.after = () -> models.revision = "asr-changed";
    var service =
        new AudioCompilationService(new Decoder(64_000), models, 1, Duration.ofSeconds(5));
    assertThrows(
        TextParser.Failure.class,
        () -> service.compile("meeting.wav", "audio/wav", SOURCE, () -> true));
    assertEquals(1, models.received.size());
  }

  @Test
  void decodedSourceMismatchCannotReachAsr() {
    var decoder = new Decoder(32_000);
    decoder.sourceSha = ModelValues.sha256(new byte[] {9});
    var models = new Models("fact");
    var service = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(5));
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () -> service.compile("meeting.wav", "audio/wav", SOURCE, () -> true));
    assertEquals("parser_invalid_output", failure.code());
    assertEquals(1, decoder.calls);
    assertTrue(models.received.isEmpty());
  }

  @Test
  void expiredCompilationBudgetAfterDecodeDoesNotStartAsr() {
    var decoder = new Decoder(32_000);
    decoder.after =
        () -> {
          try {
            Thread.sleep(200);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
          }
        };
    var models = new Models("fact");
    var service = new AudioCompilationService(decoder, models, 1, Duration.ofMillis(150));
    var failure =
        assertThrows(
            TextParser.Failure.class,
            () -> service.compile("meeting.wav", "audio/wav", SOURCE, () -> true));
    assertEquals("parser_timeout", failure.code());
    assertEquals(1, decoder.calls);
    assertTrue(models.received.isEmpty());
  }

  @Test
  void compilationProfileChangesWithChunkingAndDecoderButNotSourceText() {
    var decoder = new Decoder(32_000);
    var models = new Models("fact");
    var one = new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(5)).revision();
    assertNotEquals(
        one, new AudioCompilationService(decoder, models, 2, Duration.ofSeconds(5)).revision());
    decoder.revision = "decoder-changed";
    assertNotEquals(
        one, new AudioCompilationService(decoder, models, 1, Duration.ofSeconds(5)).revision());
  }

  @Test
  void domainKeepsSampleOwnershipAndRejectsGapsOrInventedDurations() {
    byte[] pcm = {1, 2};
    var decoded = new DecodedAudio(ModelValues.sha256(SOURCE), "decoder-v1", pcm);
    pcm[0] = 0;
    assertEquals(1, decoded.pcm()[0]);
    decoded.pcm()[0] = 0;
    assertEquals(1, decoded.pcm()[0]);
    assertEquals(1, decoded.durationMs());
    assertThrows(
        RuntimeException.class,
        () ->
            new AudioCompilation(
                ModelValues.sha256(SOURCE),
                "decoder-v1",
                "asr-v1",
                "compiler-v1",
                2000,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1000, "fact"),
                    new AudioTranscriptSpan(1, 1001, 2000, "fact"))));
  }

  private static final class Decoder implements AudioDecoder {
    private final byte[] pcm;
    private String revision = "decoder-v1";
    private int calls;
    private String sourceSha;
    private Runnable after = () -> {};

    Decoder(int size) {
      pcm = new byte[size];
      Arrays.fill(pcm, (byte) 7);
    }

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public DecodedAudio decode(String filename, String mime, byte[] source) {
      calls++;
      after.run();
      return new DecodedAudio(
          sourceSha == null ? ModelValues.sha256(source) : sourceSha, revision, pcm);
    }

    @Override
    public void close() {}
  }

  private static final class Models implements AudioModels {
    private final List<String> text;
    private final List<byte[]> received = new ArrayList<>();
    private Runnable after = () -> {};
    private String revision = "asr-v1";

    Models(String... text) {
      this.text = List.of(text);
    }

    @Override
    public String revision() {
      return revision;
    }

    @Override
    public Transcript transcribe(byte[] wav) {
      int index = received.size();
      received.add(wav.clone());
      if (index >= text.size()) {
        throw new TextModels.Failure("model_http_failed");
      }
      after.run();
      return new Transcript(text.get(index));
    }

    @Override
    public void close() {}
  }
}
