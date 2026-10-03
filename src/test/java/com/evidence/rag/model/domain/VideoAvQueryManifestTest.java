package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAvQueryManifestTest {
  @Test
  void preparedContentBindsWholeEpochMediaAndCompilerIncludingAudioTail() {
    var value = VideoAvTestFixture.compilation(VideoAvTestFixture.original(), true);
    var prepared = VideoAvQueryManifest.prepared(0, VideoAvMode.JOINT, "compiler-v1", value);
    assertEquals(3, prepared.windowCount());
    assertEquals(2, prepared.visualWindowCount());
    assertEquals(3, prepared.audioWindowCount());
    assertEquals(true, prepared.audioPresent());
    assertEquals("prepared", prepared.status());
    assertEquals(
        ModelValues.sha256(
            ("java-video-av-query-content-v1\0"
                    + value.sourceSha256()
                    + "\0compiler-v1\0"
                    + VideoAvProfile.windowManifestSha256(value))
                .getBytes(StandardCharsets.UTF_8)),
        prepared.contentSha256());
    assertNotEquals(
        prepared.contentSha256(),
        VideoAvQueryManifest.prepared(0, VideoAvMode.JOINT, "compiler-v2", value).contentSha256());
    var epoch = new VideoAvEpoch(value.epoch().sourceFirstPts() + 1, 1, 90000, 720000);
    var changed =
        new VideoAvCompilation(
            value.sourceSha256(),
            value.decoderRevision(),
            epoch,
            value.durationTick(),
            value.hasAudio(),
            value.windows());
    assertNotEquals(
        prepared.contentSha256(),
        VideoAvQueryManifest.prepared(0, VideoAvMode.JOINT, "compiler-v1", changed)
            .contentSha256());
  }

  @Test
  void absentAudioIsValidOnlyForVisualAndFailureHasNoPartialCounts() {
    var value = VideoAvTestFixture.compilation(VideoAvTestFixture.original(), false);
    var prepared = VideoAvQueryManifest.prepared(0, VideoAvMode.VISUAL, "compiler-v1", value);
    assertFalse(prepared.audioPresent());
    assertEquals(0, prepared.audioWindowCount());
    for (var mode : List.of(VideoAvMode.AUDIO, VideoAvMode.JOINT)) {
      assertThrows(
          RuntimeException.class,
          () -> VideoAvQueryManifest.prepared(0, mode, "compiler-v1", value));
    }
    var failed =
        VideoAvQueryManifest.notPrepared(0, VideoAvMode.JOINT, "compiler-v1", value.sourceSha256());
    assertNull(failed.contentSha256());
    assertNull(failed.windowCount());
    assertNull(failed.visualWindowCount());
    assertNull(failed.audioWindowCount());
    assertNull(failed.audioPresent());
    assertEquals("not_prepared", failed.status());
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvQueryManifest(
                0,
                value.sourceSha256(),
                "video",
                "compiler-v1",
                null,
                1,
                null,
                null,
                null,
                VideoAvMode.JOINT,
                "not_prepared"));
  }

  @Test
  void traceKeepsRepeatedSourcesButBindsFullQuestionProfileModeOrderAndCompiler() {
    var one = VideoAvQueryManifest.notPrepared(0, VideoAvMode.JOINT, "compiler-v1", "a".repeat(64));
    var two = VideoAvQueryManifest.notPrepared(1, VideoAvMode.JOINT, "compiler-v1", "a".repeat(64));
    var list = new ArrayList<>(List.of(one, two));
    var trace = trace("b".repeat(64), "c".repeat(64), "embedding-v1", list);
    list.clear();
    assertEquals(2, trace.attachments().size());
    assertEquals(VideoAvQueryTrace.PREPARATION_REVISION, trace.preparationRevision());
    assertEquals(64, trace.manifestSha256().length());
    assertNotEquals(
        trace.manifestSha256(),
        trace("d".repeat(64), "c".repeat(64), "embedding-v1", List.of(one, two)).manifestSha256());
    assertNotEquals(
        trace.manifestSha256(),
        trace("b".repeat(64), "d".repeat(64), "embedding-v1", List.of(one, two)).manifestSha256());
    assertNotEquals(
        trace.manifestSha256(),
        trace("b".repeat(64), "c".repeat(64), "embedding-v2", List.of(one, two)).manifestSha256());
    assertEquals("VideoAvQueryTrace[redacted]", trace.toString());
    assertEquals("VideoAvQueryManifest[redacted]", one.toString());
  }

  @Test
  void traceRejectsMixedReadinessModeCompilerAndDiscontinuousOrdinals() {
    var prepared =
        VideoAvQueryManifest.prepared(
            0,
            VideoAvMode.JOINT,
            "compiler-v1",
            VideoAvTestFixture.compilation(VideoAvTestFixture.original(), true));
    for (var second :
        List.of(
            VideoAvQueryManifest.notPrepared(1, VideoAvMode.JOINT, "compiler-v1", "a".repeat(64)),
            VideoAvQueryManifest.prepared(
                1,
                VideoAvMode.JOINT,
                "compiler-v2",
                VideoAvTestFixture.compilation(VideoAvTestFixture.original(), true)),
            VideoAvQueryManifest.prepared(
                1,
                VideoAvMode.VISUAL,
                "compiler-v1",
                VideoAvTestFixture.compilation(VideoAvTestFixture.original(), true)),
            VideoAvQueryManifest.prepared(
                2,
                VideoAvMode.JOINT,
                "compiler-v1",
                VideoAvTestFixture.compilation(VideoAvTestFixture.original(), true)))) {
      assertThrows(
          RuntimeException.class,
          () -> trace("a".repeat(64), "b".repeat(64), "embedding-v1", List.of(prepared, second)));
    }
    assertThrows(
        RuntimeException.class,
        () -> trace("a".repeat(64), "b".repeat(64), "embedding-v1", List.of()));
  }

  @Test
  void completeBatchWindowLimitIsSharedAndNoIndividualManifestCanClaimMissingCoverage() {
    var one =
        new VideoAvQueryManifest(
            0,
            "a".repeat(64),
            "video",
            "compiler-v1",
            "b".repeat(64),
            601,
            601,
            601,
            true,
            VideoAvMode.JOINT,
            "prepared");
    var two =
        new VideoAvQueryManifest(
            1,
            "a".repeat(64),
            "video",
            "compiler-v1",
            "b".repeat(64),
            600,
            600,
            600,
            true,
            VideoAvMode.JOINT,
            "prepared");
    assertEquals(
        2,
        trace("a".repeat(64), "b".repeat(64), "embedding-v1", List.of(one, two))
            .attachments()
            .size());
    var over =
        new VideoAvQueryManifest(
            1,
            "a".repeat(64),
            "video",
            "compiler-v1",
            "b".repeat(64),
            601,
            601,
            601,
            true,
            VideoAvMode.JOINT,
            "prepared");
    assertThrows(
        RuntimeException.class,
        () -> trace("a".repeat(64), "b".repeat(64), "embedding-v1", List.of(one, over)));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvQueryManifest(
                0,
                "a".repeat(64),
                "video",
                "compiler-v1",
                "b".repeat(64),
                3,
                1,
                1,
                true,
                VideoAvMode.JOINT,
                "prepared"));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvQueryManifest(
                0,
                "a".repeat(64),
                "video",
                "compiler-v1",
                "b".repeat(64),
                1,
                1,
                1,
                false,
                VideoAvMode.JOINT,
                "prepared"));
  }

  private static VideoAvQueryTrace trace(
      String question, String profile, String embedding, List<VideoAvQueryManifest> values) {
    return new VideoAvQueryTrace(VideoAvMode.JOINT, question, profile, embedding, values);
  }
}
