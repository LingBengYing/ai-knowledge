package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoCompilationFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VideoSubtitleDomainTest {
  private static final String PAYLOAD = "a".repeat(64);

  @Test
  void rationalEpochIsSubtractedBeforeOutwardMicrosecondRounding() {
    var cue = cue(0, 2034, 1000, "字幕独有事实 SUB-482 😀");
    var track = track(2, List.of(cue));
    var subtitles = new VideoSubtitleCompilation(60001, 1, 30000, List.of(track));
    assertEquals(2_000_033, subtitles.timelineOriginUs());
    assertEquals(33_966, subtitles.startUs(track, cue));
    assertEquals(1_033_967, subtitles.endUs(track, cue));
    assertEquals(1_033_967, subtitles.endUs());
    assertEquals("subtitle-payload-utf8-v1", VideoSubtitleCompilation.TEXT_FORMAT);
    assertEquals("字幕独有事实 SUB-482 😀", cue.text());
    assertEquals(PAYLOAD, cue.payloadSha256());
  }

  @Test
  void integerOverflowIsAvoidedWhenAddingPacketDuration() {
    var cue = cue(0, Long.MAX_VALUE - 5, 10, "尾部");
    var track = new VideoSubtitleTrack(1, "subrip", 1, 1_000_000, null, List.of(cue));
    var subtitles = new VideoSubtitleCompilation(Long.MAX_VALUE - 5, 1, 1_000_000, List.of(track));
    assertEquals(Long.MAX_VALUE - 5, subtitles.timelineOriginUs());
    assertEquals(0, subtitles.startUs(track, cue));
    assertEquals(10, subtitles.endUs(track, cue));
  }

  @Test
  void hugeRationalCrossProductsAndNegativeEpochRemainExact() {
    var cue = cue(0, 0, 1, "one");
    var track =
        new VideoSubtitleTrack(1, "webvtt", Long.MAX_VALUE, Long.MAX_VALUE, null, List.of(cue));
    var subtitles = new VideoSubtitleCompilation(-1, 1, 3, List.of(track));
    assertEquals(-333334, subtitles.timelineOriginUs());
    assertEquals(333333, subtitles.startUs(track, cue));
    assertEquals(1_333_334, subtitles.endUs(track, cue));
  }

  @Test
  void clearPacketsOverlapsAndDuplicatePtsKeepTheirPacketIdentity() {
    var clear = cue(0, -50, 0, "");
    var first = cue(1, 2000, 2000, "甲");
    var second = cue(2, 2000, 500, "乙");
    var whitespace = cue(3, 0, 0, " \n\t");
    var track = track(1, List.of(clear, first, second, whitespace));
    var subtitles = new VideoSubtitleCompilation(2, 1, 1, List.of(track));
    assertEquals(List.of(clear, first, second, whitespace), subtitles.tracks().getFirst().cues());
    assertEquals(0, subtitles.startUs(track, first));
    assertEquals(0, subtitles.startUs(track, second));
    assertEquals(2_000_000, subtitles.endUs());
    assertThrows(ApplicationException.class, () -> subtitles.startUs(track, clear));
    assertThrows(ApplicationException.class, () -> subtitles.endUs(track, whitespace));
  }

  @Test
  void noTracksAndNoVisibleCueAreCompleteButCannotYieldFactLocators() {
    var empty = new VideoSubtitleCompilation(0, 1, 1000, List.of());
    assertEquals(0, empty.endUs());
    assertTrue(empty.manifestSha256().matches("[a-f0-9]{64}"));
    var blankTrack = track(1, List.of(cue(0, -100, 0, "")));
    var blank = new VideoSubtitleCompilation(0, 1, 1000, List.of(blankTrack));
    assertEquals(0, blank.endUs());
    assertNotEquals(empty.manifestSha256(), blank.manifestSha256());
    assertThrows(ApplicationException.class, () -> blank.startUs(null, null));
  }

  @Test
  void nestedCollectionsAreDefensivelyCopiedAndUnmodifiable() {
    var cues = new ArrayList<>(List.of(cue(0, 0, 1, "<b>原样数据</b>\n尾部")));
    var track = track(1, cues);
    cues.clear();
    assertEquals(1, track.cues().size());
    var tracks = new ArrayList<>(List.of(track));
    var subtitles = new VideoSubtitleCompilation(0, 1, 1000, tracks);
    tracks.clear();
    assertEquals(1, subtitles.tracks().size());
    assertThrows(UnsupportedOperationException.class, () -> track.cues().clear());
    assertThrows(UnsupportedOperationException.class, () -> subtitles.tracks().clear());
    assertEquals("<b>原样数据</b>\n尾部", track.cues().getFirst().text());
  }

  @Test
  void foreignTrackOrCueCannotAcquireThisCompilationTimeLocator() {
    var track = track(1, List.of(cue(0, 0, 10, "甲")));
    var subtitles = new VideoSubtitleCompilation(0, 1, 1000, List.of(track));
    assertThrows(
        ApplicationException.class,
        () -> subtitles.startUs(track(2, track.cues()), track.cues().getFirst()));
    assertThrows(ApplicationException.class, () -> subtitles.endUs(track, cue(0, 0, 10, "乙")));
    assertThrows(ApplicationException.class, () -> subtitles.startUs(track, cue(1, 0, 10, "甲")));
  }

  @Test
  void manifestBindsEpochTrackPacketPayloadAndExactTextIncludingClearPackets() {
    var cue = cue(0, 2000, 10, "<b>预算</b>");
    var track = track(1, List.of(cue));
    var original = new VideoSubtitleCompilation(1, 1, 1, List.of(track));
    var variants =
        List.of(
            new VideoSubtitleCompilation(2, 1, 1, List.of(track)),
            new VideoSubtitleCompilation(1, 2, 2, List.of(track)),
            new VideoSubtitleCompilation(1, 1, 1, List.of(track(2, List.of(cue)))),
            new VideoSubtitleCompilation(
                1, 1, 1, List.of(new VideoSubtitleTrack(1, "webvtt", 1, 1000, null, List.of(cue)))),
            new VideoSubtitleCompilation(
                1,
                1,
                1,
                List.of(new VideoSubtitleTrack(1, "subrip", 1, 1000, "eng", List.of(cue)))),
            new VideoSubtitleCompilation(
                1, 1, 1, List.of(new VideoSubtitleTrack(1, "subrip", 2, 2000, null, List.of(cue)))),
            new VideoSubtitleCompilation(
                1, 1, 1, List.of(track(1, List.of(cue(0, 2001, 10, cue.text()))))),
            new VideoSubtitleCompilation(
                1, 1, 1, List.of(track(1, List.of(cue(0, 2000, 11, cue.text()))))),
            new VideoSubtitleCompilation(
                1, 1, 1, List.of(track(1, List.of(cue(0, 2000, 10, "预算"))))),
            new VideoSubtitleCompilation(
                1,
                1,
                1,
                List.of(
                    track(
                        1,
                        List.of(new VideoSubtitleCue(0, 2000, 10, cue.text(), "b".repeat(64)))))),
            new VideoSubtitleCompilation(
                1, 1, 1, List.of(track(1, List.of(cue, cue(1, 0, 0, ""))))));
    assertTrue(original.manifestSha256().matches("[a-f0-9]{64}"));
    assertEquals(
        original.manifestSha256(),
        new VideoSubtitleCompilation(1, 1, 1, List.of(track)).manifestSha256());
    for (var variant : variants) {
      assertNotEquals(original.manifestSha256(), variant.manifestSha256());
    }
    var blankA =
        new VideoSubtitleCompilation(0, 1, 1, List.of(track(1, List.of(cue(0, 0, 0, "")))));
    var blankB =
        new VideoSubtitleCompilation(0, 1, 1, List.of(track(1, List.of(cue(0, 1, 0, "")))));
    assertNotEquals(blankA.manifestSha256(), blankB.manifestSha256());
  }

  @Test
  void malformedCueTextIdentityAndDurationAreRejected() {
    assertThrows(ApplicationException.class, () -> cue(-1, 0, 1, "字"));
    assertThrows(ApplicationException.class, () -> cue(2048, 0, 1, "字"));
    assertThrows(ApplicationException.class, () -> cue(0, 0, -1, ""));
    assertThrows(ApplicationException.class, () -> cue(0, 0, 0, "字"));
    assertThrows(ApplicationException.class, () -> cue(0, 0, 1, null));
    assertThrows(ApplicationException.class, () -> cue(0, 0, 1, "😀".repeat(4097)));
    assertThrows(ApplicationException.class, () -> cue(0, 0, 1, "x\u0000"));
    assertThrows(ApplicationException.class, () -> cue(0, 0, 1, "x\uD800"));
    assertThrows(ApplicationException.class, () -> cue(0, 0, 1, "x\uDC00"));
    assertThrows(ApplicationException.class, () -> new VideoSubtitleCue(0, 0, 1, "字", null));
    assertThrows(
        ApplicationException.class, () -> new VideoSubtitleCue(0, 0, 1, "字", "A".repeat(64)));
    assertEquals(4096, cue(0, 0, 1, "😀".repeat(4096)).text().codePointCount(0, 8192));
  }

  @Test
  void malformedTrackMetadataAndPacketSequenceAreRejected() {
    var cue = cue(0, 0, 1, "字");
    assertThrows(ApplicationException.class, () -> track(-1, List.of(cue)));
    for (String codec : new String[] {null, "", "ass", "hdmv_pgs_subtitle"}) {
      assertThrows(
          ApplicationException.class,
          () -> new VideoSubtitleTrack(1, codec, 1, 1000, null, List.of(cue)));
    }
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleTrack(1, "subrip", 0, 1000, null, List.of(cue)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleTrack(1, "subrip", 1, 0, null, List.of(cue)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleTrack(1, "subrip", 1, 1000, "", List.of(cue)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleTrack(1, "subrip", 1, 1000, "en\n", List.of(cue)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleTrack(1, "subrip", 1, 1000, "x".repeat(64), List.of(cue)));
    assertThrows(ApplicationException.class, () -> track(1, null));
    assertThrows(ApplicationException.class, () -> track(1, java.util.Arrays.asList(cue, null)));
    assertThrows(ApplicationException.class, () -> track(1, List.of(cue(1, 0, 1, "字"))));
    assertThrows(ApplicationException.class, () -> track(1, List.of(cue, cue)));
    assertEquals(List.of(), track(1, List.of()).cues());
    assertNull(track(1, List.of(cue)).language());
  }

  @Test
  void aggregateLimitsCountAllTracksAndEveryClearPacketWithoutTruncation() {
    var one = track(1, blankPackets(1024));
    var two = track(2, blankPackets(1024));
    assertEquals(2, new VideoSubtitleCompilation(0, 1, 1, List.of(one, two)).tracks().size());
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleCompilation(0, 1, 1, List.of(one, two, track(3, blankPackets(1)))));
    var longCues =
        IntStream.range(0, 122).mapToObj(index -> cue(index, index, 1, "😀".repeat(4096))).toList();
    assertEquals(
        1, new VideoSubtitleCompilation(0, 1, 1000, List.of(track(1, longCues))).tracks().size());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCompilation(
                0,
                1,
                1000,
                List.of(track(1, longCues), track(2, List.of(cue(0, 0, 1, "字".repeat(289)))))));
    assertEquals(
        4,
        new VideoSubtitleCompilation(
                0, 1, 1, IntStream.range(0, 4).mapToObj(index -> track(index, List.of())).toList())
            .tracks()
            .size());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCompilation(
                0,
                1,
                1,
                IntStream.range(0, 5).mapToObj(index -> track(index, List.of())).toList()));
  }

  @Test
  void invalidTimelineAndUnorderedTracksCannotProduceAuthority() {
    var track = track(1, List.of(cue(0, 0, 1, "字")));
    assertThrows(
        ApplicationException.class, () -> new VideoSubtitleCompilation(0, 0, 1, List.of()));
    assertThrows(
        ApplicationException.class, () -> new VideoSubtitleCompilation(0, 1, -1, List.of()));
    assertThrows(ApplicationException.class, () -> new VideoSubtitleCompilation(0, 1, 1, null));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCompilation(
                0, 1, 1, java.util.Arrays.asList((VideoSubtitleTrack) null)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleCompilation(0, 1, 1, List.of(track, track)));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleCompilation(0, 1, 1, List.of(track(2, List.of()), track)));
    assertThrows(
        ApplicationException.class, () -> new VideoSubtitleCompilation(1, 1, 1, List.of(track)));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSubtitleCompilation(
                0, 1, 1, List.of(track(1, List.of(cue(0, 0, 600001, "字"))))));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleCompilation(Long.MAX_VALUE, Long.MAX_VALUE, 1, List.of()));
    assertEquals(
        600_000_000,
        new VideoSubtitleCompilation(0, 1, 1, List.of(track(1, List.of(cue(0, 0, 600000, "字")))))
            .endUs());
  }

  @Test
  void parentProductsBindSubtitleEpochAndCompleteTailWhileOldConstructorsStayNull() {
    var legacy = VideoCompilationFixture.compilation(false);
    var frames = legacy.frames().stream().map(VideoFrameRecall::frame).toList();
    var subtitles =
        new VideoSubtitleCompilation(
            2, 1, 1, List.of(track(1, List.of(cue(0, 2500, 1000, "SUB-482")))));
    var decoded =
        new DecodedVideo(
            legacy.sourceSha256(),
            legacy.decoderRevision(),
            2_000_000,
            4_000_000,
            frames,
            null,
            subtitles);
    var compiled =
        new VideoCompilation(
            legacy.sourceSha256(),
            legacy.decoderRevision(),
            legacy.compilerRevision(),
            2_000_000,
            4_000_000,
            legacy.frames(),
            null,
            null,
            subtitles);
    assertSame(subtitles, decoded.subtitles());
    assertSame(subtitles, compiled.subtitles());
    assertNull(legacy.subtitles());
    assertNull(
        new DecodedVideo(
                legacy.sourceSha256(), legacy.decoderRevision(), 2_000_000, 4_000_000, frames, null)
            .subtitles());
    assertNull(
        new VideoCompilation(
                legacy.sourceSha256(),
                legacy.decoderRevision(),
                legacy.compilerRevision(),
                2_000_000,
                4_000_000,
                legacy.frames(),
                null,
                null)
            .subtitles());
    assertThrows(
        ApplicationException.class,
        () ->
            new DecodedVideo(
                legacy.sourceSha256(),
                legacy.decoderRevision(),
                2_000_001,
                4_000_000,
                frames,
                null,
                subtitles));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoCompilation(
                legacy.sourceSha256(),
                legacy.decoderRevision(),
                legacy.compilerRevision(),
                2_000_001,
                4_000_000,
                legacy.frames(),
                null,
                null,
                subtitles));
    var tail =
        new VideoSubtitleCompilation(2, 1, 1, List.of(track(1, List.of(cue(0, 2500, 4000, "尾部")))));
    assertThrows(
        ApplicationException.class,
        () ->
            new DecodedVideo(
                legacy.sourceSha256(),
                legacy.decoderRevision(),
                2_000_000,
                4_000_000,
                frames,
                null,
                tail));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoCompilation(
                legacy.sourceSha256(),
                legacy.decoderRevision(),
                legacy.compilerRevision(),
                2_000_000,
                4_000_000,
                legacy.frames(),
                null,
                null,
                tail));
  }

  @Test
  void allDiagnosticRepresentationsOmitOriginalTextAndMetadata() {
    var cue = cue(0, 0, 1, "sensitive subtitle");
    var track = new VideoSubtitleTrack(1, "mov_text", 1, 1000, "private-language", List.of(cue));
    var compilation = new VideoSubtitleCompilation(0, 1, 1, List.of(track));
    for (Object value : List.of(cue, track, compilation)) {
      assertTrue(value.toString().endsWith("[redacted]"));
      assertFalse(value.toString().contains(cue.text()));
      assertFalse(value.toString().contains(PAYLOAD));
      assertFalse(value.toString().contains(track.language()));
    }
  }

  private static VideoSubtitleCue cue(int ordinal, long pts, long duration, String text) {
    return new VideoSubtitleCue(ordinal, pts, duration, text, PAYLOAD);
  }

  private static VideoSubtitleTrack track(int index, List<VideoSubtitleCue> cues) {
    return new VideoSubtitleTrack(index, "subrip", 1, 1000, null, cues);
  }

  private static List<VideoSubtitleCue> blankPackets(int count) {
    return IntStream.range(0, count).mapToObj(index -> cue(index, 0, 0, "")).toList();
  }
}
