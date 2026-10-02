package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AudioDomainTest {
  // SHA-256 of the synthetic encoded source bytes "abc", not a hash of decoded PCM.
  private static final String SOURCE_SHA =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Test
  void decodedAudioOwnsItsSamplesAndRoundsOnlyTheLastPartialMillisecondUp() {
    byte[] pcm = new byte[32_002];
    pcm[pcm.length - 2] = (byte) 0xfe;
    pcm[pcm.length - 1] = (byte) 0xff;
    var decoded = new DecodedAudio(SOURCE_SHA, "decoder-v1", pcm);

    assertEquals(1001, decoded.durationMs());
    assertEquals(SOURCE_SHA, decoded.sourceSha256());
    assertEquals("decoder-v1", decoded.decoderRevision());
    assertEquals("DecodedAudio[redacted]", decoded.toString());
    pcm[pcm.length - 2] = 0;
    assertEquals((byte) 0xfe, decoded.pcm()[pcm.length - 2]);
    byte[] returned = decoded.pcm();
    returned[returned.length - 1] = 0;
    assertEquals((byte) 0xff, decoded.pcm()[pcm.length - 1]);
    assertEquals(1, new DecodedAudio(SOURCE_SHA, "decoder-v1", new byte[2]).durationMs());
    assertEquals(1, new DecodedAudio(SOURCE_SHA, "decoder-v1", new byte[32]).durationMs());
    assertEquals(2, new DecodedAudio(SOURCE_SHA, "decoder-v1", new byte[34]).durationMs());
  }

  @Test
  void decodedAudioAcceptsExactlyTenMinutesAndRejectsAnAdditionalSample() {
    var maximum = new DecodedAudio(SOURCE_SHA, "decoder-v1", new byte[DecodedAudio.MAX_BYTES]);
    assertEquals(600_000, maximum.durationMs());
    assertEquals(19_200_000, maximum.pcm().length);
    assertInvalid(
        () -> new DecodedAudio(SOURCE_SHA, "decoder-v1", new byte[DecodedAudio.MAX_BYTES + 2]));
  }

  @Test
  void decodedAudioRejectsMissingOrMisalignedSamplesAndMalformedSourceIdentity() {
    assertInvalid(() -> new DecodedAudio(SOURCE_SHA, "decoder-v1", null));
    for (int length : List.of(0, 1, 3)) {
      assertInvalid(() -> new DecodedAudio(SOURCE_SHA, "decoder-v1", new byte[length]));
    }
    assertInvalid(() -> new DecodedAudio(null, "decoder-v1", new byte[2]));
    for (String hash : List.of("", "a".repeat(63), "g".repeat(64), SOURCE_SHA.toUpperCase())) {
      assertInvalid(() -> new DecodedAudio(hash, "decoder-v1", new byte[2]));
    }
    assertInvalid(() -> new DecodedAudio(SOURCE_SHA, null, new byte[2]));
    assertInvalid(() -> new DecodedAudio(SOURCE_SHA, "", new byte[2]));
  }

  @Test
  void transcriptSpanPreservesUnicodeWhitespaceAndItsActualTextHash() {
    var text = new AudioTranscriptSpan(0, 0, 1, "abc");
    assertEquals(SOURCE_SHA, text.textSha256());
    assertNotEquals(text.textSha256(), new AudioTranscriptSpan(0, 0, 1, "abc\n").textSha256());
    String unicode = "😀".repeat(4096);
    var maximum = new AudioTranscriptSpan(599, 570_000, 600_000, unicode);
    assertEquals(unicode, maximum.text());
    assertEquals(16_384, maximum.text().getBytes(StandardCharsets.UTF_8).length);
    assertEquals(599, maximum.ordinal());
    assertEquals(600_000, maximum.endMs());
    assertEquals("AudioTranscriptSpan[redacted]", maximum.toString());
    assertEquals("", new AudioTranscriptSpan(0, 0, 1, "").text());
    assertEquals(" \r\n\t", new AudioTranscriptSpan(0, 0, 1, " \r\n\t").text());
  }

  @ParameterizedTest
  @MethodSource("invalidSpanTimes")
  void transcriptSpanRejectsImpossibleOrdinalsAndTimeRanges(int ordinal, long start, long end) {
    assertInvalid(() -> new AudioTranscriptSpan(ordinal, start, end, "fact"));
  }

  static Stream<Arguments> invalidSpanTimes() {
    return Stream.of(
        Arguments.of(-1, 0L, 1L),
        Arguments.of(600, 0L, 1L),
        Arguments.of(0, -1L, 1L),
        Arguments.of(0, 1L, 1L),
        Arguments.of(0, 2L, 1L),
        Arguments.of(0, 570_000L, 600_001L),
        Arguments.of(0, 0L, 30_001L));
  }

  @Test
  void transcriptSpanRejectsMissingOversizeOrMalformedTextWithoutTruncation() {
    assertInvalid(() -> new AudioTranscriptSpan(0, 0, 1, null));
    for (String text :
        List.of(
            "x".repeat(4097),
            "😀".repeat(4097),
            "\u0000",
            "\u000b",
            "\u007f",
            "\u0085",
            "\ud800",
            "\udc00")) {
      assertInvalid(() -> new AudioTranscriptSpan(0, 0, 1, text));
    }
  }

  @Test
  void compilationKeepsSilentSpansAndMakesTheCompleteTimelineImmutable() {
    var spans = new ArrayList<AudioTranscriptSpan>();
    spans.add(new AudioTranscriptSpan(0, 0, 1000, ""));
    spans.add(new AudioTranscriptSpan(1, 1000, 2000, "事实 😀\r\n"));
    spans.add(new AudioTranscriptSpan(2, 2000, 2001, "\t"));
    var compilation = compilation(2001, spans);

    spans.clear();
    assertEquals(3, compilation.spans().size());
    assertEquals("", compilation.spans().getFirst().text());
    assertEquals("\t", compilation.spans().getLast().text());
    assertEquals("事实 😀\r\n", compilation.spans().get(1).text());
    assertThrows(UnsupportedOperationException.class, () -> compilation.spans().clear());
    assertEquals(SOURCE_SHA, compilation.sourceSha256());
    assertEquals("decoder-v1", compilation.decoderRevision());
    assertEquals("asr-v1", compilation.modelRevision());
    assertEquals("compiler-v1", compilation.compilerRevision());
    assertEquals(2001, compilation.durationMs());
    assertEquals("AudioCompilation[redacted]", compilation.toString());
  }

  @Test
  void compilationAcceptsSixHundredOrderedSpansEndingAtTheMaximumDuration() {
    var spans = new ArrayList<AudioTranscriptSpan>();
    for (int ordinal = 0; ordinal < 600; ordinal++) {
      spans.add(new AudioTranscriptSpan(ordinal, ordinal * 1000L, (ordinal + 1) * 1000L, "fact"));
    }
    var compilation = compilation(600_000, spans);
    assertEquals(600, compilation.spans().size());
    assertEquals(599, compilation.spans().getLast().ordinal());
    assertEquals(compilation.durationMs(), compilation.spans().getLast().endMs());
    spans.add(new AudioTranscriptSpan(0, 0, 1, "extra"));
    assertInvalid(() -> compilation(600_000, spans));
  }

  @Test
  void compilationEnforcesTheWholeTranscriptLimitWithoutDroppingItsTail() {
    var spans = new ArrayList<AudioTranscriptSpan>();
    String full = "字".repeat(4096);
    for (int ordinal = 0; ordinal < 244; ordinal++) {
      spans.add(new AudioTranscriptSpan(ordinal, ordinal * 1000L, (ordinal + 1) * 1000L, full));
    }
    spans.add(new AudioTranscriptSpan(244, 244_000, 245_000, "字".repeat(576)));
    var maximum = compilation(245_000, spans);
    assertEquals(
        1_000_000,
        maximum.spans().stream()
            .mapToInt(span -> span.text().codePointCount(0, span.text().length()))
            .sum());
    spans.set(244, new AudioTranscriptSpan(244, 244_000, 245_000, "字".repeat(577)));
    assertInvalid(() -> compilation(245_000, spans));
  }

  @Test
  void compilationRejectsGapsOverlapsWrongOrdinalsAndMissingTail() {
    var first = new AudioTranscriptSpan(0, 0, 1000, "first");
    assertInvalid(() -> compilation(2000, List.of(first)));
    assertInvalid(() -> compilation(999, List.of(first)));
    assertInvalid(() -> compilation(1001, List.of(new AudioTranscriptSpan(0, 1, 1001, "fact"))));
    for (AudioTranscriptSpan tail :
        List.of(
            new AudioTranscriptSpan(1, 1001, 2000, "gap"),
            new AudioTranscriptSpan(1, 999, 2000, "overlap"),
            new AudioTranscriptSpan(2, 1000, 2000, "ordinal gap"),
            new AudioTranscriptSpan(0, 1000, 2000, "duplicate ordinal"))) {
      assertInvalid(() -> compilation(2000, List.of(first, tail)));
    }
  }

  @Test
  void compilationRejectsInvalidDurationIdentityAndEntirelySilentTranscripts() {
    var span = new AudioTranscriptSpan(0, 0, 1, "fact");
    for (long duration : List.of(0L, -1L, 600_001L)) {
      assertInvalid(() -> compilation(duration, List.of(span)));
    }
    assertInvalid(() -> compilation(1, null));
    assertInvalid(() -> compilation(1, List.of()));
    assertInvalid(
        () ->
            compilation(
                2,
                List.of(
                    new AudioTranscriptSpan(0, 0, 1, ""),
                    new AudioTranscriptSpan(1, 1, 2, " \r\n\t"))));
    assertInvalid(() -> new AudioCompilation(null, "decoder", "asr", "compiler", 1, List.of(span)));
    assertInvalid(
        () -> new AudioCompilation("wrong-hash", "decoder", "asr", "compiler", 1, List.of(span)));
    assertInvalid(() -> new AudioCompilation(SOURCE_SHA, "", "asr", "compiler", 1, List.of(span)));
    assertInvalid(
        () -> new AudioCompilation(SOURCE_SHA, "decoder", "", "compiler", 1, List.of(span)));
    assertInvalid(() -> new AudioCompilation(SOURCE_SHA, "decoder", "asr", "", 1, List.of(span)));
  }

  @Test
  void wavFramingPreservesTheRequestedSamplesAtBothLegalChunkBounds() {
    byte[] pcm = new byte[960_002];
    pcm[0] = 99;
    pcm[2] = 12;
    pcm[3] = 34;
    pcm[960_000] = (byte) 0xfe;
    pcm[960_001] = (byte) 0xff;
    byte[] original = pcm.clone();
    byte[] maximum = AudioPcm.wav(pcm, 2, pcm.length);
    assertEquals(960_044, maximum.length);
    assertArrayEquals(
        Arrays.copyOfRange(pcm, 2, pcm.length), Arrays.copyOfRange(maximum, 44, maximum.length));
    var header = ByteBuffer.wrap(maximum).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals("RIFF", new String(maximum, 0, 4, StandardCharsets.US_ASCII));
    assertEquals(maximum.length - 8, header.getInt(4));
    assertEquals("WAVEfmt ", new String(maximum, 8, 8, StandardCharsets.US_ASCII));
    assertEquals(16, header.getInt(16));
    assertEquals(1, header.getShort(20));
    assertEquals(1, header.getShort(22));
    assertEquals(16_000, header.getInt(24));
    assertEquals(32_000, header.getInt(28));
    assertEquals(2, header.getShort(32));
    assertEquals(16, header.getShort(34));
    assertEquals("data", new String(maximum, 36, 4, StandardCharsets.US_ASCII));
    assertEquals(960_000, header.getInt(40));
    byte[] lastSample = AudioPcm.wav(pcm, pcm.length - 2, pcm.length);
    assertEquals(46, lastSample.length);
    assertArrayEquals(
        new byte[] {(byte) 0xfe, (byte) 0xff}, Arrays.copyOfRange(lastSample, 44, 46));
    assertArrayEquals(original, pcm);
    maximum[44] = 0;
    assertEquals(12, pcm[2]);
  }

  @Test
  void wavFramingRejectsMissingMisalignedAndOversizeSampleRanges() {
    assertInvalid(() -> AudioPcm.wav(null, 0, 2));
    byte[] pcm = new byte[960_002];
    for (int[] range :
        List.of(
            new int[] {-2, 2},
            new int[] {0, pcm.length + 2},
            new int[] {0, 0},
            new int[] {2, 0},
            new int[] {1, 4},
            new int[] {0, 3},
            new int[] {0, pcm.length})) {
      assertInvalid(() -> AudioPcm.wav(pcm, range[0], range[1]));
    }
  }

  private static AudioCompilation compilation(long duration, List<AudioTranscriptSpan> spans) {
    return new AudioCompilation(SOURCE_SHA, "decoder-v1", "asr-v1", "compiler-v1", duration, spans);
  }

  private static void assertInvalid(Executable operation) {
    var failure = assertThrows(ApplicationException.class, operation);
    assertEquals(FailureKind.INVALID_INPUT, failure.kind());
    assertEquals("invalid_request", failure.code());
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains(SOURCE_SHA));
  }
}
