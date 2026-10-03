package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.AudioDecoder;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SoundCompilationServiceTest {
  @Test
  void decodesOnceAndKeepsSilentMiddleAndActualShortTailWithoutAsr() {
    var calls = new AtomicInteger();
    byte[] pcm = new byte[64002];
    pcm[0] = 3;
    pcm[64000] = 7;
    var original = original();
    try (var service =
        new SoundCompilationService(decoder(original, pcm, calls), 1, Duration.ofSeconds(1))) {
      var spans = service.decode(original, () -> true);
      assertEquals(1, calls.get());
      assertEquals(3, spans.size());
      assertEquals(32001, spans.getLast().waveform().endSample());
      assertArrayEquals(new byte[32000], spans.get(1).waveform().pcm());
      assertArrayEquals(new byte[] {7, 0}, spans.getLast().waveform().pcm());
      assertArrayEquals(Arrays.copyOfRange(pcm, 0, 32000), spans.getFirst().waveform().pcm());
    }
  }

  @Test
  void queryAndLibraryHaveSameCompleteActualWaves() {
    var original = original();
    try (var service =
        new SoundCompilationService(
            decoder(original, new byte[4], new AtomicInteger()), 30, Duration.ofSeconds(1))) {
      AudioWaveform query =
          service
              .decodeQuery(
                  original.filename(), original.mediaType(), original.content(), () -> true)
              .getFirst();
      assertEquals(original.sourceSha256(), query.sourceSha256());
      assertEquals(2, query.endSample());
      assertArrayEquals(new byte[4], query.pcm());
    }
  }

  @Test
  void currentAuthorityCancellationPreventsDecode() {
    var calls = new AtomicInteger();
    var original = original();
    try (var service =
        new SoundCompilationService(
            decoder(original, new byte[4], calls), 1, Duration.ofSeconds(1))) {
      assertEquals(
          "parser_cancelled",
          assertThrows(TextParser.Failure.class, () -> service.decode(original, () -> false))
              .code());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void mismatchedSourceIsRejectedAndCloseIsNotCurrent() {
    var original = original();
    var decoder =
        new AudioDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public DecodedAudio decode(String filename, String mime, byte[] source) {
            return new DecodedAudio("a".repeat(64), revision(), new byte[2]);
          }

          public void close() {}
        };
    var service = new SoundCompilationService(decoder, 1, Duration.ofSeconds(1));
    assertEquals(
        "parser_invalid_output",
        assertThrows(TextParser.Failure.class, () -> service.decode(original, () -> true)).code());
    service.close();
    assertFalse(service.configurationCurrent());
    assertThrows(TextParser.Failure.class, () -> service.decode(original, () -> true));
  }

  @Test
  void deadlineInterruptsActualDecodeAndWaitsForItsTermination() {
    var stopped = new AtomicInteger();
    var decoder =
        new AudioDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public DecodedAudio decode(String filename, String mime, byte[] source) {
            try {
              Thread.sleep(10000);
            } catch (InterruptedException interrupted) {
              stopped.incrementAndGet();
              Thread.currentThread().interrupt();
            }
            throw new TextParser.Failure("parser_interrupted");
          }

          public void close() {}
        };
    try (var service = new SoundCompilationService(decoder, 1, Duration.ofMillis(50))) {
      assertEquals(
          "parser_timeout",
          assertThrows(TextParser.Failure.class, () -> service.decode(original(), () -> true))
              .code());
      assertEquals(1, stopped.get());
    }
  }

  private static DocumentOriginal original() {
    byte[] bytes = new byte[12];
    System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 0, 4);
    System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 8, 4);
    return new DocumentOriginal(
        "doc",
        "revision",
        "q.wav",
        "audio",
        "audio/wav",
        ModelValues.sha256(bytes),
        bytes.length,
        bytes);
  }

  private static AudioDecoder decoder(DocumentOriginal original, byte[] pcm, AtomicInteger calls) {
    return new AudioDecoder() {
      public String revision() {
        return "decoder-v1";
      }

      public DecodedAudio decode(String filename, String mime, byte[] source) {
        calls.incrementAndGet();
        return new DecodedAudio(original.sourceSha256(), revision(), pcm);
      }

      public void close() {}
    };
  }
}
