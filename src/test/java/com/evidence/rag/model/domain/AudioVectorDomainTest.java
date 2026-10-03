package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.tool.parser.AudioPcm;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AudioVectorDomainTest {
  static final String SOURCE = "a".repeat(64);
  static final String DECODER = "decoder-v1";
  static final IndexTarget TARGET =
      new IndexTarget("audio-model-v1", "b".repeat(64), "audio-model-v1", 2);
  static final PublicationVersion BASE =
      new PublicationVersion(
          "doc",
          "pub",
          "rev",
          "base",
          SOURCE,
          "java-audio-compiler-v1:" + "c".repeat(64),
          TARGET,
          "d".repeat(64),
          2);

  static String evidenceId(int ordinal) {
    return "audio-"
        + ModelValues.sha256(
            (BASE.sourceRevisionId() + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  static AudioVectorSpan span(int ordinal, long from, long to) {
    return new AudioVectorSpan(
        evidenceId(ordinal),
        "base-physical-" + ordinal,
        ordinal,
        from / 16,
        (to + 15) / 16,
        new AudioWaveform(SOURCE, DECODER, from, to, new byte[(int) (to - from) * 2]));
  }

  @Test
  void canonicalWaveformPreservesCompletePcmAndExactSampleTail() {
    byte[] pcm = new byte[] {1, 2, 3, 4, 5, 6};
    var waveform = new AudioWaveform(SOURCE, DECODER, 16000, 16003, pcm);
    assertArrayEquals(AudioPcm.wav(pcm, 0, pcm.length), waveform.wav());
    assertEquals(ModelValues.sha256(pcm), waveform.pcmSha256());
    pcm[0] = 9;
    byte[] returned = waveform.pcm();
    returned[0] = 8;
    assertEquals(1, waveform.pcm()[0]);
    assertTrue(waveform.toString().contains("redacted"));
    assertThrows(
        ApplicationException.class,
        () -> new AudioWaveform(SOURCE, DECODER, 0, 480001, new byte[960002]));
    assertThrows(
        ApplicationException.class, () -> new AudioWaveform(SOURCE, DECODER, 0, 3, new byte[4]));
  }

  @Test
  void claimsRetainSilentOrdinalGapsAndRejectCrossSourceOrOverlappingMappings() {
    var spans = List.of(span(0, 0, 16000), span(2, 32000, 32003));
    var claim =
        new AudioVectorBuildClaim(
            new Actor("org", "owner"), BASE, TARGET, UUID.randomUUID().toString(), DECODER, spans);
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBuildClaim(
                claim.actor(),
                BASE,
                TARGET,
                claim.vectorGenerationId(),
                DECODER,
                List.of(spans.getFirst())));
    assertEquals(List.of(0, 2), claim.spans().stream().map(AudioVectorSpan::ordinal).toList());
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBuildClaim(
                claim.actor(),
                BASE,
                TARGET,
                claim.vectorGenerationId(),
                DECODER,
                spans.reversed()));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBuildClaim(
                claim.actor(), BASE, TARGET, claim.vectorGenerationId(), "decoder-v2", spans));
    var badSource =
        new AudioVectorSpan(
            evidenceId(0),
            "physical",
            0,
            0,
            1000,
            new AudioWaveform("e".repeat(64), DECODER, 0, 16000, new byte[32000]));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorBuildClaim(
                claim.actor(),
                BASE,
                TARGET,
                claim.vectorGenerationId(),
                DECODER,
                List.of(badSource)));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorSpan(
                evidenceId(0),
                "physical",
                0,
                0,
                1000,
                new AudioWaveform(SOURCE, DECODER, 0, 15983, new byte[31966])));
  }

  @Test
  void completeReceiptCannotAcceptAPrefixDuplicateOrUnusableCoordinates() {
    var entry = new AudioVectorReceipt.Entry("physical", List.of(0.25, 0.75), "a".repeat(64));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorReceipt(
                List.of(entry), new VerifiedRevision("b".repeat(64), "c".repeat(64), 2)));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorReceipt(
                List.of(entry, entry), new VerifiedRevision("b".repeat(64), "c".repeat(64), 2)));
    for (var vector :
        List.of(List.of(0.0, 0.0), List.of(Double.NaN, 1.0), List.of(Double.MAX_VALUE, 1.0))) {
      assertThrows(
          ApplicationException.class,
          () -> new AudioVectorReceipt.Entry("physical", vector, "a".repeat(64)));
    }
    var entries = new ArrayList<>(List.of(entry));
    var receipt =
        new AudioVectorReceipt(entries, new VerifiedRevision("b".repeat(64), "c".repeat(64), 1));
    entries.clear();
    assertEquals(1, receipt.entries().size());
    assertTrue(receipt.toString().contains("redacted"));
  }

  @Test
  void publicationRetainsAllEntriesAndCannotAttachToAnotherBaseOrTarget() {
    var entries =
        List.of(
            new AudioVectorEntry(
                evidenceId(0), "base0", "vector0", 0, 0, 16000, "a".repeat(64), "b".repeat(64)),
            new AudioVectorEntry(
                evidenceId(2),
                "base2",
                "vector2",
                2,
                32000,
                32003,
                "c".repeat(64),
                "d".repeat(64)));
    var publication =
        new AudioVectorPublication(
            "receipt",
            BASE,
            TARGET,
            UUID.randomUUID().toString(),
            DECODER,
            entries,
            "a".repeat(64),
            "2026-10-03T00:00:00Z");
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorPublication(
                "prefix",
                BASE,
                TARGET,
                publication.vectorGenerationId(),
                DECODER,
                List.of(entries.getFirst()),
                "a".repeat(64),
                publication.createdAt()));
    assertEquals(publication, new AudioVectorState(BASE, TARGET, publication).publication());
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorPublication(
                "receipt",
                BASE,
                TARGET,
                publication.vectorGenerationId(),
                DECODER,
                entries.reversed(),
                "a".repeat(64),
                publication.createdAt()));
    assertThrows(
        ApplicationException.class,
        () ->
            new AudioVectorState(
                BASE, new IndexTarget("audio-v2", "e".repeat(64), "audio-v2", 2), publication));
    assertTrue(publication.toString().contains("redacted"));
  }
}
