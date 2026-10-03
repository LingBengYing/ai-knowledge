package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SoundDomainBoundaryTest {
  private static final String HASH = "a".repeat(64);
  private static final IndexTarget TARGET = new IndexTarget("embedding", HASH, "embedding-v1", 2);

  @Test
  void windowAxisRejectsNegativeEmptyReversedOutsideAndOverlongSamples() {
    for (long[] axis :
        List.of(
            new long[] {-1, 1},
            new long[] {0, 0},
            new long[] {2, 1},
            new long[] {9599999, 9600001},
            new long[] {0, 480001})) {
      assertThrows(RuntimeException.class, () -> span(0, axis[0], axis[1], "", "seg-id"));
    }
    for (int ordinal : new int[] {-1, 600}) {
      assertThrows(RuntimeException.class, () -> span(ordinal, 0, 1, "", "seg-id"));
      assertThrows(
          RuntimeException.class,
          () -> new SoundInputSpan("input", ordinal, wave(0, 1, "decoder-v1")));
      assertThrows(RuntimeException.class, () -> SoundProfile.spanId("rev", ordinal));
    }
    assertEquals(480000, span(599, 9120000, 9600000, "", "seg-id").endSample() - 9120000);
  }

  @Test
  void profileAndManifestRejectUnsupportedWindowSizeAndDuplicatePhysicalEntries() {
    for (int seconds : new int[] {0, 31}) {
      assertThrows(
          RuntimeException.class,
          () -> SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", seconds));
    }
    assertThrows(
        RuntimeException.class, () -> SoundProfile.manifestSha256("ws", "doc", "gen", List.of()));
    assertThrows(
        RuntimeException.class,
        () ->
            SoundProfile.manifestSha256(
                "ws", "doc", "gen", Collections.nCopies(601, span(0, 0, 1, "", "seg-id"))));
    assertThrows(
        RuntimeException.class,
        () ->
            SoundProfile.manifestSha256(
                "ws", "doc", "gen", List.of(span(0, 0, 1, "", "same"), span(1, 1, 2, "", "same"))));
    assertThrows(RuntimeException.class, () -> span(0, 0, 1, "", "seg-id", "G".repeat(64)));
  }

  @Test
  void claimRequiresCanonicalGenerationAndOriginalAudio() {
    for (String generation : List.of("not-a-generation", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {
      assertThrows(
          RuntimeException.class, () -> claim(original(), generation, List.of(input(0, 0, 1))));
    }
    var text =
        new DocumentOriginal(
            "doc",
            "rev",
            "q.txt",
            "document",
            "text/plain",
            ModelValues.sha256(new byte[] {1}),
            1,
            new byte[] {1});
    assertThrows(
        RuntimeException.class,
        () -> claim(text, UUID.randomUUID().toString(), List.of(input(0, 0, 1))));
    assertThrows(
        RuntimeException.class, () -> claim(original(), UUID.randomUUID().toString(), List.of()));
    assertThrows(
        RuntimeException.class,
        () ->
            claim(
                original(),
                UUID.randomUUID().toString(),
                Collections.nCopies(601, input(0, 0, 1))));
  }

  @Test
  void claimRejectsOtherSpanIdentityDecoderOverlongChunkAndShortInterior() {
    var wrongId = new SoundInputSpan("another-span", 0, wave(0, 1, "decoder-v1"));
    var wrongDecoder =
        new SoundInputSpan(SoundProfile.spanId("rev", 0), 0, wave(0, 1, "decoder-v2"));
    for (List<SoundInputSpan> spans :
        List.of(
            List.of(wrongId),
            List.of(wrongDecoder),
            List.of(input(0, 0, 16001)),
            List.of(input(0, 0, 1), input(1, 1, 2)))) {
      assertThrows(
          RuntimeException.class, () -> claim(original(), UUID.randomUUID().toString(), spans));
    }
  }

  @Test
  void publicationRejectsUnsupportedMimeSourceSizeSampleCountAndEmptyOrOversizedWindows() {
    for (String mime : List.of("text/plain", "audio/x-wav")) {
      var input = new PublicationInput();
      input.mime = mime;
      assertThrows(RuntimeException.class, input::build);
    }
    for (long bytes : new long[] {0, 20L * 1024 * 1024 + 1}) {
      var input = new PublicationInput();
      input.bytes = bytes;
      assertThrows(RuntimeException.class, input::build);
    }
    for (long samples : new long[] {0, 9600001}) {
      var input = new PublicationInput();
      input.samples = samples;
      assertThrows(RuntimeException.class, input::build);
    }
    for (List<SoundSpan> spans :
        List.of(
            List.<SoundSpan>of(),
            Collections.nCopies(601, new PublicationInput().spans.getFirst()))) {
      var input = new PublicationInput();
      input.spans = spans;
      assertThrows(RuntimeException.class, input::build);
    }
  }

  @Test
  void publicationRejectsNoncanonicalGenerationInvalidTimestampAndChangedProfile() {
    for (String generation : List.of("malformed", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")) {
      var input = new PublicationInput();
      input.generation = generation;
      assertThrows(RuntimeException.class, input::build);
    }
    var time = new PublicationInput();
    time.created = "tomorrow";
    assertThrows(RuntimeException.class, time::build);
    var profile = new PublicationInput();
    profile.profile = HASH;
    assertThrows(RuntimeException.class, profile::build);
  }

  @Test
  void publicationRejectsSkippedOrRenamedWindowGapLongChunkShortInteriorAndMissingTail() {
    for (int variation = 0; variation < 6; variation++) {
      var input = new PublicationInput();
      String physical = input.spans.getFirst().physicalSegmentId();
      switch (variation) {
        case 0 -> input.spans = List.of(span(1, 0, 1, "", physical));
        case 1 ->
            input.spans = List.of(new SoundSpan("other-span", 0, 0, 1, HASH, "", physical, HASH));
        case 2 -> input.spans = List.of(span(0, 1, 2, "", physical));
        case 3 -> input.spans = List.of(span(0, 0, 16001, "", physical));
        case 4 ->
            input.spans = List.of(span(0, 0, 1, "", physical), span(1, 1, 2, "", "other-physical"));
        default -> input.samples = 2;
      }
      assertThrows(RuntimeException.class, input::build);
    }
  }

  @Test
  void receiptRejectsUnsafeFloat32ValuesZeroVectorAndWrongDimensions() {
    for (List<Double> vector :
        List.of(
            List.of(Double.NaN, 1.0),
            List.of(Double.POSITIVE_INFINITY, 1.0),
            List.of(Double.MAX_VALUE, 1.0),
            List.of(0.0, -0.0),
            List.of(1.0),
            Collections.nCopies(3073, 1.0))) {
      assertThrows(RuntimeException.class, () -> entry("sound-id", "seg-id", vector));
    }
    var sparse = new ArrayList<Double>();
    sparse.add(null);
    sparse.add(1.0);
    assertThrows(RuntimeException.class, () -> entry("sound-id", "seg-id", sparse));
    assertEquals(0.0, entry("sound-id", "seg-id", List.of(-0.0, 1.0)).vector().getFirst());
  }

  @Test
  void receiptCannotLoseDuplicateOrAliasOneCompleteWindow() {
    var first = entry("first", "first-physical", List.of(1.0, 0.0));
    var other = entry("other", "other-physical", List.of(0.0, 1.0));
    assertThrows(
        RuntimeException.class,
        () -> new SoundReceipt(List.of(), new VerifiedRevision(HASH, HASH, 1)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundReceipt(
                Collections.nCopies(601, first), new VerifiedRevision(HASH, HASH, 601)));
    assertThrows(
        RuntimeException.class,
        () -> new SoundReceipt(List.of(first, other), new VerifiedRevision(HASH, HASH, 1)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundReceipt(
                List.of(first, entry("first", "new-physical", List.of(1.0, 0.0))),
                new VerifiedRevision(HASH, HASH, 2)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundReceipt(
                List.of(first, entry("new", "first-physical", List.of(1.0, 0.0))),
                new VerifiedRevision(HASH, HASH, 2)));
  }

  @Test
  void stateAndSourceBindEveryOriginalMetadataFieldAndRejectProfileOrTargetDrift() {
    var publication = new PublicationInput().build();
    var published = new SoundPublishedSpan(publication, publication.spans().getFirst());
    var proof =
        new SoundProof(
            published,
            List.of("A tone is audible."),
            SoundProof.factsSha256(List.of("A tone is audible.")),
            HASH);
    for (DocumentOriginal changed :
        List.of(
            original("other-doc", "rev", "q.wav", "audio/wav", new byte[] {1, 2}),
            original("doc", "other-rev", "q.wav", "audio/wav", new byte[] {1, 2}),
            original("doc", "rev", "other.wav", "audio/wav", new byte[] {1, 2}),
            original("doc", "rev", "q.wav", "audio/mpeg", new byte[] {1, 2}),
            original("doc", "rev", "q.wav", "audio/wav", new byte[] {2, 1}),
            original("doc", "rev", "q.wav", "audio/wav", new byte[] {1, 2, 3}))) {
      assertThrows(
          RuntimeException.class,
          () -> new SoundState(changed, TARGET, publication.profileFingerprint(), publication));
      assertThrows(RuntimeException.class, () -> new SoundSource("trace", 1, proof, changed));
    }
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundState(
                original(),
                new IndexTarget("other", HASH, "embedding-v1", 2),
                publication.profileFingerprint(),
                publication));
    assertThrows(
        RuntimeException.class, () -> new SoundState(original(), TARGET, HASH, publication));
    for (int ordinal : new int[] {0, 33}) {
      assertThrows(
          RuntimeException.class, () -> new SoundSource("trace", ordinal, proof, original()));
    }
  }

  @Test
  void scopeRequiresTheWholeSelectedSetWithoutDuplicateOrOtherWorkspacePublication() {
    var publication = new PublicationInput().build();
    var actor = new Actor("ws", "owner");
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundScope(
                new Actor("other", "owner"),
                DocumentSelection.allDocuments(),
                List.of(publication)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundScope(
                actor, DocumentSelection.allDocuments(), List.of(publication, publication)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundScope(
                actor,
                DocumentSelection.selected(List.of("doc", "missing")),
                List.of(publication)));
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundScope(
                actor, DocumentSelection.allDocuments(), Collections.nCopies(129, publication)));
    assertEquals(
        List.of(),
        new SoundScope(actor, DocumentSelection.selected(List.of()), List.of()).publications());
    assertThrows(
        RuntimeException.class,
        () ->
            new SoundPublishedSpan(
                publication,
                span(0, 0, 1, "changed", publication.spans().getFirst().physicalSegmentId())));
  }

  @Test
  void traceStatusCannotContradictItsReasonHashCitationsOrPolicy() {
    var pub = new PublicationInput().build();
    var facts = List.of("A tone is audible.");
    var proof =
        new SoundProof(
            new SoundPublishedSpan(pub, pub.spans().getFirst()),
            facts,
            SoundProof.factsSha256(facts),
            HASH);
    assertThrows(
        RuntimeException.class,
        () -> trace("answered", "reason", HASH, List.of(proof), "java-sound-answer-v1"));
    assertThrows(
        RuntimeException.class,
        () -> trace("answered", null, HASH, List.of(), "java-sound-answer-v1"));
    assertThrows(
        RuntimeException.class,
        () -> trace("abstained", "no_evidence", HASH, List.of(), "java-sound-answer-v1"));
    assertThrows(
        RuntimeException.class,
        () -> trace("partial", null, HASH, List.of(proof), "java-sound-answer-v1"));
    assertThrows(
        RuntimeException.class,
        () -> trace("answered", null, HASH, List.of(proof), "java-sound-answer-v2"));
    assertThrows(
        RuntimeException.class,
        () ->
            trace("answered", null, HASH, Collections.nCopies(33, proof), "java-sound-answer-v1"));
    var missingProof = new ArrayList<SoundProof>();
    missingProof.add(null);
    assertThrows(
        RuntimeException.class,
        () -> trace("answered", null, HASH, missingProof, "java-sound-answer-v1"));
    for (String status : List.of("answered", "partial")) {
      assertThrows(RuntimeException.class, () -> new SoundTraceReceipt("trace", status, "reason"));
    }
    assertThrows(RuntimeException.class, () -> new SoundTraceReceipt("trace", "abstained", null));
  }

  @Test
  void factsRemainExactCanonicalUnicodeAndRejectIncompleteOrOversizedLists() {
    assertEquals("[\"声音\\\"\\\\😀\",\"尾声\"]", SoundProof.factsJson(List.of("声音\"\\😀", "尾声")));
    for (List<String> facts :
        List.of(
            List.<String>of(),
            Collections.nCopies(17, "fact"),
            List.of(" "),
            List.of("x".repeat(1025)),
            List.of("声".repeat(1024), "音".repeat(1024), "尾".repeat(1024)))) {
      assertThrows(RuntimeException.class, () -> SoundProof.factsJson(facts));
    }
    var missing = new ArrayList<String>();
    missing.add(null);
    assertThrows(RuntimeException.class, () -> SoundProof.factsJson(missing));
    for (int count : new int[] {-1, 601}) {
      assertThrows(RuntimeException.class, () -> new SoundManagedEvidence("rev", "pub", count));
    }
    assertThrows(RuntimeException.class, () -> new SoundManagedEvidence("rev", null, 1));
    assertThrows(RuntimeException.class, () -> new SoundManagedEvidence("rev", "pub", 0));
  }

  private static SoundTraceDraft trace(
      String status, String reason, String answer, List<SoundProof> proofs, String policy) {
    return new SoundTraceDraft(HASH, answer, status, reason, proofs, "sound-v1", policy);
  }

  private static SoundReceipt.Entry entry(String id, String physical, List<Double> vector) {
    return new SoundReceipt.Entry(id, physical, "", vector, HASH);
  }

  private static SoundSpan span(int ordinal, long start, long end, String recall, String physical) {
    return span(ordinal, start, end, recall, physical, HASH);
  }

  private static SoundSpan span(
      int ordinal, long start, long end, String recall, String physical, String hash) {
    return new SoundSpan(
        SoundProfile.spanId("rev", Math.max(0, Math.min(599, ordinal))),
        ordinal,
        start,
        end,
        hash,
        recall,
        physical,
        HASH);
  }

  private static DocumentOriginal original() {
    return original("doc", "rev", "q.wav", "audio/wav", new byte[] {1, 2});
  }

  private static DocumentOriginal original(
      String doc, String revision, String filename, String mime, byte[] bytes) {
    return new DocumentOriginal(
        doc, revision, filename, "audio", mime, ModelValues.sha256(bytes), bytes.length, bytes);
  }

  private static AudioWaveform wave(long start, long end, String decoder) {
    return new AudioWaveform(
        original().sourceSha256(), decoder, start, end, new byte[(int) (end - start) * 2]);
  }

  private static SoundInputSpan input(int ordinal, long start, long end) {
    return new SoundInputSpan(
        SoundProfile.spanId("rev", ordinal), ordinal, wave(start, end, "decoder-v1"));
  }

  private static SoundBuildClaim claim(
      DocumentOriginal original, String generation, List<SoundInputSpan> spans) {
    return new SoundBuildClaim(
        new Actor("ws", "owner"),
        original,
        TARGET,
        generation,
        "sound-v1",
        "decoder-v1",
        1,
        spans,
        SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", 1));
  }

  private static final class PublicationInput {
    String generation = UUID.randomUUID().toString();
    String mime = "audio/wav";
    long bytes = 2;
    long samples = 1;
    List<SoundSpan> spans =
        List.of(
            span(
                0,
                0,
                1,
                "",
                SoundProfile.physicalSegmentId(generation, SoundProfile.spanId("rev", 0))));
    String manifest = SoundProfile.manifestSha256("ws", "doc", generation, spans);
    String profile = SoundProfile.fingerprint(TARGET, "sound-v1", "decoder-v1", 1);
    String created = Instant.now().toString();

    SoundPublication build() {
      return new SoundPublication(
          "pub",
          "ws",
          "doc",
          "rev",
          original().sourceSha256(),
          "q.wav",
          mime,
          bytes,
          generation,
          TARGET,
          "sound-v1",
          "decoder-v1",
          1,
          samples,
          spans,
          manifest,
          profile,
          created);
    }
  }
}
