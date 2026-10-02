package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioSourceEvidenceTest {
  private static final String TEXT = "备案后预算为42元。";
  private static final String FULL = "😀\n\n\n" + TEXT;

  @Test
  void codePointExcerptKeepsWholeServerTimeRatherThanInterpolatingInsideTheSpan() {
    var source = new AudioSourceEvidence(published(), 7, 4 + TEXT.length());
    assertEquals("预算为42元。", source.quote());
    assertEquals(2000, source.startMs());
    assertEquals(3000, source.endMs());
    assertTrue(source.toString().contains("redacted"));
    assertTrue(source.evidence().toString().contains("redacted"));
  }

  @Test
  void publishedAudioRequiresSameRevisionPhysicalIdentityAndCompleteContextSnippet() {
    var good = published();
    var invalid =
        List.<Runnable>of(
            () ->
                new PublishedAudioEvidence(
                    null,
                    "physical",
                    "b".repeat(64),
                    good.span(),
                    good.transcript(),
                    "meeting.wav",
                    "audio/wav"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "other",
                    "b".repeat(64),
                    good.span(),
                    good.transcript(),
                    "meeting.wav",
                    "audio/wav"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "physical",
                    "bad",
                    good.span(),
                    good.transcript(),
                    "meeting.wav",
                    "audio/wav"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "physical",
                    "b".repeat(64),
                    null,
                    good.transcript(),
                    "meeting.wav",
                    "audio/wav"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "physical",
                    "b".repeat(64),
                    good.span(),
                    null,
                    "meeting.wav",
                    "audio/wav"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "physical",
                    "b".repeat(64),
                    good.span(),
                    good.transcript(),
                    "meeting.wav",
                    "video/mp4"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "physical",
                    "b".repeat(64),
                    good.span(),
                    new GroundingText(
                        "physical", "other/audio", FULL, sha(FULL), 4, 4 + TEXT.length()),
                    "meeting.wav",
                    "audio/wav"),
            () ->
                new PublishedAudioEvidence(
                    good.publication(),
                    "physical",
                    "b".repeat(64),
                    good.span(),
                    new GroundingText(
                        "physical", "publication/audio", FULL, sha(FULL), 5, 4 + TEXT.length()),
                    "meeting.wav",
                    "audio/wav"));
    invalid.forEach(value -> assertThrows(ApplicationException.class, value::run));
  }

  @Test
  void excerptCannotClaimUnretrievedOrEmptyTranscriptRange() {
    var good = published();
    assertThrows(ApplicationException.class, () -> new AudioSourceEvidence(null, 0, 1));
    assertThrows(ApplicationException.class, () -> new AudioSourceEvidence(good, 3, 5));
    assertThrows(ApplicationException.class, () -> new AudioSourceEvidence(good, 4, 100));
    assertThrows(ApplicationException.class, () -> new AudioSourceEvidence(good, 5, 5));
  }

  @Test
  void originalBytesMustMatchSourceIdentityAndCanonicalMediaType() {
    var good = published();
    assertThrows(
        ApplicationException.class,
        () -> new AudioSourceEvidence(good, 4, 5, new SourceAudio("audio/wav", new byte[] {1, 2})));
    assertThrows(
        ApplicationException.class,
        () -> new AudioSourceEvidence(good, 4, 5, new SourceAudio("audio/mpeg", new byte[] {0})));
    var source = new AudioSourceEvidence(good, 4, 5, new SourceAudio("audio/wav", new byte[] {0}));
    assertEquals(1, source.audio().content().length);
  }

  private static PublishedAudioEvidence published() {
    var publication =
        new PublicationVersion(
            "document",
            "publication",
            "revision",
            "generation",
            ModelValues.sha256(new byte[] {0}),
            "java-audio-compiler-v1:" + "a".repeat(64),
            new IndexTarget("embedding", "projection", "model", 2),
            "c".repeat(64),
            2);
    var span =
        new AudioEvidence(
            "audio-" + sha("revision\0" + 2), "revision", 2, 2000, 3000, TEXT, sha(TEXT), 1);
    var context =
        new GroundingText("physical", "publication/audio", FULL, sha(FULL), 4, 4 + TEXT.length());
    return new PublishedAudioEvidence(
        publication, "physical", "b".repeat(64), span, context, "meeting.wav", "audio/wav");
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
