package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.dto.VideoAnswerCommand;
import com.evidence.rag.model.entity.TraceVideoSubtitleCitationEntity;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import com.evidence.rag.web.converter.VideoAnswerRequestMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoSubtitleQueryDomainTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void explicitSubtitleModeIsIndependentOfOcrAndAudiovisualProof() {
    var command =
        VideoAnswerRequestMapper.command(Map.of("question", "预算是多少？", "mode", "subtitle"));
    assertTrue(command.subtitle());
    assertFalse(command.ocr());
    assertNull(command.mode());
    assertThrows(
        ApplicationException.class,
        () -> new VideoAnswerCommand(command.answer(), null, true, true));
    assertThrows(
        ApplicationException.class,
        () -> new VideoAnswerCommand(command.answer(), VideoAssessment.Mode.JOINT, false, true));
    assertThrows(
        ApplicationException.class,
        () -> new VideoAnswerCommand(command.answer(), null, false, false));
  }

  @Test
  void subtitleTraceClaimsItsOwnCompleteOrdinalUnionWithoutVideoProof() {
    var entries = new ArrayList<>(List.of(trace(1, "physical", 1, 9)));
    var draft = draft(List.of(), List.of(), entries);
    entries.clear();
    assertEquals(1, draft.videoSubtitleEvidence().size());
    assertTrue(draft.videoEvidence().isEmpty());
    assertTrue(draft.videoOcrEvidence().isEmpty());
    assertNull(draft.videoProof());
    assertEquals(
        2,
        draft(List.of(trace(1, "text", 0, 1)), List.of(), List.of(trace(2, "subtitle", 1, 9)))
            .videoSubtitleEvidence()
            .getFirst()
            .citationOrdinal());
    assertThrows(
        ApplicationException.class,
        () ->
            draft(List.of(trace(1, "text", 0, 1)), List.of(), List.of(trace(1, "subtitle", 1, 9))));
    assertThrows(
        ApplicationException.class,
        () ->
            draft(List.of(), List.of(trace(1, "ocr", 0, 1)), List.of(trace(1, "subtitle", 1, 9))));
    assertThrows(
        ApplicationException.class,
        () -> draft(List.of(), List.of(), List.of(trace(2, "subtitle", 1, 9))));
    assertThrows(ApplicationException.class, () -> draft(List.of(), List.of(), null));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceDraft(
                HASH,
                null,
                "abstained",
                "no_evidence",
                "model",
                "prompt",
                "policy",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(trace(1, "subtitle", 1, 9))));
  }

  @Test
  void cueGroundingKeepsTheCompleteSameTrackIncludingUnselectedTailAndExactCodePoints() {
    var source = source();
    assertEquals("预算😀42万元。", source.grounding().snippet());
    assertEquals(source.track().text(), source.grounding().contextText());
    assertTrue(source.grounding().contextText().contains("须经审批"));
    assertFalse(source.grounding().contextText().contains("English only"));
    assertEquals(source.trackTextSha256(), source.grounding().contextSha256());
    var excerpt = new VideoSubtitleSourceEvidence(source, trace(1, "physical", 1, 9), null);
    assertEquals("预算😀42万元。", excerpt.quote());
    assertEquals(500000L, source.source().startUs());
    assertEquals(1500000L, source.source().endUs());
    assertFalse(source.toString().contains("预算"));
    assertFalse(excerpt.toString().contains("预算"));
  }

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

  @Test
  void excerptAndHashOnlyTraceCannotForgeCueOrCrossItsTrackCoordinates() {
    var source = source();
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleSourceEvidence(null, trace(1, "physical", 1, 9), null));
    assertThrows(
        ApplicationException.class, () -> new VideoSubtitleSourceEvidence(source, null, null));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleSourceEvidence(source, trace(1, "another", 1, 9), null));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleSourceEvidence(source, trace(1, "physical", 0, 1), null));
    assertThrows(
        ApplicationException.class,
        () -> new VideoSubtitleSourceEvidence(source, trace(1, "physical", 1, 10), null));
    var saved =
        new TraceVideoSubtitleCitationEntity(
            trace(1, "physical", 1, 9),
            "publication",
            source.source().id(),
            source.track().id(),
            HASH,
            HASH,
            HASH,
            HASH,
            HASH,
            HASH);
    assertFalse(saved.toString().contains("physical"));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoSubtitleCitationEntity(
                null,
                "publication",
                source.source().id(),
                source.track().id(),
                HASH,
                HASH,
                HASH,
                HASH,
                HASH,
                HASH));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoSubtitleCitationEntity(
                trace(1, "physical", 1, 9),
                "publication",
                "fake",
                source.track().id(),
                HASH,
                HASH,
                HASH,
                HASH,
                HASH,
                HASH));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoSubtitleCitationEntity(
                trace(1, "physical", 1, 9),
                "publication",
                source.source().id(),
                "fake",
                HASH,
                HASH,
                HASH,
                HASH,
                HASH,
                HASH));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoSubtitleCitationEntity(
                trace(1, "physical", 1, 9),
                "publication",
                source.source().id(),
                source.track().id(),
                HASH,
                HASH,
                HASH,
                HASH,
                HASH,
                "bad"));
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

  private static TraceEvidence trace(int ordinal, String id, int start, int end) {
    return new TraceEvidence(ordinal, id, start, end, 1, 1, List.of(HASH));
  }

  private static TraceDraft draft(
      List<TraceEvidence> text, List<TraceEvidence> ocr, List<TraceEvidence> subtitle) {
    return new TraceDraft(
        HASH,
        HASH,
        "answered",
        null,
        "models",
        "prompt",
        "policy",
        text,
        List.of(),
        List.of(),
        List.of(),
        null,
        ocr,
        subtitle);
  }
}
