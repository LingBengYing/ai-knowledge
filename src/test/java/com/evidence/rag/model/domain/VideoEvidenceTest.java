package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class VideoEvidenceTest {
  @Test
  void identitiesGroupsAndManifestPreserveRealWindowsSilenceAndAudioOnlyTail() {
    var compilation = VideoCompilationFixture.compilation(true);
    var evidence = VideoEvidence.fromCompilation("revision", compilation);
    assertEquals(2, evidence.frames().size());
    assertEquals(3, evidence.spans().size());
    assertEquals(3, evidence.groups().size());
    assertEquals(4, evidence.projectionCount());
    assertEquals(
        "video-frame-"
            + ModelValues.sha256("revision\0".concat("1").getBytes(StandardCharsets.UTF_8)),
        evidence.frames().get(1).id());
    assertEquals(VideoEvidence.transcriptIdentity("revision", 2), evidence.spans().getLast().id());
    assertNull(evidence.spans().get(1).indexOrdinal());
    assertEquals(1, evidence.spans().getLast().indexOrdinal());
    assertEquals(0, evidence.groups().getFirst().startUs());
    assertEquals(200_000, evidence.groups().getFirst().endUs());
    assertEquals(1_200_000, evidence.groups().get(1).startUs());
    assertEquals(1_400_000, evidence.groups().get(1).endUs());
    assertNull(evidence.groups().getLast().frameId());
    assertEquals(2_000_000, evidence.groups().getLast().startUs());
    assertEquals(4_000_000, evidence.groups().getLast().endUs());
    assertEquals(
        evidence.manifestSha256(),
        VideoEvidence.fromCompilation("revision", compilation).manifestSha256());
    assertNotEquals(
        evidence.manifestSha256(),
        VideoEvidence.fromCompilation("other", compilation).manifestSha256());
    assertEquals("VideoEvidence[redacted]", evidence.toString());
    assertThrows(UnsupportedOperationException.class, () -> evidence.groups().clear());
  }

  @Test
  void noAudioProducesOnlyTrueFrameWindowsWithoutInventingSceneCoverage() {
    var evidence =
        VideoEvidence.fromCompilation("revision", VideoCompilationFixture.compilation(false));
    assertEquals(2, evidence.groups().size());
    assertTrue(evidence.spans().isEmpty());
    assertTrue(evidence.groups().stream().allMatch(group -> group.transcriptSpanId() == null));
    assertEquals(200_000, evidence.groups().getFirst().endUs());
    assertEquals(2, evidence.projectionCount());
  }

  @Test
  void totalRecallLimitRejectsACompleteOversizedCompilationRatherThanTruncating() {
    var frames = new ArrayList<VideoFrameRecall>();
    for (int index = 0; index < 128; index++) {
      frames.add(
          new VideoFrameRecall(
              new VideoFrame(
                  index, index * 1_000_000L, 1_000_000, VideoCompilationFixture.image(), 2, 2),
              new ImageRecall("图".repeat(4096), "vision-v1")));
    }
    var spans = new ArrayList<AudioTranscriptSpan>();
    for (int index = 0; index < 245; index++) {
      spans.add(
          new AudioTranscriptSpan(
              index, index * 1000L, (index + 1) * 1000L, "文".repeat(index == 244 ? 576 : 4096)));
    }
    var audio =
        new AudioTranscription(
            VideoCompilationFixture.SOURCE, "decoder", "asr", "transcriber", 245L * 16000, spans);
    var compilation =
        new VideoCompilation(
            VideoCompilationFixture.SOURCE,
            "decoder",
            VideoCompilationFixture.COMPILER,
            0,
            245_000_000,
            frames,
            audio);
    assertThrows(
        ApplicationException.class, () -> VideoEvidence.fromCompilation("revision", compilation));
  }

  @Test
  void forgedAuthorityMemberIdentitiesAndInvalidGroupsAreRejected() {
    assertThrows(
        ApplicationException.class,
        () -> new VideoFrameEvidence("wrong", "revision", VideoCompilationFixture.frame(0, 0, 1)));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoTranscriptEvidence(
                "wrong", "revision", new AudioTranscriptSpan(0, 0, 1, "text"), 0));
    assertThrows(
        ApplicationException.class,
        () -> new VideoEvidenceGroup("wrong", "revision", 0, 0, 1, null, null));
  }
}
