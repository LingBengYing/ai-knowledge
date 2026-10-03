package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SoundDomainTest {
  private static final String HASH = "a".repeat(64);
  private static final IndexTarget TARGET = new IndexTarget("embedding", HASH, "embedding-v1", 2);

  @Test
  void profileBindsDecoderChunkAndBothModels() {
    String profile = SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", 1);
    assertNotEquals(profile, SoundProfile.fingerprint(TARGET, "sound-v2", "decoder-v1", 1));
    assertNotEquals(profile, SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v2", 1));
    assertNotEquals(profile, SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", 2));
  }

  @Test
  void completeClaimKeepsSilentWindowAndExactTail() {
    var original = original();
    var spans = List.of(input(original, 0, 0, 16000), input(original, 1, 16000, 16001));
    var claim = claim(original, spans);
    assertEquals(16001, claim.spans().getLast().waveform().endSample());
    assertTrue(claim.toString().contains("redacted"));
  }

  @Test
  void rejectsMissingWindowWrongSourceAndIncorrectProfile() {
    var original = original();
    assertThrows(RuntimeException.class, () -> claim(original, List.of(input(original, 1, 0, 1))));
    assertThrows(RuntimeException.class, () -> claim(original, List.of(input(original, 0, 1, 2))));
    var wrong =
        new SoundInputSpan(
            SoundProfile.spanId(original.revisionId(), 0),
            0,
            new AudioWaveform(HASH, "decoder-v1", 0, 1, new byte[2]));
    assertThrows(RuntimeException.class, () -> claim(original, List.of(wrong)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundBuildClaim(
                new Actor("ws", "owner"),
                original,
                TARGET,
                UUID.randomUUID().toString(),
                "sound-v1",
                "decoder-v1",
                1,
                List.of(input(original, 0, 0, 1)),
                HASH));
  }

  @Test
  void publicationKeepsEmptyRecallAndEnforcesManifestAndPhysicalIdentity() {
    var original = original();
    String generation = UUID.randomUUID().toString();
    String id = SoundProfile.spanId(original.revisionId(), 0);
    var span =
        new SoundSpan(id, 0, 0, 1, HASH, "", SoundProfile.physicalSegmentId(generation, id), HASH);
    var publication = publication(original, generation, List.of(span));
    assertEquals("", publication.spans().getFirst().recallText());
    var wrong = new SoundSpan(id, 0, 0, 1, HASH, "", "wrong-physical", HASH);
    assertThrows(RuntimeException.class, () -> publication(original, generation, List.of(wrong)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundPublication(
                publication.id(),
                "ws",
                original.documentId(),
                original.revisionId(),
                original.sourceSha256(),
                "q.wav",
                "audio/wav",
                2,
                generation,
                TARGET,
                "sound-v1",
                "decoder-v1",
                1,
                1,
                List.of(span),
                HASH,
                publication.profileFingerprint(),
                Instant.now().toString()));
  }

  @Test
  void recallRejectsControlsBrokenUnicodeAndExcessBytes() {
    for (String recall : List.of("bad\nline", "bad\uD800", "声".repeat(2731))) {
      assertThrows(
          RuntimeException.class,
          () -> new SoundSpan("sound-id", 0, 0, 1, HASH, recall, "seg-id", HASH));
    }
  }

  @Test
  void proofChecksExactFactsAndTraceRefusalCannotCarryCitations() {
    var original = original();
    String generation = UUID.randomUUID().toString();
    String id = SoundProfile.spanId(original.revisionId(), 0);
    var span =
        new SoundSpan(
            id, 0, 0, 1, HASH, "untrusted", SoundProfile.physicalSegmentId(generation, id), HASH);
    var source = new SoundPublishedSpan(publication(original, generation, List.of(span)), span);
    var facts = List.of("A tone is audible.");
    var proof = new SoundProof(source, facts, SoundProof.factsSha256(facts), HASH);
    assertEquals(facts, proof.facts());
    assertThrows(RuntimeException.class, () -> new SoundProof(source, facts, HASH, HASH));
    assertThrows(
        RuntimeException.class, () -> new SoundProof(source, List.of("same", "same"), HASH, HASH));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundTraceDraft(
                HASH,
                null,
                "abstained",
                "no_evidence",
                List.of(proof),
                "sound-v1",
                "java-sound-answer-v1"));
    assertEquals(
        "abstained",
        new SoundTraceDraft(
                HASH,
                null,
                "abstained",
                "no_evidence",
                List.of(),
                "sound-v1",
                "java-sound-answer-v1")
            .status());
  }

  private static DocumentOriginal original() {
    byte[] content = new byte[] {1, 2};
    return new DocumentOriginal(
        "doc", "revision", "q.wav", "audio", "audio/wav", ModelValues.sha256(content), 2, content);
  }

  private static SoundInputSpan input(
      DocumentOriginal original, int ordinal, long start, long end) {
    return new SoundInputSpan(
        SoundProfile.spanId(original.revisionId(), ordinal),
        ordinal,
        new AudioWaveform(
            original.sourceSha256(), "decoder-v1", start, end, new byte[(int) (end - start) * 2]));
  }

  private static SoundBuildClaim claim(DocumentOriginal original, List<SoundInputSpan> spans) {
    return new SoundBuildClaim(
        new Actor("ws", "owner"),
        original,
        TARGET,
        UUID.randomUUID().toString(),
        "sound-v1",
        "decoder-v1",
        1,
        spans,
        SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", 1));
  }

  private static SoundPublication publication(
      DocumentOriginal original, String generation, List<SoundSpan> spans) {
    return new SoundPublication(
        UUID.randomUUID().toString(),
        "ws",
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.filename(),
        original.mediaType(),
        original.sizeBytes(),
        generation,
        TARGET,
        "sound-v1",
        "decoder-v1",
        1,
        1,
        spans,
        SoundProfile.manifestSha256("ws", original.documentId(), generation, spans),
        SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", 1),
        Instant.now().toString());
  }
}
