package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Untrusted original-wave identities and complete sidecar mappings, not coverage-only null cases.
 */
class AudioVectorBoundaryTest {
  private static final PublicationVersion BASE = AudioVectorDomainTest.BASE;
  private static final IndexTarget TARGET = AudioVectorDomainTest.TARGET;
  private static final String SOURCE = AudioVectorDomainTest.SOURCE,
      DECODER = AudioVectorDomainTest.DECODER;
  private static final List<AudioVectorSpan> SPANS =
      List.of(AudioVectorDomainTest.span(0, 0, 16000), AudioVectorDomainTest.span(2, 32000, 32003));
  private static final List<AudioVectorEntry> ENTRIES =
      SPANS.stream()
          .map(
              span ->
                  new AudioVectorEntry(
                      span.audioEvidenceId(),
                      span.basePhysicalSegmentId(),
                      "vector-" + span.ordinal(),
                      span.ordinal(),
                      span.waveform().startSample(),
                      span.waveform().endSample(),
                      span.waveform().pcmSha256(),
                      "a".repeat(64)))
          .toList();

  @Test
  void waveformRejectsUnboundHashReversedOrOutsideTheOriginalTenMinuteAxis() {
    for (String hash : List.of("A".repeat(64), "not-a-source-hash")) {
      assertThrows(
          ApplicationException.class, () -> new AudioWaveform(hash, DECODER, 0, 1, new byte[2]));
    }
    for (long[] range :
        List.of(new long[] {-1, 1}, new long[] {1, 1}, new long[] {9600000, 9600001})) {
      assertThrows(
          ApplicationException.class,
          () -> new AudioWaveform(SOURCE, DECODER, range[0], range[1], new byte[2]));
    }
    var finalSample = new AudioWaveform(SOURCE, DECODER, 9599999, 9600000, new byte[] {7, 8});
    assertEquals(46, finalSample.wav().length);
  }

  @Test
  void persistedMappingRejectsInvalidOrdinalSampleRangeAndEitherMalformedDigest() {
    var good = ENTRIES.getFirst();
    for (int ordinal : new int[] {-1, 600}) {
      assertThrows(
          ApplicationException.class,
          () -> entry(good, ordinal, 0, 16000, good.pcmSha256(), good.entrySha256()));
    }
    for (long[] range :
        List.of(
            new long[] {-1, 1},
            new long[] {1, 1},
            new long[] {9600000, 9600001},
            new long[] {0, 480001})) {
      assertThrows(
          ApplicationException.class,
          () -> entry(good, 0, range[0], range[1], good.pcmSha256(), good.entrySha256()));
    }
    assertThrows(
        ApplicationException.class,
        () -> entry(good, 0, 0, 16000, "bad-pcm-hash", good.entrySha256()));
    assertThrows(
        ApplicationException.class,
        () -> entry(good, 0, 0, 16000, good.pcmSha256(), "bad-entry-hash"));
  }

  @Test
  void spanTimeCannotInventAnOrdinalOffsetLongerWindowOrSamplesAfterTheSavedCeil() {
    var waveform = new AudioWaveform(SOURCE, DECODER, 0, 16000, new byte[32000]);
    for (long[] time :
        List.of(
            new long[] {-1, 1000},
            new long[] {1000, 1000},
            new long[] {0, 600001},
            new long[] {0, 30001},
            new long[] {1, 1000},
            new long[] {0, 999})) {
      assertThrows(
          ApplicationException.class,
          () ->
              new AudioVectorSpan(
                  SPANS.getFirst().audioEvidenceId(), "physical", 0, time[0], time[1], waveform));
    }
    for (int ordinal : new int[] {-1, 600}) {
      assertThrows(
          ApplicationException.class,
          () ->
              new AudioVectorSpan(
                  SPANS.getFirst().audioEvidenceId(), "physical", ordinal, 0, 1000, waveform));
    }
  }

