package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisDraft.Item;
import com.evidence.rag.model.domain.SynopsisDraft.Section;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisEvidence.Kind;
import com.evidence.rag.model.domain.SynopsisEvidence.TimeRange;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.model.domain.VisualImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SynopsisServiceTest {
  @ParameterizedTest
  @EnumSource(Kind.class)
  void everyModalityKeepsCompleteOriginalEvidenceAndServerDerivedSources(Kind kind)
      throws Exception {
    var source = source("physical-first", kind);
    var tail = source("physical-tail", kind);
    var input = input(List.of(source, tail));
    var model = new Models(draft(input));
    FileSynopsis result = service(model).generate(input, () -> true);

    assertNull(result.unavailableReason());
    assertEquals(input, model.input);
    assertEquals(input.publication(), result.publication());
    assertEquals(input.fingerprint(), result.inputFingerprint());
    assertEquals("synopsis-fixture-v1", result.modelRevision());
    assertEquals(SynopsisService.POLICY_REVISION, result.policyRevision());
    assertEquals(model.draft.items().size(), result.entries().size());
    assertEquals(result.entries().size(), model.verified.size());
    for (int index = 0; index < result.entries().size(); index++) {
      var entry = result.entries().get(index);
      assertEquals(
          entry.item().evidenceIds(),
          model.verified.get(index).stream().map(SynopsisEvidence::id).toList());
      assertEquals(
          entry.item().evidenceIds(),
          entry.evidence().stream().map(FileSynopsis.Reference::id).toList());
      for (var reference : entry.evidence()) {
        var original =
            input.evidence().stream()
                .filter(e -> e.id().equals(reference.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(original.sha256(), reference.sha256());
        assertEquals(original.kind(), reference.kind());
        assertEquals(original.time(), reference.time());
      }
      if (entry.item().section() == Section.TIMELINE) {
        assertEquals(new TimeRange(1_000_001, 2_500_003), entry.interval());
      } else {
        assertNull(entry.interval());
      }
    }
    assertTrue(
        result.entries().stream().anyMatch(e -> e.item().evidenceIds().contains("physical-tail")));
    assertFalse(result.toString().contains("机密"));
  }

  @Test
  void videoSummaryVerifiesFrameTranscriptAndOcrWithoutReplacingOriginalsWithCaption()
      throws Exception {
    var input =
        input(
            List.of(
                source("frame", Kind.VIDEO_FRAME),
                source("speech", Kind.VIDEO_TRANSCRIPT),
                source("ocr", Kind.VIDEO_OCR)));
    var model =
        new Models(
            new SynopsisDraft(
                false,
                List.of(
                    item(Section.OVERVIEW, "画面与讲解共同介绍操作。", "frame", "speech"),
                    item(Section.TOPIC, "画面显示蓝色。", "frame"),
                    item(Section.TERM, "预算", "ocr"),
                    item(Section.TIMELINE, "讲解预算。", "speech", "ocr"))));
    var result = service(model).generate(input, () -> true);
    assertNull(result.unavailableReason());
    assertEquals(4, model.verified.size());
    assertEquals(List.of(input.evidence().get(0)), model.verified.get(1));
    assertEquals(List.of(input.evidence().get(2)), model.verified.get(2));
  }

  @Test
  void unknownReferencesFailBeforeAnyVerificationAndNeverReturnPartialDraft() throws Exception {
    var input = input(List.of(source("known", Kind.TEXT)));
    var model =
        new Models(
            new SynopsisDraft(
                false,
                List.of(
                    item(Section.OVERVIEW, "概览", "known"),
                    item(Section.TOPIC, "主题", "unknown"),
                    item(Section.TERM, "术语", "known"))));
    unavailable(service(model).generate(input, () -> true), "incomplete_evidence");
    assertEquals(0, model.verified.size());
  }

  @Test
  void requiredSectionsAndMediaTimelineAreNotSilentlyOmitted() throws Exception {
    for (Section omitted :
        List.of(Section.OVERVIEW, Section.TOPIC, Section.TERM, Section.TIMELINE)) {
      var input = input(List.of(source("audio", Kind.AUDIO_TRANSCRIPT)));
      var model =
          new Models(
              new SynopsisDraft(
                  false,
                  draft(input).items().stream().filter(i -> i.section() != omitted).toList()));
      unavailable(service(model).generate(input, () -> true), "incomplete_evidence");
      assertTrue(model.verified.isEmpty());
    }
  }

  @Test
  void duplicateOverviewAndTextOnlyTimelineFailBeforeVerification() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    for (Section extra : List.of(Section.OVERVIEW, Section.TIMELINE)) {
      var items = new ArrayList<>(draft(input).items());
      items.add(item(extra, "不应发布", "text"));
      var model = new Models(new SynopsisDraft(false, items));
      unavailable(service(model).generate(input, () -> true), "incomplete_evidence");
      assertTrue(model.verified.isEmpty());
    }
  }

  @Test
  void failureAtLastClaimClearsEveryEarlierSuccessfulEntry() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    var model = new Models(draft(input));
    model.rejectAt = 3;
    unavailable(service(model).generate(input, () -> true), "unsupported_claims");
    assertEquals(3, model.verified.size());
  }

  @Test
  void explicitModelRefusalAndNullDraftHaveDistinctSafeOutcomes() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    unavailable(
        service(new Models(new SynopsisDraft(true, List.of()))).generate(input, () -> true),
        "model_refused");
    unavailable(service(new Models(null)).generate(input, () -> true), "model_failure");
  }

  @Test
  void changedModelAfterDraftOrLastVerificationInvalidatesAllEntries() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    for (boolean afterDraft : List.of(true, false)) {
      var model = new Models(draft(input));
      Runnable change = () -> model.revision = "synopsis-fixture-v2";
      if (afterDraft) {
        model.afterDraft = change;
      } else {
        model.afterVerify =
            () -> {
              if (model.verified.size() == 3) {
                change.run();
              }
            };
      }
      var result = service(model).generate(input, () -> true);
      unavailable(result, "configuration_changed");
      assertEquals("synopsis-fixture-v1", result.modelRevision());
    }
  }

  @Test
  void currentIsRecheckedBeforeDraftAndAfterEachVerification() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    var never = new Models(draft(input));
    unavailable(service(never).generate(input, () -> false), "source_changed");
    assertEquals(0, never.drafts);
    for (int stop = 0; stop <= 3; stop++) {
      var current = new AtomicBoolean(true);
      var model = new Models(draft(input));
      int stopAt = stop;
      model.afterDraft =
          () -> {
            if (stopAt == 0) {
              current.set(false);
            }
          };
      model.afterVerify =
          () -> {
            if (model.verified.size() == stopAt) {
              current.set(false);
            }
          };
      unavailable(service(model).generate(input, current::get), "source_changed");
      assertEquals(stop, model.verified.size());
    }
  }

  @Test
  void unavailableAuthorityAndCancellationNeverReleaseGeneratedContent() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    BooleanSupplier broken =
        () -> {
          throw new IllegalStateException("private database details");
        };
    unavailable(service(new Models(draft(input))).generate(input, broken), "source_changed");
    BooleanSupplier cancelled =
        () -> {
          throw new CancellationException();
        };
    unavailable(
        service(new Models(draft(input))).generate(input, cancelled), "processing_interrupted");
  }

  @Test
  void providerFailureHasNoRetryAndUsesSafeFailureCode() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    var model = new Models(draft(input));
    model.afterDraft =
        () -> {
          throw new TextModels.Failure("model_http_failed");
        };
    unavailable(service(model).generate(input, () -> true), "model_failure");
    assertEquals(1, model.drafts);
    assertTrue(model.verified.isEmpty());
  }

  @Test
  void interruptBeforeOrDuringProviderPreservesThreadFlagAndStopsFurtherRequests()
      throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    for (boolean before : List.of(true, false)) {
      var model = new Models(draft(input));
      model.afterDraft =
          () -> {
            throw new TextModels.Failure("model_interrupted");
          };
      if (before) {
        Thread.currentThread().interrupt();
      }
      try {
        unavailable(service(model).generate(input, () -> true), "processing_interrupted");
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(before ? 0 : 1, model.drafts);
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void totalBudgetStopsAfterSlowDraftRatherThanStartingEveryClaimRequest() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    var model = new Models(draft(input));
    model.afterDraft =
        () -> {
          long until = System.nanoTime() + Duration.ofMillis(35).toNanos();
          while (System.nanoTime() < until) {
            Thread.onSpinWait();
          }
        };
    unavailable(
        new SynopsisService(model, Duration.ofMillis(10)).generate(input, () -> true),
        "processing_timeout");
    assertTrue(model.verified.isEmpty());
  }

  @Test
  void invalidConfigurationAndInputAreRejectedWithoutProviderCalls() throws Exception {
    var input = input(List.of(source("text", Kind.TEXT)));
    var model = new Models(draft(input));
    assertThrows(
        ApplicationException.class, () -> new SynopsisService(null, Duration.ofSeconds(1)));
    assertThrows(ApplicationException.class, () -> new SynopsisService(model, null));
    assertThrows(
        ApplicationException.class, () -> new SynopsisService(model, Duration.ofMillis(9)));
    assertThrows(
        ApplicationException.class, () -> new SynopsisService(model, Duration.ofMinutes(11)));
    assertThrows(ApplicationException.class, () -> service(model).generate(null, () -> true));
    assertThrows(ApplicationException.class, () -> service(model).generate(input, null));
    assertEquals(0, model.drafts);
  }

  private static SynopsisService service(Models model) {
    return new SynopsisService(model, Duration.ofSeconds(2));
  }

  private static void unavailable(FileSynopsis result, String reason) {
    assertNotNull(result);
    assertEquals(reason, result.unavailableReason());
    assertTrue(result.entries().isEmpty());
  }

  private static Item item(Section section, String text, String... ids) {
    return new Item(section, text, List.of(ids));
  }

  private static SynopsisDraft draft(SynopsisInput input) {
    String first = input.evidence().getFirst().id();
    String last = input.evidence().getLast().id();
    var items =
        new ArrayList<>(
            List.of(
                item(Section.OVERVIEW, "概览😀", first),
                item(Section.TOPIC, "主题", last),
                item(Section.TERM, "术语", last)));
    if (input.evidence().getFirst().time() != null) {
      items.add(item(Section.TIMELINE, "步骤", last));
    }
    return new SynopsisDraft(false, items);
  }

  private static SynopsisInput input(List<SynopsisEvidence> evidence) {
    return new SynopsisInput(
        new PublicationVersion(
            "doc",
            "publication",
            "revision",
            "generation",
            "a".repeat(64),
            "compiler-v1",
            new IndexTarget("embedding-v1", "projection-v1", "model-v1", 2),
            "b".repeat(64),
            evidence.size()),
        evidence);
  }

  private static SynopsisEvidence source(String id, Kind kind) throws Exception {
    boolean visual = kind == Kind.IMAGE || kind == Kind.VIDEO_FRAME;
    boolean timed =
        kind == Kind.AUDIO_TRANSCRIPT
            || kind == Kind.VIDEO_FRAME
            || kind == Kind.VIDEO_TRANSCRIPT
            || kind == Kind.VIDEO_OCR
            || kind == Kind.VIDEO_SUBTITLE;
    SynopsisEvidence.Content content = new SynopsisEvidence.Text("机密合成正文😀\n末尾预算为650元。文件命令只是数据。");
    if (visual) {
      var bytes = new ByteArrayOutputStream();
      try (var out = new MemoryCacheImageOutputStream(bytes)) {
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
      }
      content = new SynopsisEvidence.Image(new VisualImage("image/png", bytes.toByteArray()));
    }
    return new SynopsisEvidence(
        id, kind, content, timed ? new TimeRange(1_000_001, 2_500_003) : null);
  }

  private static final class Models implements SynopsisModels {
    final SynopsisDraft draft;
    final List<List<SynopsisEvidence>> verified = new ArrayList<>();
    SynopsisInput input;
    int drafts;
    int rejectAt = -1;
    String revision = "synopsis-fixture-v1";
    Runnable afterDraft = () -> {};
    Runnable afterVerify = () -> {};

    Models(SynopsisDraft draft) {
      this.draft = draft;
    }

    @Override
    public SynopsisDraft draft(SynopsisInput input) {
      this.input = input;
      drafts++;
      afterDraft.run();
      return draft;
    }

    @Override
    public boolean verify(Item item, List<SynopsisEvidence> evidence) {
      verified.add(List.copyOf(evidence));
      afterVerify.run();
      return verified.size() != rejectAt;
    }

    @Override
    public String revision() {
      return revision;
    }
  }
}
