package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AnswerEligibility;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VideoAnswerProposalServiceTest {
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(VideoAssessment.Mode.class)
  void allThreeModesUseActualPublicationIdentitiesAndDurableTypedSources(
      VideoAssessment.Mode mode) {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String other = context.publish("other.txt", "旁支资料。");
      String video = VideoAnswerFixture.publish(context, "");
      var facts = new VideoAnswerFixture.Facts();
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.selected(List.of(other, video)), context.target);
      String question =
          switch (mode) {
            case VISUAL -> "指示灯的颜色是什么？";
            case TRANSCRIPT -> "重启等待时间是多少？";
            case JOINT -> VideoAnswerFixture.QUESTION;
          };
      var result =
          VideoAnswerFixture.proposals(context, facts)
              .propose(scope, question, mode, () -> context.evidence.hydrate(scope, List.of()));
      assertEquals("answered", result.trace().outcome(), result.trace().reasonCode());
      assertEquals(mode, result.trace().videoProof().mode());
      assertEquals(Set.of(video), context.projection.lastScope.documentRevisions().keySet());
      assertFalse(result.answer().contains("红"));
      assertFalse(result.answer().contains("99"));
      assertTrue(facts.contexts.stream().allMatch(question::equals));
      assertTrue(
          result.sources().stream()
              .allMatch(s -> !s.trace().physicalSegmentId().startsWith("video-")));
      var receipt =
          context.evidence.finish(scope, result.trace(), () -> AnswerEligibility.ELIGIBLE);
      assertEquals("answered", receipt.outcome());
      assertEquals(2, context.scalar("SELECT COUNT(*) FROM query_trace_documents"));
      for (var source : result.sources()) {
        var saved =
            context.evidence.videoSource(
                context.owner, receipt.traceId(), source.trace().citationOrdinal());
        assertEquals(source.source().source(), saved.source().source());
        assertEquals(source.trace(), saved.trace());
        assertArrayEquals(VideoAnswerFixture.ORIGINAL, saved.video().content());
      }
      context.revoke(other);
      assertThrows(
          ApplicationException.class,
          () -> context.evidence.videoSource(context.owner, receipt.traceId(), 1));
    }
  }

  @Test
  void unselectedTranscriptTailCannotHideConflictingVisualEvidence() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      VideoAnswerFixture.publish(context, "指示灯的颜色是红色。");
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.allDocuments(), context.target);
      var result =
          VideoAnswerFixture.proposals(context, new VideoAnswerFixture.Facts())
              .propose(
                  scope,
                  VideoAnswerFixture.QUESTION,
                  VideoAssessment.Mode.JOINT,
                  () -> context.evidence.hydrate(scope, List.of()));
      assertEquals("abstained", result.trace().outcome());
      assertEquals("conflicting_evidence", result.trace().reasonCode());
      assertTrue(result.sources().isEmpty());
      assertTrue(result.trace().videoEvidence().isEmpty());
    }
  }

  @Test
  void revokingNonCandidateSelectedDocumentStopsProofBeforeRelease() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String other = context.publish("other.txt", "旁支资料。");
      String video = VideoAnswerFixture.publish(context, "");
      var scope =
          context.evidence.snapshot(
              context.owner, DocumentSelection.selected(List.of(other, video)), context.target);
      var facts = new VideoAnswerFixture.Facts();
      facts.afterDraft = () -> context.revoke(other);
      var error =
          assertThrows(
              ApplicationException.class,
              () ->
                  VideoAnswerFixture.proposals(context, facts)
                      .propose(
                          scope,
                          VideoAnswerFixture.QUESTION,
                          VideoAssessment.Mode.JOINT,
                          () -> context.evidence.hydrate(scope, List.of())));
      assertEquals("scope_changed", error.code());
    }
  }
}
