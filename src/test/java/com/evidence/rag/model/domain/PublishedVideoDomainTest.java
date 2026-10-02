package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishedVideoDomainTest {
  private static final String REVISION = "published-video-revision";
  private static final String HASH = "a".repeat(64);

  @Test
  void recallCandidateRequiresVideoCompilerAndSealedSourceIdentity() {
    var publication = source().source().publication();
    String frameId = source().source().group().frameId();
    assertThrows(
        ApplicationException.class,
        () -> candidate(null, VideoTraceEvidence.Kind.VISUAL, frameId, HASH, "蓝灯", "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () -> candidate(publication, null, frameId, HASH, "蓝灯", "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            candidate(
                publication(publication, REVISION, "java-text-parser-v1"),
                VideoTraceEvidence.Kind.VISUAL,
                frameId,
                HASH,
                "蓝灯",
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            candidate(publication, VideoTraceEvidence.Kind.VISUAL, null, HASH, "蓝灯", "video/mp4"));
    for (String hash : Arrays.asList(null, "unsealed")) {
      assertThrows(
          ApplicationException.class,
          () ->
              candidate(
                  publication, VideoTraceEvidence.Kind.VISUAL, frameId, hash, "蓝灯", "video/mp4"));
    }
    var transcript =
        candidate(
            publication,
            VideoTraceEvidence.Kind.TRANSCRIPT,
            source().source().group().transcriptSpanId(),
            HASH,
            "等待5秒",
            "video/mp4");
    assertEquals(VideoTraceEvidence.Kind.TRANSCRIPT, transcript.kind());
  }

  @Test
  void recallCandidatePreservesUnicodeAndRejectsMissingOversizeOrMalformedText() {
    var publication = source().source().publication();
    String frameId = source().source().group().frameId();
    String boundary = "😀".repeat(4096);
    assertEquals(
        boundary,
        candidate(publication, VideoTraceEvidence.Kind.VISUAL, frameId, HASH, boundary, "video/mp4")
            .recallText());
    for (String recall : Arrays.asList(null, " \n", "灯".repeat(4097), "\uD800", "\uDFFF")) {
      assertThrows(
          ApplicationException.class,
          () ->
              candidate(
                  publication, VideoTraceEvidence.Kind.VISUAL, frameId, HASH, recall, "video/mp4"));
    }
    for (String mime : Arrays.asList(null, "image/png")) {
      assertThrows(
          ApplicationException.class,
          () -> candidate(publication, VideoTraceEvidence.Kind.VISUAL, frameId, HASH, "蓝灯", mime));
    }
  }

  @Test
  void publishedGroupBindsRevisionCompilerManifestAndBothPhysicalMembers() {
    var source = source();
    var published = source.source();
    var publication = published.publication();
    var group = published.group();
    assertThrows(
        ApplicationException.class, () -> group(null, group, "frame", "span", HASH, "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () -> group(publication, null, "frame", "span", HASH, "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            group(
                publication(publication, "another-revision", publication.parserRevision()),
                group,
                "frame",
                "span",
                HASH,
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            group(
                publication(publication, REVISION, "java-text-parser-v1"),
                group,
                "frame",
                "span",
                HASH,
                "video/mp4"));
    for (String hash : Arrays.asList(null, "unsealed-manifest")) {
      assertThrows(
          ApplicationException.class,
          () -> group(publication, group, "frame", "span", hash, "video/mp4"));
    }
    for (String mime : Arrays.asList(null, "audio/mp4")) {
      assertThrows(
          ApplicationException.class, () -> group(publication, group, "frame", "span", HASH, mime));
    }
    assertThrows(
        ApplicationException.class,
        () -> group(publication, group, null, "span", HASH, "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () -> group(publication, group, "frame", group.transcriptSpanId(), HASH, "video/mp4"));
  }

  @Test
  void singleModalitySourcesRetainExactTimeWithoutInventingTheOtherMember() {
    var pair = source();
    var frame = pair.proofInput().frame();
    String frameId = pair.source().group().frameId();
    String spanId = pair.source().group().transcriptSpanId();
    var frameGroup =
        new VideoEvidenceGroup(
            VideoEvidence.groupIdentity(REVISION, frameId, null),
            REVISION,
            0,
            0,
            200_000,
            frameId,
            null);
    var framePublication =
        group(
            pair.source().publication(),
            frameGroup,
            "physical-frame",
            null,
            pair.source().manifestSha256(),
            "video/mp4");
    var frameInput =
        new VideoProofInput(
            pair.proofInput().sourceSha256(),
            pair.proofInput().manifestSha256(),
            frameGroup,
            frame,
            null);
    var frameSource = new PublishedVideoEvidence(framePublication, frameInput, null);
    assertEquals(null, frameSource.transcriptSpan());
    assertThrows(
        ApplicationException.class,
        () ->
            group(
                pair.source().publication(),
                frameGroup,
                "physical-frame",
                "physical-span",
                HASH,
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(framePublication, frameInput, pair.transcriptSpan()));
    var transcriptGroup =
        new VideoEvidenceGroup(
            VideoEvidence.groupIdentity(REVISION, null, spanId),
            REVISION,
            1,
            0,
            1_000_000,
            null,
            spanId);
    var transcriptPublication =
        group(
            pair.source().publication(),
            transcriptGroup,
            null,
            "physical-span",
            pair.source().manifestSha256(),
            "video/mp4");
    var transcriptInput =
        new VideoProofInput(
            pair.proofInput().sourceSha256(),
            pair.proofInput().manifestSha256(),
            transcriptGroup,
            null,
            pair.proofInput().transcript());
    var transcriptSource =
        new PublishedVideoEvidence(transcriptPublication, transcriptInput, pair.transcriptSpan());
    assertEquals(1_000_000, transcriptSource.source().group().endUs());
    assertThrows(
        ApplicationException.class,
        () ->
            group(
                pair.source().publication(),
                transcriptGroup,
                "invented-frame",
                "physical-span",
                HASH,
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                transcriptSource,
                new VideoTraceEvidence(
                    1, VideoTraceEvidence.Kind.VISUAL, "physical-frame", null, null, 1, 1, HASH)));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                frameSource,
                new VideoTraceEvidence(
                    1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical-span", 0, 1, 1, 1, HASH)));
  }

  @Test
  void selectedTranscriptMustExactlyMatchItsSpanAndRealIntersection() {
    var source = source();
    var original = source.proofInput();
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(null, original, source.transcriptSpan()));
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), null, source.transcriptSpan()));
    var otherGroup =
        new VideoEvidenceGroup(
            original.group().id(),
            REVISION,
            1,
            original.group().startUs(),
            original.group().endUs(),
            original.group().frameId(),
            original.group().transcriptSpanId());
    var otherInput =
        new VideoProofInput(
            original.sourceSha256(),
            original.manifestSha256(),
            otherGroup,
            original.frame(),
            original.transcript());
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), otherInput, source.transcriptSpan()));
    var unindexed =
        group(
            source.source().publication(),
            source.source().group(),
            "physical-frame",
            null,
            source.source().manifestSha256(),
            "video/mp4");
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(unindexed, original, source.transcriptSpan()));
    var missing =
        new VideoProofInput(
            original.sourceSha256(),
            original.manifestSha256(),
            original.group(),
            original.frame(),
            null);
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), missing, source.transcriptSpan()));
    String different = "😀红色圆形。";
    var changed =
        new GroundingText(
            original.group().transcriptSpanId(),
            "video-transcript:" + REVISION,
            different,
            ModelValues.sha256(different.getBytes(StandardCharsets.UTF_8)),
            0,
            different.codePointCount(0, different.length()));
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoEvidence(
                source.source(),
                new VideoProofInput(
                    original.sourceSha256(),
                    original.manifestSha256(),
                    original.group(),
                    original.frame(),
                    changed),
                source.transcriptSpan()));
    var noIntersection =
        new VideoTranscriptEvidence(
            source.transcriptSpan().id(),
            REVISION,
            new AudioTranscriptSpan(0, 500, 1000, original.transcript().snippet()),
            0);
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), original, noIntersection));
    var shorterSpan =
        new VideoTranscriptEvidence(
            source.transcriptSpan().id(),
            REVISION,
            new AudioTranscriptSpan(0, 0, 100, original.transcript().snippet()),
            0);
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), original, shorterSpan));
  }

  @Test
  void retainedBlankSpanSuppliesNoTranscriptCitation() {
    var source = source();
    var original = source.proofInput();
    var silent =
        new VideoTranscriptEvidence(
            source.transcriptSpan().id(), REVISION, new AudioTranscriptSpan(0, 0, 1000, ""), null);
    var published =
        group(
            source.source().publication(),
            source.source().group(),
            "physical-frame",
            null,
            source.source().manifestSha256(),
            "video/mp4");
    var input =
        new VideoProofInput(
            original.sourceSha256(),
            original.manifestSha256(),
            original.group(),
            original.frame(),
            null);
    assertEquals(
        null, new PublishedVideoEvidence(published, input, silent).proofInput().transcript());
    assertThrows(
        ApplicationException.class, () -> new PublishedVideoEvidence(published, original, silent));
  }

  @Test
  void sourceQuoteUsesContextCodePointsButCannotEscapeSelectedSpan() {
    var source = source();
    String prefix = "前文😀\n";
    String context = prefix + source.proofInput().transcript().contextText();
    int start = prefix.codePointCount(0, prefix.length());
    var transcript =
        new GroundingText(
            source.source().group().transcriptSpanId(),
            "video-transcript:" + REVISION,
            context,
            ModelValues.sha256(context.getBytes(StandardCharsets.UTF_8)),
            start,
            context.codePointCount(0, context.length()));
    var input =
        new VideoProofInput(
            source.proofInput().sourceSha256(),
            source.proofInput().manifestSha256(),
            source.proofInput().group(),
            source.proofInput().frame(),
            transcript);
    var shifted = new PublishedVideoEvidence(source.source(), input, source.transcriptSpan());
    var quote =
        new VideoTraceEvidence(
            1,
            VideoTraceEvidence.Kind.TRANSCRIPT,
            "physical-span",
            start + 1,
            start + 3,
            1,
            1,
            HASH);
    assertEquals("蓝色", new VideoSourceEvidence(shifted, quote).quote());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                shifted,
                new VideoTraceEvidence(
                    1,
                    VideoTraceEvidence.Kind.TRANSCRIPT,
                    "physical-span",
                    start - 1,
                    start + 1,
                    1,
                    1,
                    HASH)));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                source,
                new VideoTraceEvidence(
                    1,
                    VideoTraceEvidence.Kind.VISUAL,
                    "different-physical",
                    null,
                    null,
                    1,
                    1,
                    HASH)));
    assertThrows(ApplicationException.class, () -> new VideoSourceEvidence(null, quote));
    assertThrows(ApplicationException.class, () -> new VideoSourceEvidence(source, null));
  }

  private static PublishedVideoCandidate candidate(
      PublicationVersion publication,
      VideoTraceEvidence.Kind kind,
      String sourceId,
      String hash,
      String recall,
      String mime) {
    return new PublishedVideoCandidate(
        publication, kind, "physical", sourceId, hash, recall, "video.mp4", mime);
  }

  private static PublishedVideoGroup group(
      PublicationVersion publication,
      VideoEvidenceGroup group,
      String frame,
      String span,
      String manifest,
      String mime) {
    return new PublishedVideoGroup(publication, group, frame, span, manifest, "video.mp4", mime);
  }

  private static PublicationVersion publication(
      PublicationVersion source, String revision, String parser) {
    return new PublicationVersion(
        source.documentId(),
        source.publicationId(),
        revision,
        source.projectionGenerationId(),
        source.sourceSha256(),
        parser,
        source.target(),
        source.manifestSha256(),
        source.segmentCount());
  }

  @Test
  void pinsSelectedMaterialToSourceRevisionParentAndIndependentCompilationManifest() {
    var source = source();
    assertNotEquals(
        source.source().publication().manifestSha256(), source.source().manifestSha256());
    assertEquals(source.source().publication().sourceSha256(), source.proofInput().sourceSha256());
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoEvidence(
                source.source(),
                new VideoProofInput(
                    HASH,
                    source.proofInput().manifestSha256(),
                    source.proofInput().group(),
                    source.proofInput().frame(),
                    source.proofInput().transcript()),
                source.transcriptSpan()));
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoEvidence(
                source.source(),
                new VideoProofInput(
                    source.proofInput().sourceSha256(),
                    HASH,
                    source.proofInput().group(),
                    source.proofInput().frame(),
                    source.proofInput().transcript()),
                source.transcriptSpan()));
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), source.proofInput(), null));
  }

  @Test
  void requiresExactFrameSpanIntersectionAndNotMerelyContainedArbitraryTime() {
    var source = source();
    var group = source.source().group();
    var shorter =
        new VideoEvidenceGroup(
            group.id(),
            group.revisionId(),
            group.ordinal(),
            1000,
            group.endUs(),
            group.frameId(),
            group.transcriptSpanId());
    var publication =
        new PublishedVideoGroup(
            source.source().publication(),
            shorter,
            "physical-frame",
            "physical-span",
            source.source().manifestSha256(),
            "video.mp4",
            "video/mp4");
    var input =
        new VideoProofInput(
            source.proofInput().sourceSha256(),
            source.proofInput().manifestSha256(),
            shorter,
            source.proofInput().frame(),
            source.proofInput().transcript());
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(publication, input, source.transcriptSpan()));
    var different =
        new VideoTranscriptEvidence(
            VideoEvidence.transcriptIdentity(REVISION, 1),
            REVISION,
            new AudioTranscriptSpan(1, 1000, 2000, "其他内容"),
            1);
    assertThrows(
        ApplicationException.class,
        () -> new PublishedVideoEvidence(source.source(), source.proofInput(), different));
  }

  @Test
  void rejectsCandidatePhysicalSourceIdentityConfusionAndInvalidRecallMetadata() {
    var source = source();
    var group = source.source();
    var candidate =
        new PublishedVideoCandidate(
            group.publication(),
            VideoTraceEvidence.Kind.VISUAL,
            group.framePhysicalSegmentId(),
            group.group().frameId(),
            HASH,
            "蓝色指示灯",
            "video.mp4",
            "video/mp4");
    assertEquals(group.framePhysicalSegmentId(), candidate.physicalSegmentId());
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoCandidate(
                group.publication(),
                VideoTraceEvidence.Kind.VISUAL,
                group.group().frameId(),
                group.group().frameId(),
                HASH,
                "蓝色指示灯",
                "video.mp4",
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoCandidate(
                group.publication(),
                VideoTraceEvidence.Kind.VISUAL,
                "physical",
                group.group().transcriptSpanId(),
                HASH,
                "蓝色指示灯",
                "video.mp4",
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoCandidate(
                group.publication(),
                VideoTraceEvidence.Kind.VISUAL,
                "physical",
                group.group().frameId(),
                HASH,
                "\u0000",
                "video.mp4",
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoGroup(
                group.publication(),
                group.group(),
                group.group().frameId(),
                "physical-span",
                group.manifestSha256(),
                "video.mp4",
                "video/mp4"));
    assertThrows(
        ApplicationException.class,
        () ->
            new PublishedVideoGroup(
                group.publication(),
                group.group(),
                "physical",
                "physical",
                group.manifestSha256(),
                "video.mp4",
                "video/mp4"));
  }

  @Test
  void returnsOriginalCodePointQuoteAndNeverAVisualCharacterLocator() {
    var source = source();
    var transcript =
        new VideoTraceEvidence(
            1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical-span", 1, 3, 0.8, 0.9, HASH);
    assertEquals("蓝色", new VideoSourceEvidence(source, transcript).quote());
    var visual =
        new VideoTraceEvidence(
            1, VideoTraceEvidence.Kind.VISUAL, "physical-frame", null, null, 0.8, 0.9, HASH);
    assertEquals(null, new VideoSourceEvidence(source, visual).quote());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                source,
                new VideoTraceEvidence(
                    1,
                    VideoTraceEvidence.Kind.TRANSCRIPT,
                    source.source().group().transcriptSpanId(),
                    1,
                    3,
                    0.8,
                    0.9,
                    HASH)));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                source,
                new VideoTraceEvidence(
                    1,
                    VideoTraceEvidence.Kind.TRANSCRIPT,
                    "physical-span",
                    1,
                    100,
                    0.8,
                    0.9,
                    HASH)));
    var original = new SourceVideo("video/mp4", VideoCompilationFixture.ORIGINAL);
    assertArrayEquals(
        VideoCompilationFixture.ORIGINAL,
        new VideoSourceEvidence(source, visual, original).video().content());
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(source, visual, new SourceVideo("video/mp4", new byte[] {1})));
    assertThrows(
        ApplicationException.class,
        () ->
            new VideoSourceEvidence(
                source, visual, new SourceVideo("video/webm", VideoCompilationFixture.ORIGINAL)));
  }

  @Test
  void originalVideoSupportsOnlyFourMediaTypesAndCopiesBothByteDirections() {
    for (String type : List.of("video/mp4", "video/quicktime", "video/webm", "video/x-matroska")) {
      byte[] input = {1, 2, 3};
      var video = new SourceVideo(type, input);
      input[0] = 9;
      assertEquals(1, video.content()[0]);
      var output = video.content();
      output[0] = 8;
      assertEquals(1, video.content()[0]);
    }
    assertThrows(ApplicationException.class, () -> new SourceVideo("audio/mp4", new byte[] {1}));
    assertThrows(ApplicationException.class, () -> new SourceVideo(null, new byte[] {1}));
    assertThrows(ApplicationException.class, () -> new SourceVideo("video/mp4", null));
    assertThrows(ApplicationException.class, () -> new SourceVideo("video/mp4", new byte[0]));
    assertThrows(
        ApplicationException.class,
        () -> new SourceVideo("video/mp4", new byte[20 * 1024 * 1024 + 1]));
  }

  private static PublishedVideoEvidence source() {
    String text = "😀蓝色圆形。";
    var compilation =
        new VideoCompilation(
            VideoCompilationFixture.SOURCE,
            "video-decoder-v1",
            VideoCompilationFixture.COMPILER,
            0,
            1_000_000,
            List.of(VideoCompilationFixture.frame(0, 0, 200_000)),
            new AudioTranscription(
                VideoCompilationFixture.SOURCE,
                "video-decoder-v1",
                "asr-v1",
                "transcription-v1",
                16_000,
                List.of(new AudioTranscriptSpan(0, 0, 1000, text))));
    var material = VideoEvidence.fromCompilation(REVISION, compilation);
    var group = material.groups().getFirst();
    var publication =
        new PublicationVersion(
            "document",
            "publication",
            REVISION,
            "generation",
            compilation.sourceSha256(),
            compilation.compilerRevision(),
            new IndexTarget("embedding", "projection", "model", 2),
            HASH,
            2);
    var published =
        new PublishedVideoGroup(
            publication,
            group,
            "physical-frame",
            "physical-span",
            material.manifestSha256(),
            "video.mp4",
            "video/mp4");
    var transcript =
        new GroundingText(
            group.transcriptSpanId(),
            "video-transcript:" + REVISION,
            text,
            ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8)),
            0,
            text.codePointCount(0, text.length()));
    var input =
        new VideoProofInput(
            compilation.sourceSha256(),
            material.manifestSha256(),
            group,
            compilation.frames().getFirst().frame(),
            transcript);
    return new PublishedVideoEvidence(published, input, material.spans().getFirst());
  }
}