  @Test
  void claimRequiresCanonicalFreshGenerationAndACompleteSourceBoundDisjointMapping() {
    for (String generation : List.of("bad-generation", "1-1-1-1-1")) {
      assertThrows(ApplicationException.class, () -> claim(BASE, generation, SPANS));
    }
    String generation = UUID.randomUUID().toString();
    var baseWithGeneration = base(generation, "rev");
    assertThrows(ApplicationException.class, () -> claim(baseWithGeneration, generation, SPANS));
    assertThrows(ApplicationException.class, () -> claim(BASE, generation, List.of()));
    var first = SPANS.getFirst();
    var tail = SPANS.getLast();
    var wrongSource =
        new AudioVectorSpan(
            first.audioEvidenceId(),
            first.basePhysicalSegmentId(),
            first.ordinal(),
            first.startMs(),
            first.endMs(),
            new AudioWaveform("e".repeat(64), DECODER, 0, 16000, new byte[32000]));
    var wrongRevision =
        new AudioVectorSpan(
            "audio-" + ModelValues.sha256("other-rev\0".getBytes(StandardCharsets.UTF_8)),
            first.basePhysicalSegmentId(),
            first.ordinal(),
            first.startMs(),
            first.endMs(),
            first.waveform());
    assertThrows(
        ApplicationException.class, () -> claim(BASE, generation, List.of(wrongSource, tail)));
    assertThrows(
        ApplicationException.class, () -> claim(BASE, generation, List.of(wrongRevision, tail)));
    var repeatedPhysical =
        new AudioVectorSpan(
            tail.audioEvidenceId(),
            first.basePhysicalSegmentId(),
            tail.ordinal(),
            tail.startMs(),
            tail.endMs(),
            tail.waveform());
    var overlapping =
        new AudioVectorSpan(
            tail.audioEvidenceId(),
            tail.basePhysicalSegmentId(),
            tail.ordinal(),
            999,
            1000,
            new AudioWaveform(SOURCE, DECODER, 15984, 16000, new byte[32]));
    var repeatedEvidence =
        new AudioVectorSpan(
            first.audioEvidenceId(),
            tail.basePhysicalSegmentId(),
            tail.ordinal(),
            tail.startMs(),
            tail.endMs(),
            tail.waveform());
    for (var bad : List.of(repeatedPhysical, overlapping, repeatedEvidence)) {
      assertThrows(ApplicationException.class, () -> claim(BASE, generation, List.of(first, bad)));
    }
  }

  @Test
  void publicationCannotReuseMappingIdsOverlapSamplesOrChangeEvidenceRevision() {
    var first = ENTRIES.getFirst();
    var tail = ENTRIES.getLast();
    var badTails =
        List.of(
            new AudioVectorEntry(
                first.audioEvidenceId(),
                tail.basePhysicalSegmentId(),
                tail.vectorPhysicalSegmentId(),
                2,
                32000,
                32003,
                tail.pcmSha256(),
                tail.entrySha256()),
            new AudioVectorEntry(
                tail.audioEvidenceId(),
                first.basePhysicalSegmentId(),
                tail.vectorPhysicalSegmentId(),
                2,
                32000,
                32003,
                tail.pcmSha256(),
                tail.entrySha256()),
            new AudioVectorEntry(
                tail.audioEvidenceId(),
                tail.basePhysicalSegmentId(),
                first.vectorPhysicalSegmentId(),
                2,
                32000,
                32003,
                tail.pcmSha256(),
                tail.entrySha256()),
            new AudioVectorEntry(
                tail.audioEvidenceId(),
                tail.basePhysicalSegmentId(),
                tail.basePhysicalSegmentId(),
                2,
                32000,
                32003,
                tail.pcmSha256(),
                tail.entrySha256()),
            new AudioVectorEntry(
                tail.audioEvidenceId(),
                tail.basePhysicalSegmentId(),
                tail.vectorPhysicalSegmentId(),
                2,
                15984,
                16000,
                tail.pcmSha256(),
                tail.entrySha256()),
            new AudioVectorEntry(
                "audio-" + "e".repeat(64),
                tail.basePhysicalSegmentId(),
                tail.vectorPhysicalSegmentId(),
                2,
                32000,
                32003,
                tail.pcmSha256(),
                tail.entrySha256()));
    for (var bad : badTails) {
      assertThrows(
          ApplicationException.class,
          () ->
              publication(
                  BASE,
                  UUID.randomUUID().toString(),
                  List.of(first, bad),
                  "a".repeat(64),
                  "2026-10-03T00:00:00Z"));
    }
  }

