package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioTranscriptionTest {
  private static final String SOURCE = "a".repeat(64);

  @Test
  void silentTranscriptRetainsActualSampleCountAndOwnsItsCompleteTimeline() {
    var spans =
        new ArrayList<>(
            List.of(
                new AudioTranscriptSpan(0, 0, 1000, ""),
                new AudioTranscriptSpan(1, 1000, 1001, "\n")));
    var transcript = transcript(16_001, spans);
    spans.clear();
    assertEquals(16_001, transcript.sampleCount());
    assertEquals(1001, transcript.durationMs());
    assertEquals(2, transcript.spans().size());
    assertEquals("\n", transcript.spans().getLast().text());
    assertThrows(UnsupportedOperationException.class, () -> transcript.spans().clear());
    assertEquals("AudioTranscription[redacted]", transcript.toString());
  }

  @Test
  void oneSampleAndFullTenMinutesPreserveCompleteBlankResults() {
    assertEquals(1, transcript(1, List.of(new AudioTranscriptSpan(0, 0, 1, ""))).durationMs());
    var spans = new ArrayList<AudioTranscriptSpan>();
    for (int index = 0; index < 600; index++) {
      spans.add(new AudioTranscriptSpan(index, index * 1000L, (index + 1) * 1000L, ""));
    }
    var transcript = transcript(9_600_000, spans);
    assertEquals(600_000, transcript.durationMs());
    assertEquals(600, transcript.spans().size());
  }

  @Test
  void rejectsInvalidSourceProfileAndSampleBounds() {
    var spans = List.of(new AudioTranscriptSpan(0, 0, 1, ""));
    for (String invalid : new String[] {null, "", "A".repeat(64), "a".repeat(63)}) {
      assertThrows(
          RuntimeException.class,
          () -> new AudioTranscription(invalid, "decoder", "model", "transcriber", 1, spans));
    }
    for (String invalid : new String[] {null, "", "invalid\nrevision", "a".repeat(201)}) {
      assertThrows(
          RuntimeException.class,
          () -> new AudioTranscription(SOURCE, invalid, "model", "transcriber", 1, spans));
      assertThrows(
          RuntimeException.class,
          () -> new AudioTranscription(SOURCE, "decoder", invalid, "transcriber", 1, spans));
      assertThrows(
          RuntimeException.class,
          () -> new AudioTranscription(SOURCE, "decoder", "model", invalid, 1, spans));
    }
    for (long samples : new long[] {0, -1, 9_600_001, Long.MAX_VALUE}) {
      assertThrows(RuntimeException.class, () -> transcript(samples, spans));
    }
  }

  @Test
  void rejectsMissingReorderedGappedAndInventedTailSpans() {
    assertThrows(RuntimeException.class, () -> transcript(1, null));
    assertThrows(RuntimeException.class, () -> transcript(1, List.of()));
    var nullSpan = new ArrayList<AudioTranscriptSpan>();
    nullSpan.add(null);
    assertThrows(RuntimeException.class, () -> transcript(1, nullSpan));
    for (var spans :
        List.of(
            List.of(new AudioTranscriptSpan(1, 0, 1, "")),
            List.of(new AudioTranscriptSpan(0, 1, 2, "")),
            List.of(
                new AudioTranscriptSpan(0, 0, 1000, ""),
                new AudioTranscriptSpan(1, 1001, 2000, "")),
            List.of(
                new AudioTranscriptSpan(0, 0, 1000, ""),
                new AudioTranscriptSpan(0, 1000, 2000, "")),
            List.of(new AudioTranscriptSpan(0, 0, 999, "")))) {
      assertThrows(RuntimeException.class, () -> transcript(16_000, spans));
    }
    assertThrows(
        RuntimeException.class,
        () ->
            transcript(
                16_000,
                java.util.Collections.nCopies(601, new AudioTranscriptSpan(0, 0, 1000, ""))));
  }

  @Test
  void completeTranscriptCannotExceedOneMillionCodePoints() {
    var spans = new ArrayList<AudioTranscriptSpan>();
    for (int index = 0; index < 245; index++) {
      spans.add(
          new AudioTranscriptSpan(index, index * 1000L, (index + 1) * 1000L, "x".repeat(4096)));
    }
    assertThrows(RuntimeException.class, () -> transcript(245 * 16_000L, spans));
  }

  private static AudioTranscription transcript(long samples, List<AudioTranscriptSpan> spans) {
    return new AudioTranscription(SOURCE, "decoder", "model", "transcriber", samples, spans);
  }
}
