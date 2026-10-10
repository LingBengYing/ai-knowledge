package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.KnowledgeAnswerResult;
import com.evidence.rag.model.dto.KnowledgeCitation;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.DocumentWithdrawal;
import com.evidence.rag.support.VideoCompilationFixture;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public answer/source behavior with real SQLite and only external model/vector Adapters. */
class KnowledgeAnswerServiceTest {
  private static final String QUESTION = "青榆X1如何开启夜间模式？";
  private static final String TITLE = "一 青榆 X1 . 桌面净化器";
  private static final String OCR = TITLE + "\n01 / 开机\n短按电源开机\n合成演示 · 虚构产品";
  private static final String SPOKEN = "短按电源开机，长按月亮键3秒开启夜间模式，月亮指示灯变绿表示开启。";
  private static final String MANUAL =
      "适用产品：青榆X1桌面净化器。\n01 开机\n短按电源开机。\n02 开启夜间模式\n" + "长按月亮键3秒，开启夜间模式。\n03 确认开启\n月亮指示灯变绿，表示开启。";
  private static final String UNRELATED = "这是同一完整所选范围中的保养说明，不描述夜间模式操作。";
  private static final String COMPILER = "java-video-compiler-v4:" + "e".repeat(64);
  private static final String VIDEO_SHA = ModelValues.sha256(VideoAnswerFixture.ORIGINAL);
  private static final Duration DEADLINE = Duration.ofSeconds(10);
  @TempDir Path directory;

  @Test
  void defaultTopKLimitsActualSynthesisEvidenceAcrossTheFullWorkspace() {
    try (var fixture = new Fixture(directory)) {
      for (int i = 0; i < 13; i++) {
        fixture.context.publish("synthetic-" + i + ".txt", "合成灯塔材料编号：" + i);
      }
      var answer =
          fixture.answers.answer(
              fixture.context.owner, new AnswerCommand("灯塔", DocumentSelection.allDocuments()));
      assertEquals("answered", answer.status(), answer.reason());
      assertEquals(
          5,
          fixture.models.contextOnly.size(),
          "Final TopK must limit evidence passed to synthesis, not only displayed matches");
    }
  }

  @Test
  void configuredThresholdRemovesTwelveLowScoreCandidatesBeforeSynthesis() {
    try (var fixture = new Fixture(directory)) {
      fixture.settings.set(new RetrievalSettings(4, "hybrid", "rerank", 0.5, 20, true, 0.5));
      fixture.context.publish("relevant.txt", MANUAL);
      for (int i = 0; i < 12; i++) {
        fixture.context.publish("noise-" + i + ".txt", "无关合成部署启动码" + i);
      }
      fixture.models.ranking =
          texts ->
              IntStream.range(0, texts.size())
                  .mapToObj(
                      i -> new TextModels.Ranked(i, texts.get(i).equals(MANUAL) ? 0.5 : 0.000001))
                  .toList();
      var answer = fixture.answers.answer(fixture.context.owner, selected());
      assertEquals("answered", answer.status(), answer.reason());
      assertEquals(
          List.of(MANUAL), fixture.models.contextOnly.stream().map(Context::context).toList());
      assertEquals(1, answer.citations().size());
      assertEquals(
          1,
          fixture.context.scalar(
              "SELECT COUNT(*) FROM knowledge_answer_traces "
                  + "WHERE policy_revision LIKE '%:retrieval-v4:"
                  + fixture.settings.get().fingerprint()
                  + "'"));
    }
  }

