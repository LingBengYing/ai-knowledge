package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoSubtitleQueryDomainTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void publishedCueRejectsForeignTrackRevisionUnsealedHashesAndBlankPackets() {
    var valid = source();
    assertThrows(
        ApplicationException.class,
        () -> published(null, valid.source(), valid.track(), HASH, HASH, "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () -> published(valid.publication(), null, valid.track(), HASH, HASH, "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () -> published(valid.publication(), valid.source(), null, HASH, HASH, "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            published(
                valid.publication(),
                valid.track().cues().getFirst(),
                valid.track(),
                HASH,
                HASH,
                "video/mp4"));
    for (String bad : List.of("", "bad-hash")) {
      assertThrows(
          ApplicationException.class,
          () ->
              published(
                  valid.publication(), valid.source(), valid.track(), bad, HASH, "video/mp4"));
      assertThrows(
          ApplicationException.class,
          () ->
              published(
                  valid.publication(), valid.source(), valid.track(), HASH, bad, "video/mp4"));
    }
    assertThrows(
        ApplicationException.class,
        () ->
            published(valid.publication(), valid.source(), valid.track(), HASH, HASH, "audio/mp4"));
    var wrong =
        new PublicationVersion(
            "document",
            "publication",
            "other",
            "generation",
            HASH,
            "java-video-compiler-v3:" + HASH,
            valid.publication().target(),
            HASH,
            4);
    assertThrows(
        ApplicationException.class,
        () -> published(wrong, valid.source(), valid.track(), HASH, HASH, "video/mp4"));
  }

  private static PublishedVideoSubtitleEvidence source() {
    var compilation = VideoSubtitleCompilationFixture.compilation(false);
    var authority = VideoSubtitleEvidence.fromCompilation("revision", compilation);
    var track = authority.tracks().getFirst();
    var publication =
        new PublicationVersion(
            "document",
            "publication",
            "revision",
            "generation",
            compilation.sourceSha256(),
            compilation.compilerRevision(),
            new IndexTarget("embedding", "vector", "models", 3),
            HASH,
            5);
    return published(
        publication,
        track.cues().get(1),
        track,
        authority.manifestSha256(),
        compilation.subtitles().manifestSha256(),
        "video/mp4");
  }

  private static PublishedVideoSubtitleEvidence published(
      PublicationVersion publication,
      VideoSubtitleCueEvidence cue,
      VideoSubtitleTrackEvidence track,
      String manifest,
      String nativeManifest,
      String mime) {
    return new PublishedVideoSubtitleEvidence(
        publication,
        "physical",
        HASH,
        cue,
        track,
        manifest,
        nativeManifest,
        "decoder-v2",
        "video.mp4",
        mime);
  }
}
