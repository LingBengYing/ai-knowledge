package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.VideoAvModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvWindow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAvQueryBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "short", "null-element", "nonfinite", "float-overflow", "zero"})
  void invalidOriginalQuestionVectorNeverDispatchesAnyProjection(String invalid) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      fixture.vector =
          switch (invalid) {
            case "missing" -> null;
            case "short" -> List.of(1.0);
            case "null-element" -> Arrays.asList(1.0, null);
            case "nonfinite" -> List.of(Double.NaN, 1.0);
            case "float-overflow" -> List.of(Double.MAX_VALUE, 1.0);
            default -> List.of(0.0, -0.0);
          };
      assertEquals(
          "upstream_invalid",
          answers
              .answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT))
              .reasonCode());
      assertTrue(fixture.queries.isEmpty());
      assertEquals(0, fixture.decodes);
      assertTrue(fixture.drafts.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "too-many", "null-element"})
  void invalidWholeCandidatePageCannotBeTruncatedToValidHead(String invalid) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      var id = fixture.publications.getFirst().windows().getFirst().visualPhysicalId();
      fixture.search =
          (route, query) ->
              switch (invalid) {
                case "missing" -> null;
                case "too-many" ->
                    IntStream.range(0, 65)
                        .mapToObj(
                            i ->
                                new RetrievalProjection.Candidate(
                                    i == 0 ? id : "foreign-" + i, 1.0))
                        .toList();
                default -> Arrays.asList(new RetrievalProjection.Candidate(id, 1.0), null);
              };
      assertEquals(
          "upstream_invalid",
          answers
              .answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.VISUAL))
              .reasonCode());
      assertEquals(0, fixture.decodes);
      assertTrue(fixture.drafts.isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"uncited-clip", "uncited-pcm", "epoch"})
  void wholeCompiledGroupMustMatchBeforeCandidateProofEvenForUncitedWindow(String changed) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      var original = fixture.register("library", 2, 2, 32000, true);
      var publication = fixture.publications.getFirst();
      var head = publication.windows().getFirst();
      fixture.search =
          (route, query) ->
              List.of(
                  new RetrievalProjection.Candidate(
                      route == VideoAvRoute.VISUAL
                          ? head.visualPhysicalId()
                          : head.audioPhysicalId(),
                      1.0));
      var compiled = fixture.compilations.get(original.sourceSha256());
      var windows = new ArrayList<>(compiled.windows());
      var last = windows.getLast();
      var clip = last.video();
      var audio = last.audio();
      var epoch = compiled.epoch();
      if (changed.equals("uncited-clip")) {
        byte[] content = clip.content();
        content[0] ^= 1;
        clip =
            new VideoAvClip(
                content,
                ModelValues.sha256(content),
                clip.firstLocalTick(),
                clip.endLocalTick(),
                clip.frames(),
                clip.framesManifestSha256());
      } else if (changed.equals("uncited-pcm")) {
        byte[] pcm = audio.pcm();
        pcm[pcm.length - 1] ^= 1;
        audio =
            new AudioWaveform(
                audio.sourceSha256(),
                audio.decoderRevision(),
                audio.startSample(),
                audio.endSample(),
                pcm);
      } else {
        epoch =
            new VideoAvEpoch(
                epoch.sourceFirstPts() + 1,
                epoch.sourceTimeBaseNumerator(),
                epoch.sourceTimeBaseDenominator(),
                epoch.ticksPerSecond());
      }
      windows.set(
          windows.size() - 1,
          new VideoAvWindow(
              last.id(), last.ordinal(), last.startTick(), last.endTick(), clip, audio));
      fixture.compilations.put(
          original.sourceSha256(),
          new VideoAvCompilation(
              compiled.sourceSha256(),
              compiled.decoderRevision(),
              epoch,
              compiled.durationTick(),
              compiled.hasAudio(),
              windows));
      assertEquals(
          "source_changed",
          answers
              .answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.JOINT))
              .reasonCode());
      assertEquals(1, fixture.decodes);
      assertTrue(fixture.drafts.isEmpty());
    }
  }

  @Test
  void withdrawalInsideSearchRejectsBeforeAnyDecodeAndLeavesNoTrace() {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("cited", 1, 1, 16000, true);
      fixture.register("uncited", 1, 1, 0, true);
      var id = fixture.publications.getFirst().windows().getFirst().visualPhysicalId();
      fixture.search =
          (route, query) -> {
            fixture.sql(
                "INSERT INTO document_tombstones SELECT id,workspace_id,'owner','2026-10-08T00:00:00Z' FROM documents WHERE id='uncited'");
            return List.of(new RetrievalProjection.Candidate(id, 1.0));
          };
      assertEquals(
          "scope_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      answers.answer(
                          VideoAvTestFixture.OWNER,
                          VideoAvAnswerServiceTest.all(VideoAvMode.VISUAL)))
              .code());
      assertEquals(0, fixture.decodes);
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_traces"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"null-draft", "null-verify", "extra-support", "duplicate-support", "wrong-mode"})
  void malformedWholeFactProtocolCannotProduceCitation(String invalid) {
    try (var fixture = new VideoAvTestFixture(directory);
        var answers = fixture.answers()) {
      fixture.register("library", 1, 1, 16000, true);
      if (invalid.equals("null-draft")) {
        fixture.draft = (window, mode) -> null;
      } else if (invalid.equals("wrong-mode")) {
        fixture.draft =
            (window, mode) ->
                new VideoAvModels.Draft(
                    true, List.of(new VideoAvModels.Claim("有响声。", VideoAvRequirement.AUDIO)));
      } else if (invalid.equals("null-verify")) {
        fixture.verify = facts -> null;
      } else if (invalid.equals("extra-support")) {
        fixture.verify =
            facts ->
                new VideoAvModels.Verification(
                    true,
                    List.of(
                        new VideoAvModels.Support(facts.getFirst().id(), true, true, false),
                        new VideoAvModels.Support("c".repeat(64), true, true, false)));
      } else {
        fixture.draft =
            (window, mode) ->
                new VideoAvModels.Draft(
                    true,
                    List.of(
                        new VideoAvModels.Claim("有敲击动作。", VideoAvRequirement.VISUAL),
                        new VideoAvModels.Claim("灯光为红色。", VideoAvRequirement.VISUAL)));
        fixture.verify =
            facts ->
                new VideoAvModels.Verification(
                    true,
                    List.of(
                        new VideoAvModels.Support(facts.getFirst().id(), true, true, false),
                        new VideoAvModels.Support(facts.getFirst().id(), true, true, false)));
      }
      assertEquals(
          "upstream_invalid",
          answers
              .answer(VideoAvTestFixture.OWNER, VideoAvAnswerServiceTest.all(VideoAvMode.VISUAL))
              .reasonCode());
      assertEquals(0, fixture.count("SELECT count(*) FROM video_av_trace_evidence"));
    }
  }
}