  @Test
  void globalTopKCombinesDocumentAndVideoRanksAndDeduplicatesExactOriginals() {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", MANUAL);
      fixture.context.publish("duplicate.txt", MANUAL);
      var video = fixture.publishVideo();
      fixture.settings.set(new RetrievalSettings(1, "hybrid", "rerank", 0.5, 2, false, 0.5));
      fixture.models.ranking =
          texts ->
              IntStream.range(0, texts.size())
                  .mapToObj(
                      i ->
                          new TextModels.Ranked(
                              i,
                              texts.get(i).equals(MANUAL)
                                  ? 0.99
                                  : texts.get(i).equals(SPOKEN) ? 0.9 : 0.8))
                  .toList();
      var answer = fixture.answers.answer(fixture.context.owner, selected(video.documentId()));
      assertEquals("answered", answer.status(), answer.reason());
      assertEquals(
          List.of(MANUAL, SPOKEN),
          fixture.models.contextOnly.stream().map(Context::context).toList());
      assertEquals(
          Set.of("document_text", "video_transcript"),
          answer.citations().stream()
              .map(KnowledgeCitation::evidenceKind)
              .collect(java.util.stream.Collectors.toSet()));
      assertSources(fixture, answer);
    }
  }

  @Test
  void rejectedSamePageChunkCannotReenterThroughFullContext() {
    try (var fixture = new Fixture(directory)) {
      String selected = "灯塔相关说明。" + "甲".repeat(1193);
      fixture.context.publish("same-page.txt", selected + "尾部部署启动码-NOISE");
      fixture.settings.set(new RetrievalSettings(1, "hybrid", "rerank", 0.5, 1, false, 0.5));
      fixture.models.ranking =
          texts ->
              IntStream.range(0, texts.size())
                  .mapToObj(
                      i -> new TextModels.Ranked(i, texts.get(i).startsWith("灯塔") ? 1.0 : 0.01))
                  .toList();
      var answer = fixture.answers.answer(fixture.context.owner, selected());
      assertEquals("answered", answer.status(), answer.reason());
      assertEquals(1, fixture.models.contextOnly.size());
      assertTrue(!fixture.models.contextOnly.getFirst().context().contains("NOISE"));
      assertEquals(selected, answer.citations().getFirst().quote());
      assertSources(fixture, answer);
    }
  }

  @Test
  void thresholdEmptySkipsGenerationAndMidQuerySettingsChangeAffectsOnlyNextRequest() {
    try (var fixture = new Fixture(directory)) {
      fixture.context.publish("manual.txt", MANUAL);
      var initial = new RetrievalSettings(1, "hybrid", "rerank", 0.5, 1, true, 0.5);
      fixture.settings.set(initial);
      fixture.models.onRerank =
          () ->
              fixture.settings.set(new RetrievalSettings(2, "hybrid", "rerank", 0.5, 1, true, 2.0));
      var first = fixture.answers.answer(fixture.context.owner, selected());
      assertEquals("answered", first.status());
      assertEquals(1, fixture.models.generations);
      var second = fixture.answers.answer(fixture.context.owner, selected());
      assertEquals("no_evidence", second.reason());
      assertEquals(
          1, fixture.models.generations, "No generation after threshold removes all sources");
      assertEquals(
          1,
          fixture.context.scalar(
              "SELECT COUNT(*) FROM knowledge_answer_traces "
                  + "WHERE policy_revision LIKE '%:retrieval-v1:"
                  + initial.fingerprint()
                  + "'"));
    }
  }

  @Test
  void manualAndSameVideoProcedureReopenOriginalVersionsAndTimesAfterRestart() {
    KnowledgeAnswerResult answer;
    String extra;
    try (var fixture = new Fixture(directory)) {
      String manual = fixture.context.publish("synthetic-manual.txt", MANUAL);
      var video = fixture.publishVideo();
      extra = fixture.context.publish("synthetic-maintenance.txt", UNRELATED);
      answer =
          fixture.answers.answer(
              fixture.context.owner, selected(manual, video.documentId(), extra));

      assertEquals("answered", answer.status(), answer.reason());
      assertNull(answer.reason());
      assertEquals(3, answer.citations().size());
      assertEquals(
          Set.of("document_text", "video_frame_ocr", "video_transcript"),
          answer.citations().stream()
              .map(KnowledgeCitation::evidenceKind)
              .collect(java.util.stream.Collectors.toSet()));
      var document = citation(answer, "document_text");
      assertEquals(manual, document.documentId());
      assertEquals(1, document.page());
      assertTrue(document.quote().contains("适用产品：青榆X1桌面净化器。"));
      assertTrue(document.quote().contains("长按月亮键3秒，开启夜间模式。"));
      assertTrue(document.quote().contains("月亮指示灯变绿，表示开启。"));
      assertNull(document.startMs());
      var identity = citation(answer, "video_frame_ocr");
      var procedure = citation(answer, "video_transcript");
      assertEquals(OCR, identity.quote());
      assertEquals(SPOKEN, procedure.quote());
      for (var source : List.of(identity, procedure)) {
        assertEquals(video.documentId(), source.documentId());
        assertEquals(video.revisionId(), source.revisionId());
        assertEquals(VIDEO_SHA, source.sourceSha256());
        assertEquals(new BigDecimal("0.000"), source.startMs());
        assertNull(source.page());
        assertEquals(
            "/v1/documents/" + video.documentId() + "/revisions/" + video.revisionId() + "/content",
            source.contentUrl());
      }
      assertEquals(new BigDecimal("40.000"), identity.endMs());
      assertEquals("frame_interval", identity.timePrecision());
      assertEquals("machine_ocr", identity.origin());
      assertEquals(new BigDecimal("12410.000"), procedure.endMs());
      assertEquals("server_chunk", procedure.timePrecision());
      assertEquals("machine_asr", procedure.origin());
      assertEquals(
          Set.of(MANUAL, OCR, SPOKEN, UNRELATED),
          fixture.models.contextOnly.stream()
              .map(Context::context)
              .collect(java.util.stream.Collectors.toSet()));
      assertSources(fixture, answer);
    }

    try (var reopened = new Fixture(directory)) {
      assertSources(reopened, answer);
      assertEquals(0, reopened.models.requests, "saved source reads must not call a model");
      reopened.remove(extra);
      assertThrows(
          ApplicationException.class,
          () -> reopened.answers.source(reopened.context.owner, answer.answerId(), 1),
          "the durable trace must retain selected documents absent from final citations");
    }
  }

  @Test
  void ordinarySynthesisChoosesRelevantSourcesWithoutArtificialCitationDependency() {
    try (var fixture = new Fixture(directory)) {
      String manual = fixture.context.publish("synthetic-manual.txt", MANUAL);
      var video = fixture.publishVideo();
      fixture.models.omitIdentity = true;
      var answer =
          fixture.answers.answer(fixture.context.owner, selected(manual, video.documentId()));
      assertEquals("answered", answer.status(), answer.reason());
      assertNull(answer.reason());
      assertEquals(2, answer.citations().size());
      assertTrue(answer.answer().contains("长按月亮键"));
      assertSources(fixture, answer);
    }
  }

  @Test
  void finalReleaseRevalidatesSelectedDocumentsEvenWhenTheyAreNotCited() {
    try (var fixture = new Fixture(directory)) {
      String manual = fixture.context.publish("synthetic-manual.txt", MANUAL);
      var video = fixture.publishVideo();
      String extra = fixture.context.publish("synthetic-maintenance.txt", UNRELATED);
      fixture.models.onVerify = () -> fixture.remove(extra);
      var answer =
          fixture.answers.answer(
              fixture.context.owner, selected(manual, video.documentId(), extra));
      assertEquals("abstained", answer.status());
      assertEquals("scope_changed", answer.reason());
      assertTrue(answer.citations().isEmpty());
      assertThrows(
          ApplicationException.class,
          () -> fixture.answers.source(fixture.context.owner, answer.answerId(), 1));
    }
  }

  private static void assertSources(Fixture fixture, KnowledgeAnswerResult answer) {
    for (var citation : answer.citations()) {
      var source =
          fixture.answers.source(fixture.context.owner, answer.answerId(), citation.citationId());
      assertEquals(answer.answerId(), source.answerId());
      assertEquals(citation, source.citation());
    }
  }

  private static KnowledgeCitation citation(KnowledgeAnswerResult answer, String kind) {
    return answer.citations().stream()
        .filter(value -> value.evidenceKind().equals(kind))
        .findFirst()
        .orElseThrow();
  }

  private static AnswerCommand selected(String... ids) {
    return new AnswerCommand(QUESTION, DocumentSelection.selected(List.of(ids)));
  }

  private record PublishedVideo(String documentId, String revisionId) {}

  private static final class Fixture implements AutoCloseable {
    final AnswerTestContext context;
    final Models models = new Models();
    final AtomicReference<RetrievalSettings> settings =
        new AtomicReference<>(RetrievalSettings.defaults());
    final ManagedTextRuntime runtime;
    final ProductHelpService retrieval;
    final KnowledgeAnswerService answers;

    Fixture(Path directory) {
      context = new AnswerTestContext(directory, DEADLINE, 1);
      runtime =
          new ManagedTextRuntime(
              context.authority.store(),
              (version, configuration) -> {
                var textAnswers =
                    new AnswerService(
                        context.evidence, models, context.projection, context.target, DEADLINE, 1);
                var indexing =
                    new IndexingTaskProcessor(
                        context.authority.indexing(),
                        context.owner.workspaceId(),
                        context.target,
                        DEADLINE,
                        ignored -> {
                          throw new AssertionError("Answering must not start indexing");
                        });
                return new TextRuntimeSnapshot(
                    version,
                    models,
                    context.projection,
                    context.target,
                    textAnswers,
                    indexing,
                    () -> {});
              });
      var snapshot = runtime.prepare(1, ManagedTextTestFixture.configuration());
      try (var lease = context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
        runtime.install(snapshot, lease, () -> {});
      }
      retrieval = new ProductHelpService(context.evidence, runtime, DEADLINE, settings::get);
      answers =
          new KnowledgeAnswerService(
              context.evidence,
              retrieval,
              runtime,
              new KnowledgeTraceService(context.authority.store(), context.evidence),
              DEADLINE,
              1);
    }

    PublishedVideo publishVideo() {
      var store = context.authority.store();
      var ingestion =
          new IngestionService(
              store,
              new IngestionRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              null,
              null,
              null,
              COMPILER,
              true);
      var upload =
          ingestion.uploadDocument(
              context.owner, "synthetic-tutorial.mp4", "video/mp4", VideoAnswerFixture.ORIGINAL);
      var claim = ingestion.claimIngestion(context.owner.workspaceId()).orElseThrow();
      var frame = VideoCompilationFixture.frame(0, 0, 40_000).frame();
      int length = OCR.codePointCount(0, OCR.length());
      var regions = new ArrayList<ImageTextRegion>();
      var words = java.util.regex.Pattern.compile("\\S+").matcher(OCR);
      while (words.find()) {
        regions.add(
            new ImageTextRegion(
                OCR.codePointCount(0, words.start()),
                OCR.codePointCount(0, words.end()),
                0,
                0,
                2,
                2));
      }
      var ocr =
          new VideoOcrCompilation(
              "synthetic-ocr-v1",
              List.of(
                  new VideoFrameOcr(
                      0,
                      frame.image().sha256(),
                      new ImageDimensions(2, 2),
                      OCR,
                      List.of(new VideoOcrSegment(0, 0, length, OCR)),
                      regions)));
      var audio =
          new AudioTranscription(
              VIDEO_SHA,
              "video-decoder-v1",
              "synthetic-asr-v1",
              "synthetic-transcription-v1",
              198_560,
              List.of(new AudioTranscriptSpan(0, 0, 12_410, SPOKEN)));
      assertTrue(
          ingestion.completeVideoIngestion(
              claim,
              new VideoCompilation(
                  VIDEO_SHA,
                  "video-decoder-v1",
                  COMPILER,
                  0,
                  12_410_000,
                  List.of(new VideoFrameRecall(frame, null)),
                  audio,
                  ocr)));
      context.authority.createIndexing(context.owner, upload.documentId(), context.target);
      var index = context.authority.claimIndexing(context.owner.workspaceId()).orElseThrow();
      var entries =
          index.items().stream()
              .map(
                  item ->
                      new RetrievalProjection.Entry(
                          RetrievalProjection.physicalSegmentId(
                              index.projectionGenerationId(), item.evidenceId()),
                          context.owner.workspaceId(),
                          index.documentId(),
                          index.projectionGenerationId(),
                          item.recallText(),
                          List.of(1.0, 0.0)))
              .toList();
      context.projection.data.initialize();
      context.projection.data.upsert(entries);
      var hashes = new TreeMap<String, String>();
      entries.forEach(
          entry -> hashes.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
      assertTrue(
          context.authority.completeIndexing(
              index,
              hashes,
              context.projection.data.verify(
                  new RetrievalProjection.RevisionManifest(
                      context.owner.workspaceId(),
                      index.documentId(),
                      index.projectionGenerationId(),
                      hashes))));
      return new PublishedVideo(upload.documentId(), claim.revisionId());
    }

    void remove(String documentId) {
      DocumentWithdrawal.withdraw(context.authority.store(), context.owner, documentId);
    }

    @Override
    public void close() {
      answers.close();
      retrieval.close();
      runtime.close();
      context.close();
    }
  }

  private static final class Models implements TextModels {
    int requests;
    int generations;
    boolean omitIdentity;
    Runnable onVerify = () -> {};
    Runnable onRerank = () -> {};
    Function<List<String>, List<Ranked>> ranking =
        texts ->
            IntStream.range(0, texts.size()).mapToObj(index -> new Ranked(index, 1.0)).toList();
    List<Context> contextOnly = List.of();

    @Override
    public Synthesis answerKnowledge(String question, List<SynthesisEvidence> evidence) {
      requests++;
      generations++;
      contextOnly = evidence.stream().map(item -> new Context(item.id(), item.context())).toList();
      var ids =
          evidence.stream()
              .filter(
                  item ->
                      !item.quote().equals(UNRELATED)
                          && (!omitIdentity || !item.quote().equals(OCR)))
              .map(SynthesisEvidence::id)
              .toList();
      onVerify.run();
      return new Synthesis(
          false, List.of(new Statement("青榆X1先短按电源开机，再长按月亮键3秒开启夜间模式，月亮指示灯变绿表示开启。", ids)));
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      requests++;
      return texts.stream().map(ignored -> List.of(1.0, 0.0)).toList();
    }

    @Override
    public List<Ranked> rerank(String question, List<String> texts) {
      requests++;
      onRerank.run();
      return ranking.apply(texts);
    }

    @Override
    public Extraction extract(String question, List<Evidence> evidence) {
      throw new AssertionError("Knowledge answering must retain typed extraction evidence");
    }

    @Override
    public String revision() {
      return "test-answer-model-v1";
    }
  }

  private record Context(String id, String context) {}
}