  @Test
  void sealedPublicationRejectsNoncanonicalGenerationMalformedManifestAndInvalidTimestamp() {
    for (String generation : List.of("bad-generation", "1-1-1-1-1")) {
      assertThrows(
          ApplicationException.class,
          () -> publication(BASE, generation, ENTRIES, "a".repeat(64), "2026-10-03T00:00:00Z"));
    }
    String generation = UUID.randomUUID().toString();
    assertThrows(
        ApplicationException.class,
        () ->
            publication(
                base(generation, "rev"),
                generation,
                ENTRIES,
                "a".repeat(64),
                "2026-10-03T00:00:00Z"));
    assertThrows(
        ApplicationException.class,
        () -> publication(BASE, generation, ENTRIES, "bad-manifest", "2026-10-03T00:00:00Z"));
    assertThrows(
        ApplicationException.class,
        () -> publication(BASE, generation, ENTRIES, "a".repeat(64), "not-an-instant"));
    var saved = publication(BASE, generation, ENTRIES, "a".repeat(64), "2026-10-03T00:00:00Z");
    assertThrows(
        ApplicationException.class,
        () -> new AudioVectorState(base("different-base", "rev"), TARGET, saved));
  }

  @Test
  void receiptAdmissionRejectsWrongDimensionMalformedDigestAndSubFloatNonzeroVectors() {
    for (var vector :
        List.of(
            List.of(1.0),
            List.of(Double.MIN_VALUE, Double.MIN_VALUE),
            Collections.nCopies(3073, 1.0))) {
      assertThrows(
          ApplicationException.class,
          () -> new AudioVectorReceipt.Entry("physical", vector, "a".repeat(64)));
    }
    assertThrows(
        ApplicationException.class,
        () -> new AudioVectorReceipt.Entry("physical", List.of(1.0, 0.0), "not-a-digest"));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorReceipt(
                List.of(), new VerifiedRevision("a".repeat(64), "b".repeat(64), 1)));
    var entries = new ArrayList<AudioVectorReceipt.Entry>();
    for (int i = 0; i < 601; i++) {
      entries.add(new AudioVectorReceipt.Entry("physical-" + i, List.of(1.0, 0.0), "a".repeat(64)));
    }
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorReceipt(
                entries, new VerifiedRevision("a".repeat(64), "b".repeat(64), 601)));
  }

  private static AudioVectorBuildClaim claim(
      PublicationVersion base, String generation, List<AudioVectorSpan> spans) {
    return new AudioVectorBuildClaim(
        new Actor("org", "owner"), base, TARGET, generation, DECODER, spans);
  }

  private static AudioVectorPublication publication(
      PublicationVersion base,
      String generation,
      List<AudioVectorEntry> entries,
      String manifest,
      String created) {
    return new AudioVectorPublication(
        "receipt", base, TARGET, generation, DECODER, entries, manifest, created);
  }

  private static AudioVectorEntry entry(
      AudioVectorEntry good, int ordinal, long start, long end, String pcm, String digest) {
    return new AudioVectorEntry(
        good.audioEvidenceId(),
        good.basePhysicalSegmentId(),
        good.vectorPhysicalSegmentId(),
        ordinal,
        start,
        end,
        pcm,
        digest);
  }

  private static PublicationVersion base(String generation, String revision) {
    return new PublicationVersion(
        BASE.documentId(),
        BASE.publicationId(),
        revision,
        generation,
        BASE.sourceSha256(),
        BASE.parserRevision(),
        BASE.target(),
        BASE.manifestSha256(),
        BASE.segmentCount());
  }
}
