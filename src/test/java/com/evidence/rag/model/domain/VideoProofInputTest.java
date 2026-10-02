package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VideoProofInputTest {
  private static final String REVISION = "selected-video-revision";

  @Test
  void preservesSeparateParentAndFrameHashesWithoutExposingTranscriptInLogs() {
    var compilation = VideoCompilationFixture.compilation(true);
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var group = material.groups().getFirst();
    var frame = compilation.frames().getFirst().frame();
    var transcript = transcript(group.transcriptSpanId(), "video-transcript:" + REVISION);
    var input =
        new VideoProofInput(
            compilation.sourceSha256(), material.manifestSha256(), group, frame, transcript);
    assertEquals(compilation.sourceSha256(), input.sourceSha256());
    assertFalse(input.sourceSha256().equals(input.frame().image().sha256()));
    assertEquals(transcript, input.transcript());
    assertFalse(input.toString().contains(transcript.contextText()));
  }

  @Test
  void rejectsInvalidHashesWrongFrameIdentityAndFrameOutsideGroupTime() {
    var compilation = VideoCompilationFixture.compilation(true);
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var group = material.groups().getFirst();
    var frame = compilation.frames().getFirst().frame();
    var transcript = transcript(group.transcriptSpanId(), "video-transcript:" + REVISION);
    assertThrows(
        ApplicationException.class,
        () -> new VideoProofInput("invalid", material.manifestSha256(), group, frame, transcript));
    assertThrows(
        ApplicationException.class,
        () -> new VideoProofInput(compilation.sourceSha256(), null, group, frame, transcript));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoProofInput(
                compilation.sourceSha256(), material.manifestSha256(), null, frame, transcript));
    for (var wrongFrame :
        java.util.List.of(
            new VideoFrame(1, 0, 200_000, frame.image(), 2, 2),
            new VideoFrame(0, 500_000, 200_000, frame.image(), 2, 2),
            new VideoFrame(0, 0, 100_000, frame.image(), 2, 2))) {
      assertThrows(
          ApplicationException.class,
          () ->
              new VideoProofInput(
                  compilation.sourceSha256(),
                  material.manifestSha256(),
                  group,
                  wrongFrame,
                  transcript));
    }
  }

  @Test
  void rejectsTranscriptCandidateFromAnotherSpanOrRevision() {
    var compilation = VideoCompilationFixture.compilation(true);
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var group = material.groups().getFirst();
    var frame = compilation.frames().getFirst().frame();
    for (var wrongTranscript :
        java.util.List.of(
            transcript(
                VideoEvidence.transcriptIdentity(REVISION, 2), "video-transcript:" + REVISION),
            transcript(group.transcriptSpanId(), "video-transcript:other-revision"))) {
      assertThrows(
          ApplicationException.class,
          () ->
              new VideoProofInput(
                  compilation.sourceSha256(),
                  material.manifestSha256(),
                  group,
                  frame,
                  wrongTranscript));
    }
  }

  @Test
  void retainsBlankSpanAndSingleModalityGroupsWithoutInventingMissingMembers() {
    var compilation = VideoCompilationFixture.compilation(true);
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var blankGroup =
        material.groups().stream()
            .filter(
                group ->
                    VideoEvidence.transcriptIdentity(REVISION, 1).equals(group.transcriptSpanId()))
            .findFirst()
            .orElseThrow();
    var blank =
        new VideoProofInput(
            compilation.sourceSha256(),
            material.manifestSha256(),
            blankGroup,
            compilation.frames().get(1).frame(),
            null);
    assertEquals(null, blank.transcript());
    var audioGroup =
        material.groups().stream()
            .filter(group -> group.frameId() == null)
            .findFirst()
            .orElseThrow();
    var audioOnly =
        new VideoProofInput(
            compilation.sourceSha256(),
            material.manifestSha256(),
            audioGroup,
            null,
            transcript(audioGroup.transcriptSpanId(), "video-transcript:" + REVISION));
    assertEquals(null, audioOnly.frame());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoProofInput(
                compilation.sourceSha256(), material.manifestSha256(), blankGroup, null, null));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoProofInput(
                compilation.sourceSha256(),
                material.manifestSha256(),
                audioGroup,
                compilation.frames().getFirst().frame(),
                audioOnly.transcript()));
  }

  private static GroundingText transcript(String physicalId, String contextId) {
    String context = "蓝色圆形。";
    return new GroundingText(
        physicalId,
        contextId,
        context,
        ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
        0,
        context.codePointCount(0, context.length()));
  }
}
