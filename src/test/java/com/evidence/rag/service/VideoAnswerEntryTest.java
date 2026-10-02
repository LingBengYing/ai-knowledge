package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.controller.VideoAnswerController;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.security.web.AuthenticationFilter;
import com.evidence.rag.security.web.RequestAuthenticator;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.VideoCompilationFixture;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class VideoAnswerEntryTest {
  @TempDir Path directory;

  @Test
  void legacyAnswerCompositionCannotAccidentallyInvokeVideoModelsOrSources() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      var command = new AnswerCommand("指示灯的颜色是什么？", DocumentSelection.selected(List.of()));
      assertUnavailable(
          () -> context.answers.answerVideo(context.owner, command, VideoAssessment.Mode.VISUAL));
      assertUnavailable(() -> context.answers.videoSource(context.owner, "trace-1", 1));
      assertUnavailable(() -> context.answers.videoFrame(context.owner, "trace-1", 1));
      assertUnavailable(() -> context.answers.videoContent(context.owner, "trace-1", 1));
      assertTrue(context.models.calls.isEmpty());
      assertTrue(context.projection.calls.isEmpty());
    }
  }

  @Test
  void jointAnswerPublishesTypedFrameAndWholeTranscriptSpanThenRevokesAllSourceReads() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String other = context.publish("selected.txt", "旁支资料。");
      String video = VideoAnswerFixture.publish(context, "");
      var facts = new VideoAnswerFixture.Facts();
      try (var answers = answers(context, facts)) {
        var result =
            answers.answerVideo(context.owner, command(other, video), VideoAssessment.Mode.JOINT);
        assertEquals("answered", result.status(), result.reason());
        assertEquals(2, result.citations().size());
        assertTrue(result.answer().contains("蓝色"));
        assertTrue(result.answer().contains("5秒"));
        assertFalse(result.answer().contains("红"));
        assertFalse(result.answer().contains("99"));
        assertEquals(Set.of(video), context.projection.lastScope.documentRevisions().keySet());
        var visual =
            result.citations().stream()
                .filter(c -> "video_frame".equals(c.kind()))
                .findFirst()
                .orElseThrow();
        var transcript =
            result.citations().stream()
                .filter(c -> "video_transcript".equals(c.kind()))
                .findFirst()
                .orElseThrow();
        assertEquals(visual.groupId(), transcript.groupId());
        assertEquals("machine_vlm", visual.proofOrigin());
        assertEquals("machine_asr", transcript.proofOrigin());
        assertEquals(video, visual.documentId());
        assertEquals(ModelValues.sha256(VideoAnswerFixture.ORIGINAL), visual.sourceSha256());
        assertEquals(visual.sourceSha256(), transcript.sourceSha256());
        assertEquals(0, visual.startUs());
        assertEquals(200_000, visual.endUs());
        assertEquals(new BigDecimal("200.000"), visual.endMs());
        assertEquals("group_interval", visual.timePrecision());
        assertEquals(0, visual.frame().frameUs());
        assertEquals(200_000, visual.frame().durationUs());
        assertEquals(VideoCompilationFixture.image().sha256(), visual.frame().frameSha256());
        assertEquals(2, visual.frame().width());
        assertEquals(2, visual.frame().height());
        assertEquals("decoded_original", visual.frame().origin());
        assertNull(visual.transcript());
        assertNull(transcript.frame());
        assertEquals(0, transcript.transcript().startMs());
        assertEquals(1000, transcript.transcript().endMs());
        assertEquals("重启等待时间是5秒", transcript.transcript().quote());
        assertEquals("machine_asr", transcript.transcript().textOrigin());
        assertEquals("server_chunk", transcript.transcript().timePrecision());
        for (var citation : result.citations()) {
          assertEquals(
              citation,
              answers.videoSource(context.owner, result.answerId(), citation.number()).citation());
          assertArrayEquals(
              VideoAnswerFixture.ORIGINAL,
              answers.videoContent(context.owner, result.answerId(), citation.number()).content());
          assertTrue(citation.sourceUrl().startsWith("/v1/video-sources/" + result.answerId()));
          assertEquals(citation.sourceUrl() + "/content", citation.contentUrl());
        }
        assertArrayEquals(
            VideoCompilationFixture.image().content(),
            answers.videoFrame(context.owner, result.answerId(), visual.number()).content());
        var serialized = JsonMapper.builder().build().valueToTree(result);
        assertEquals(result.answerId(), serialized.path("answer_id").asText());
        assertEquals(
            "machine_vlm",
            serialized.path("citations").get(visual.number() - 1).path("proof_origin").asText());
        assertEquals(
            "machine_asr",
            serialized
                .path("citations")
                .get(transcript.number() - 1)
                .path("proof_origin")
                .asText());
        assertFalse(serialized.toString().contains("physical_segment_id"));
        assertFalse(serialized.toString().contains("start_code_point"));
        assertFalse(serialized.toString().contains("\"page\""));
        assertFalse(result.toString().contains("5秒"));
        context.revoke(other);
        assertNotFound(() -> answers.videoSource(context.owner, result.answerId(), 1));
        assertNotFound(() -> answers.videoFrame(context.owner, result.answerId(), 1));
        assertNotFound(() -> answers.videoContent(context.owner, result.answerId(), 1));
      }
    }
  }

  @Test
  void nonCandidateScopeChangeDuringProofRefusesThroughTheSharedFinalReceipt() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String other = context.publish("selected.txt", "旁支资料。");
      String video = VideoAnswerFixture.publish(context, "");
      var facts = new VideoAnswerFixture.Facts();
      facts.afterDraft = () -> context.revoke(other);
      try (var answers = answers(context, facts)) {
        var result =
            answers.answerVideo(context.owner, command(other, video), VideoAssessment.Mode.JOINT);
        assertEquals("abstained", result.status());
        assertEquals("scope_changed", result.reason());
        assertTrue(result.citations().isEmpty());
        assertNotFound(() -> answers.videoContent(context.owner, result.answerId(), 1));
      }
    }
  }

  @Test
  void videoModelChangeStopsVideoButDoesNotDisableOrdinaryTextAnswers() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String text = context.publish("manual.txt", "重启等待时间是5秒。");
      String video = VideoAnswerFixture.publish(context, "");
      var facts = new VideoAnswerFixture.Facts();
      facts.afterDraft = () -> facts.revision = "fixture-fact-v2";
      try (var answers = answers(context, facts)) {
        var result = answers.answerVideo(context.owner, command(video), VideoAssessment.Mode.JOINT);
        assertEquals("abstained", result.status());
        assertEquals("configuration_changed", result.reason());
        assertTrue(result.citations().isEmpty());
        var plain =
            answers.answer(
                context.owner,
                new AnswerCommand("重启等待时间是多少？", DocumentSelection.selected(List.of(text))));
        assertEquals("answered", plain.status(), plain.reason());
      }
    }
  }

  @Test
  void videoAndTextShareTheSameAdmissionPermit() throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String video = VideoAnswerFixture.publish(context, "");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var facts = new VideoAnswerFixture.Facts();
      facts.afterDraft =
          () -> {
            entered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Video fixture was not released");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError(interrupted);
            }
          };
      try (var answers = answers(context, facts)) {
        var request =
            CompletableFuture.supplyAsync(
                () ->
                    answers.answerVideo(context.owner, command(video), VideoAssessment.Mode.JOINT));
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          var refused =
              assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          context.owner,
                          new AnswerCommand("重启等待时间是多少？", DocumentSelection.selected(List.of()))));
          assertEquals("answer_capacity_exceeded", refused.code());
        } finally {
          release.countDown();
        }
        assertEquals("answered", request.get(5, TimeUnit.SECONDS).status());
      }
    }
  }

  @Test
  void authenticatedVisualRequestReturnsSealedFrameAndOriginalBytesThroughTheController()
      throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String video = VideoAnswerFixture.publish(context, "");
      try (var answers = answers(context, new VideoAnswerFixture.Facts())) {
        var controller = new VideoAnswerController(answers);
        var answer =
            authenticated(
                context.owner,
                "POST",
                "/v1/video-answers",
                request ->
                    controller.answer(
                        request,
                        Map.of(
                            "question",
                            "指示灯的颜色是什么？",
                            "mode",
                            "visual",
                            "document_ids",
                            List.of(video))));
        assertEquals("answered", answer.status(), answer.reason());
        assertEquals(1, answer.citations().size());
        var citation = answer.citations().getFirst();
        assertEquals("video_frame", citation.kind());
        var source =
            authenticated(
                context.owner,
                "GET",
                citation.sourceUrl(),
                request -> controller.source(request, answer.answerId(), 1));
        assertEquals(citation, source.citation());
        var frame =
            authenticated(
                context.owner,
                "GET",
                citation.frame().contentUrl(),
                request -> controller.frame(request, answer.answerId(), 1));
        assertEquals(200, frame.getStatusCode().value());
        assertEquals("image/png", frame.getHeaders().getContentType().toString());
        assertEquals("no-store", frame.getHeaders().getCacheControl());
        assertEquals("nosniff", frame.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(VideoCompilationFixture.image().content(), frame.getBody());
        var original =
            authenticated(
                context.owner,
                "GET",
                citation.contentUrl(),
                request -> controller.content(request, answer.answerId(), 1));
        assertEquals(200, original.getStatusCode().value());
        assertEquals("video/mp4", original.getHeaders().getContentType().toString());
        assertArrayEquals(VideoAnswerFixture.ORIGINAL, original.getBody());
        var selected =
            authenticated(
                context.owner,
                "GET",
                citation.contentUrl(),
                request -> {
                  request.addHeader("Range", "bytes=4-7");
                  return controller.content(request, answer.answerId(), 1);
                });
        assertEquals(206, selected.getStatusCode().value());
        assertArrayEquals(new byte[] {'f', 't', 'y', 'p'}, selected.getBody());
        assertEquals("bytes 4-7/24", selected.getHeaders().getFirst("Content-Range"));
      }
    }
  }

  @Test
  void isolatedTranscriptTailAnswersWithoutInventingAFrame() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String video = VideoAnswerFixture.publish(context, VideoAnswerFixture.TRANSCRIPT);
      var selection = DocumentSelection.selected(List.of(video));
      var scope = context.evidence.snapshot(context.owner, selection, context.target);
      context.projection.results =
          candidates -> {
            var group =
                context
                    .evidence
                    .videoGroups(
                        scope, candidates.stream().map(candidate -> candidate.segmentId()).toList())
                    .stream()
                    .filter(candidate -> candidate.group().frameId() == null)
                    .findFirst()
                    .orElseThrow();
            return candidates.stream()
                .filter(
                    candidate -> candidate.segmentId().equals(group.transcriptPhysicalSegmentId()))
                .toList();
          };
      var facts = new VideoAnswerFixture.Facts();
      facts.afterDraft =
          () -> {
            throw new AssertionError("Transcript mode must not ask a frame model");
          };
      try (var answers = answers(context, facts)) {
        var answer =
            answers.answerVideo(
                context.owner,
                new AnswerCommand("重启等待时间是多少？", selection),
                VideoAssessment.Mode.TRANSCRIPT);
        assertEquals("answered", answer.status(), answer.reason());
        assertEquals(1, answer.citations().size());
        var citation = answer.citations().getFirst();
        assertEquals("video_transcript", citation.kind());
        assertEquals("machine_asr", citation.proofOrigin());
        assertNull(citation.frame());
        assertEquals(1_000_000, citation.startUs());
        assertEquals(2_000_000, citation.endUs());
        assertEquals(1000, citation.transcript().startMs());
        assertEquals(2000, citation.transcript().endMs());
        assertEquals("重启等待时间是5秒", citation.transcript().quote());
        assertEquals(citation, answers.videoSource(context.owner, answer.answerId(), 1).citation());
        assertArrayEquals(
            VideoAnswerFixture.ORIGINAL,
            answers.videoContent(context.owner, answer.answerId(), 1).content());
        assertNotFound(() -> answers.videoFrame(context.owner, answer.answerId(), 1));
      }
    }
  }

  @Test
  void videoRequiresAnExplicitModeAndAClosedServiceRejectsPreviouslyValidSources() {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String video = VideoAnswerFixture.publish(context, "");
      try (var answers = answers(context, new VideoAnswerFixture.Facts())) {
        assertEquals(
            "invalid_request",
            assertThrows(
                    ApplicationException.class,
                    () -> answers.answerVideo(context.owner, command(video), null))
                .code());
        assertTrue(context.models.calls.isEmpty());
        assertTrue(context.projection.calls.isEmpty());
        var answer = answers.answerVideo(context.owner, command(video), VideoAssessment.Mode.JOINT);
        assertEquals("answered", answer.status(), answer.reason());
        answers.close();
        int modelCalls = context.models.calls.size();
        int projectionCalls = context.projection.calls.size();
        assertUnavailable(
            () -> answers.answerVideo(context.owner, command(video), VideoAssessment.Mode.JOINT));
        assertUnavailable(() -> answers.videoSource(context.owner, answer.answerId(), 1));
        assertUnavailable(() -> answers.videoFrame(context.owner, answer.answerId(), 1));
        assertUnavailable(() -> answers.videoContent(context.owner, answer.answerId(), 1));
        assertEquals(modelCalls, context.models.calls.size());
        assertEquals(projectionCalls, context.projection.calls.size());
      }
    }
  }

  @Test
  void sharedRequestBudgetInterruptsVideoProofWithoutReleasingBusyAdmissionEarly()
      throws Exception {
    try (var context = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String video = VideoAnswerFixture.publish(context, "");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var facts = new VideoAnswerFixture.Facts();
      facts.afterDraft =
          () -> {
            entered.countDown();
            boolean interrupted = false;
            while (release.getCount() != 0) {
              try {
                release.await();
              } catch (InterruptedException expected) {
                interrupted = true;
              }
            }
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          };
      try (var answers =
          new AnswerService(
              context.evidence,
              context.models,
              context.projection,
              context.target,
              Duration.ofSeconds(1),
              1,
              VideoAnswerFixture.proposals(context, facts))) {
        var pending =
            CompletableFuture.supplyAsync(
                () ->
                    assertThrows(
                        ApplicationException.class,
                        () ->
                            answers.answerVideo(
                                context.owner, command(video), VideoAssessment.Mode.JOINT)));
        try {
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          assertEquals("answer_timeout", pending.get(3, TimeUnit.SECONDS).code());
          assertEquals(
              "answer_capacity_exceeded",
              assertThrows(
                      ApplicationException.class,
                      () ->
                          answers.answer(
                              context.owner,
                              new AnswerCommand(
                                  "重启等待时间是多少？", DocumentSelection.selected(List.of()))))
                  .code());
          assertEquals(
              1, facts.contexts.size(), "No second proof call may start after the shared timeout");
        } finally {
          release.countDown();
        }
      }
      assertEquals(1, facts.contexts.size());
    }
  }

  private static <T> T authenticated(
      Actor actor, String method, String path, Function<MockHttpServletRequest, T> action)
      throws Exception {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    request.setServerName("127.0.0.1");
    request.addHeader("X-Workspace-Id", actor.workspaceId());
    request.addHeader("X-Principal-Id", actor.principalId());
    var result = new AtomicReference<T>();
    var filter =
        new AuthenticationFilter(
            new RequestAuthenticator("development_headers", actor.workspaceId(), null),
            (req, response, handler, failure) -> {
              throw new AssertionError("Synthetic identity failed", failure);
            });
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, response) -> result.set(action.apply(request)));
    return result.get();
  }

  private static AnswerService answers(AnswerTestContext context, VideoAnswerFixture.Facts facts) {
    return new AnswerService(
        context.evidence,
        context.models,
        context.projection,
        context.target,
        Duration.ofSeconds(10),
        1,
        VideoAnswerFixture.proposals(context, facts));
  }

  private static AnswerCommand command(String... documents) {
    return new AnswerCommand(
        VideoAnswerFixture.QUESTION, DocumentSelection.selected(List.of(documents)));
  }

  private static void assertNotFound(Runnable request) {
    assertEquals("not_found", assertThrows(ApplicationException.class, request::run).code());
  }

  private static void assertUnavailable(Runnable request) {
    assertEquals(
        "answers_unavailable", assertThrows(ApplicationException.class, request::run).code());
  }
}
