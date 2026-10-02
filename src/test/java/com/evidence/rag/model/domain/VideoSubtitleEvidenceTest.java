package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VideoSubtitleEvidenceTest {
  @Test
  void allPacketsHaveStableIdentityAndOffsetsInCompleteSameTrackText() {
    var result =
        VideoSubtitleEvidence.fromCompilation(
            "revision", VideoSubtitleCompilationFixture.compilation(false));
    assertEquals(2, result.tracks().size());
    assertEquals(3, result.projectionCount());
    var track = result.tracks().getFirst();
    assertEquals("\n预算😀42万元。\n补充条件：须经审批。\n", track.text());
    assertEquals(sha(track.text()), track.textSha256());
    assertEquals("video-subtitle-track-" + sha("revision\0" + 1), track.id());
    var first = track.cues().get(1);
    assertEquals("video-subtitle-cue-" + sha("revision\0" + 1 + "\0" + 1), first.id());
    assertEquals(1, first.startOffset());
    assertEquals(9, first.endOffset());
    assertEquals(500_000L, first.startUs());
    assertEquals(1_500_000L, first.endUs());
    assertEquals(0, first.indexOrdinal());
    assertEquals(sha(first.cue().text()), first.textSha256());
    assertEquals(1, track.cues().get(2).indexOrdinal());
    assertEquals(2, result.tracks().get(1).cues().getFirst().indexOrdinal());
    assertNull(track.cues().getFirst().indexOrdinal());
    assertNull(track.cues().getFirst().startUs());
    assertNull(track.cues().getLast().endUs());
    assertEquals(21, track.cues().getLast().startOffset());
    assertEquals(21, track.cues().getLast().endOffset());
  }

  @Test
  void emptyTracksHaveARealManifestAndOptionalOcrIsExplicitlyBound() {
    var without = VideoSubtitleCompilationFixture.compilation(false, List.of());
    var first = VideoSubtitleEvidence.fromCompilation("revision", without);
    assertEquals(0, first.projectionCount());
    assertEquals(List.of(), first.tracks());
    assertTrue(first.manifestSha256().matches("[a-f0-9]{64}"));
    assertNotEquals(
        first.manifestSha256(),
        VideoSubtitleEvidence.fromCompilation("other", without).manifestSha256());
    assertNotEquals(
        first.manifestSha256(),
        VideoSubtitleEvidence.fromCompilation(
                "revision", VideoSubtitleCompilationFixture.compilation(true, List.of()))
            .manifestSha256());
    assertNotEquals(
        first.manifestSha256(),
        VideoSubtitleEvidence.fromCompilation(
                "revision", VideoSubtitleCompilationFixture.compilation(false))
            .manifestSha256());
  }

  @Test
  void completeAuthorityCannotBeBuiltFromMissingOrLegacySubtitleProduct() {
    assertThrows(
        ApplicationException.class, () -> VideoSubtitleEvidence.fromCompilation("revision", null));
    assertThrows(
        ApplicationException.class,
        () ->
            VideoSubtitleEvidence.fromCompilation(
                "revision", com.evidence.rag.support.VideoCompilationFixture.compilation(false)));
    var v3 = VideoSubtitleCompilationFixture.compilation(false);
    var mismatched =
        new VideoCompilation(
            v3.sourceSha256(),
            v3.decoderRevision(),
            "java-video-compiler-v2:" + "c".repeat(64),
            v3.timelineOriginUs(),
            v3.durationUs(),
            v3.frames(),
            v3.audio(),
            v3.ocr(),
            v3.subtitles());
    assertThrows(
        ApplicationException.class,
        () -> VideoSubtitleEvidence.fromCompilation("revision", mismatched));
    assertThrows(
        ApplicationException.class, () -> VideoSubtitleTrackEvidence.identity("revision", -1));
    assertThrows(
        ApplicationException.class, () -> VideoSubtitleCueEvidence.identity("revision", 1, 2048));
  }

  @Test
  void cueEvidenceCannotForgeIdentityOffsetsOrBlankFactLocators() {
    var track =
        VideoSubtitleEvidence.fromCompilation(
                "revision", VideoSubtitleCompilationFixture.compilation(false))
            .tracks()
            .getFirst();
    var good = track.cues().get(1);
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCueEvidence(
                "wrong", "revision", track.id(), 1, good.cue(), 1, 9, 500_000L, 1_500_000L, 0));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCueEvidence(
                good.id(), "revision", track.id(), 1, good.cue(), 1, 10, 500_000L, 1_500_000L, 0));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCueEvidence(
                good.id(), "revision", track.id(), 1, good.cue(), 1, 9, null, null, null));
    var blank = track.cues().getFirst();
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCueEvidence(
                blank.id(), "revision", track.id(), 1, blank.cue(), 0, 0, 0L, 1L, 0));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleTrackEvidence(
                track.id(),
                track.revisionId(),
                track.track(),
                track.text() + "altered",
                track.textSha256(),
                track.cues()));
  }

  @Test
  void authorityCollectionsAreImmutableAndDiagnosticsRemainRedacted() {
    var result =
        VideoSubtitleEvidence.fromCompilation(
            "revision", VideoSubtitleCompilationFixture.compilation(false));
    assertThrows(UnsupportedOperationException.class, () -> result.tracks().clear());
    var track = result.tracks().getFirst();
    assertThrows(UnsupportedOperationException.class, () -> track.cues().clear());
    for (Object object : List.of(result, track, track.cues().getFirst())) {
      assertTrue(object.toString().endsWith("[redacted]"));
    }
    assertNotEquals(
        VideoSubtitleCueEvidence.identity("revision", 1, 0),
        VideoSubtitleCueEvidence.identity("revision", 2, 0));
    assertNotEquals(
        VideoSubtitleCueEvidence.identity("revision", 1, 0),
        VideoSubtitleCueEvidence.identity("other", 1, 0));
  }

  @Test
  void subtitleAdditionsCannotExceedTheWholeVideoProjectionLimit() {
    var base = VideoSubtitleCompilationFixture.compilation(false);
    var segments =
        IntStream.range(0, 3000)
            .mapToObj(index -> new VideoOcrSegment(index, index, index + 1, "字"))
            .toList();
    var frames =
        List.of(
            new VideoFrameOcr(
                0,
                base.frames().getFirst().frame().image().sha256(),
                new ImageDimensions(2, 2),
                "字".repeat(3000),
                segments,
                List.of(new ImageTextRegion(0, 3000, 0, 0, 2, 2))),
            new VideoFrameOcr(
                1,
                base.frames().get(1).frame().image().sha256(),
                new ImageDimensions(2, 2),
                "",
                List.of(),
                List.of()));
    var cues =
        IntStream.range(0, 1100)
            .mapToObj(index -> new VideoSubtitleCue(index, 2500, 1, "文", "a".repeat(64)))
            .toList();
    var compilation =
        new VideoCompilation(
            base.sourceSha256(),
            base.decoderRevision(),
            base.compilerRevision(),
            base.timelineOriginUs(),
            base.durationUs(),
            base.frames(),
            null,
            new VideoOcrCompilation("ocr-v1", frames),
            new VideoSubtitleCompilation(
                2, 1, 1, List.of(new VideoSubtitleTrack(1, "subrip", 1, 1000, null, cues))));
    assertEquals(3000, VideoOcrEvidence.fromCompilation("revision", compilation).segments().size());
    assertThrows(
        ApplicationException.class,
        () -> VideoSubtitleEvidence.fromCompilation("revision", compilation));
  }

  @Test
  void subtitleRecallCannotBypassTheCombinedFrameAndTranscriptTextCapacity() {
    var base = VideoSubtitleCompilationFixture.compilation(false);
    var frames =
        IntStream.range(0, 128)
            .mapToObj(
                index ->
                    new VideoFrameRecall(
                        new VideoFrame(
                            index,
                            index * 100_000L,
                            100_000,
                            base.frames().getFirst().frame().image(),
                            2,
                            2),
                        new ImageRecall("画".repeat(4096), "vision-v1")))
            .toList();
    var spans =
        IntStream.range(0, 200)
            .mapToObj(
                index ->
                    new AudioTranscriptSpan(
                        index, index * 1000L, (index + 1) * 1000L, "声".repeat(4096)))
            .toList();
    var cues =
        IntStream.range(0, 122)
            .mapToObj(
                index -> new VideoSubtitleCue(index, 2500, 1, "文".repeat(4096), "a".repeat(64)))
            .toList();
    var compilation =
        new VideoCompilation(
            base.sourceSha256(),
            base.decoderRevision(),
            base.compilerRevision(),
            base.timelineOriginUs(),
            200_000_000,
            frames,
            new AudioTranscription(
                base.sourceSha256(),
                base.decoderRevision(),
                "asr-v1",
                "transcription-v1",
                3_200_000,
                spans),
            null,
            new VideoSubtitleCompilation(
                2, 1, 1, List.of(new VideoSubtitleTrack(1, "subrip", 1, 1000, null, cues))));
    assertEquals(328, VideoEvidence.fromCompilation("revision", compilation).projectionCount());
    assertThrows(
        ApplicationException.class,
        () -> VideoSubtitleEvidence.fromCompilation("revision", compilation));
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
