package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisBatchReview;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisDraft.Item;
import com.evidence.rag.model.domain.SynopsisDraft.Section;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import com.evidence.rag.support.HierarchyFixtureModels;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HierarchicalSynopsisServiceTest {
  @Test
  void completeTreeProcessesEveryLeafAndReviewsEveryOriginalBatch() {
    var input = input(2049, 20, false);
    var models = new HierarchyFixtureModels();
    var result = service(models).generate(input, () -> true);
    assertNull(result.unavailableReason());
    assertEquals(
        input.evidence(), models.leaves.stream().flatMap(b -> b.evidence().stream()).toList());
    assertEquals(models.leaves, models.reviewed);
    assertTrue(models.reductions.size() > 10);
    assertEquals(input.publication(), result.publication());
    assertEquals(input.fingerprint(), result.inputFingerprint());
    assertEquals(HierarchicalSynopsisService.POLICY_REVISION, result.policyRevision());
    assertEquals("source-2048", result.entries().get(1).evidence().getFirst().id());
    for (var reduction : models.reductions) {
      assertEquals(input.publication(), reduction.publication());
      assertEquals(input.fingerprint(), reduction.inputFingerprint());
    }
    for (int i = 0; i < result.entries().size(); i++) {
      assertEquals(
          result.entries().get(i).item().evidenceIds(),
          models.verified.get(i).stream().map(SynopsisEvidence::id).toList());
      assertEquals(
          result.entries().get(i).evidence().getFirst().sha256(),
          models.verified.get(i).getFirst().sha256());
    }
  }

  @Test
  void characterBoundSplitsTimedFileAndKeepsActualTailInterval() {
    var input = input(17, 4000, true);
    var model = new HierarchyFixtureModels();
    var result = service(model).generate(input, () -> true);
    assertNull(result.unavailableReason());
    assertEquals(2, model.leaves.size());
    assertEquals(
        new SynopsisEvidence.TimeRange(16000, 17000), result.entries().getLast().interval());
    assertEquals(model.leaves, model.reviewed);
  }

  @Test
  void tailConditionOmittedFromDerivedNodesStillVetoesWholeFinalSummary() {
    var model =
        new HierarchyFixtureModels() {
          @Override
          public SynopsisBatchReview review(SynopsisBatch batch, List<Item> items) {
            var review = super.review(batch, items);
            if (batch.endOrdinal() == batch.publication().segmentCount()) {
              return new SynopsisBatchReview(
                  true,
                  IntStream.range(0, items.size())
                      .mapToObj(i -> new SynopsisBatchReview.ItemReview(i, i != 1))
                      .toList());
            }
            return review;
          }
        };
    unavailable(service(model).generate(input(65, 10, false), () -> true), "unsupported_claims");
    assertEquals(3, model.verified.size());
    assertEquals(2, model.reviewed.size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "incomplete", "null", "unknown_index"})
  void noPartialOrIncompleteFinalReviewCanPublish(String mode) {
    var model =
        new HierarchyFixtureModels() {
          @Override
          public SynopsisBatchReview review(SynopsisBatch batch, List<Item> items) {
            var review = super.review(batch, items);
            return switch (mode) {
              case "missing" -> new SynopsisBatchReview(true, review.items().subList(0, 1));
              case "incomplete" -> new SynopsisBatchReview(false, review.items());
              case "unknown_index" ->
                  new SynopsisBatchReview(
                      true,
                      IntStream.range(1, items.size() + 1)
                          .mapToObj(i -> new SynopsisBatchReview.ItemReview(i, true))
                          .toList());
              default -> null;
            };
          }
        };
    unavailable(service(model).generate(input(65, 10, false), () -> true), "incomplete_evidence");
  }

  @ParameterizedTest
  @ValueSource(strings = {"leaf", "reduce", "verify", "review"})
  void authorityAndConfigurationAreRecheckedAfterEveryKindOfCall(String operation) {
    for (boolean configuration : List.of(false, true)) {
      var current = new AtomicBoolean(true);
      var model = new HierarchyFixtureModels();
      model.afterCall =
          op -> {
            if (op.equals(operation)) {
              if (configuration) {
                model.modelRevision = "changed";
              } else {
                current.set(false);
              }
            }
          };
      unavailable(
          service(model).generate(input(65, 10, false), current::get),
          configuration ? "configuration_changed" : "source_changed");
    }
  }

  @Test
  void unknownLeafAndReductionReferencesCannotEnterVerification() {
    for (boolean leaf : List.of(false, true)) {
      var model =
          new HierarchyFixtureModels() {
            @Override
            public SynopsisDraft draftLeaf(SynopsisBatch batch) {
              return leaf
                  ? HierarchyFixtureModels.draft("invented", "invented", false)
                  : super.draftLeaf(batch);
            }

            @Override
            public SynopsisDraft reduce(SynopsisReductionInput input) {
              return HierarchyFixtureModels.draft("invented", "invented", false);
            }
          };
      unavailable(service(model).generate(input(65, 10, false), () -> true), "incomplete_evidence");
      assertTrue(model.verified.isEmpty());
    }
  }

  @Test
  void unsupportedLastEntryDoesNotReturnEarlierSuccessfulEntries() {
    var model =
        new HierarchyFixtureModels() {
          @Override
          public boolean verify(Item item, List<SynopsisEvidence> evidence) {
            super.verify(item, evidence);
            return verified.size() < 3;
          }
        };
    unavailable(service(model).generate(input(65, 10, false), () -> true), "unsupported_claims");
    assertTrue(model.reviewed.isEmpty());
  }

  @Test
  void emptyOrOversizedLeafCannotBecomeAnApparentlyCompleteNode() {
    for (int count : List.of(0, 17)) {
      var model =
          new HierarchyFixtureModels() {
            @Override
            public SynopsisDraft draftLeaf(SynopsisBatch batch) {
              return new SynopsisDraft(
                  false,
                  IntStream.range(0, count)
                      .mapToObj(
                          i ->
                              new Item(
                                  Section.TOPIC,
                                  "候选" + i,
                                  List.of(batch.evidence().getFirst().id())))
                      .toList());
            }
          };
      unavailable(service(model).generate(input(65, 10, false), () -> true), "incomplete_evidence");
      assertTrue(model.reductions.isEmpty());
    }
  }

  @Test
  void allActualCitationsMustFitOneProofRequestNeverSplitAndOrTheirJudgments() {
    var model =
        new HierarchyFixtureModels() {
          @Override
          public SynopsisDraft reduce(SynopsisReductionInput input) {
            var items = new ArrayList<>(super.reduce(input).items());
            items.set(0, new Item(Section.OVERVIEW, "两个原始片段共同支持", List.of("source-0", "source-1")));
            return new SynopsisDraft(false, items);
          }
        };
    unavailable(service(model).generate(input(2, 40000, false), () -> true), "incomplete_evidence");
    assertTrue(model.verified.isEmpty());
  }

  @Test
  void refusalNullAndMissingRequiredSectionsAreNotSummaries() {
    for (int mode = 0; mode < 4; mode++) {
      int selected = mode;
      var model =
          new HierarchyFixtureModels() {
            @Override
            public SynopsisDraft reduce(SynopsisReductionInput input) {
              var draft = super.reduce(input);
              return switch (selected) {
                case 0 -> null;
                case 1 -> new SynopsisDraft(true, List.of());
                case 2 -> new SynopsisDraft(false, draft.items().subList(0, 2));
                default ->
                    new SynopsisDraft(
                        false, List.of(new Item(Section.TIMELINE, "非法时间", List.of("source-0"))));
              };
            }
          };
      unavailable(
          service(model).generate(input(65, 10, false), () -> true),
          mode == 0 ? "model_failure" : mode == 1 ? "model_refused" : "incomplete_evidence");
      assertTrue(model.verified.isEmpty());
    }
  }

  @Test
  void providerErrorsCancellationAndInterruptedCallsHaveNoRetry() {
    for (String code : List.of("model_http_failed", "model_interrupted", "cancel")) {
      var model = new HierarchyFixtureModels();
      model.afterCall =
          op -> {
            if (code.equals("cancel")) {
              throw new CancellationException();
            }
            throw new TextModels.Failure(code);
          };
      try {
        unavailable(
            service(model).generate(input(65, 10, false), () -> true),
            code.equals("model_http_failed") ? "model_failure" : "processing_interrupted");
        assertEquals(1, model.leaves.size());
        assertEquals(code.equals("model_interrupted"), Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void budgetCoversLastReviewAndClearsAllVerifiedEntries() {
    var model = new HierarchyFixtureModels();
    model.afterCall =
        operation -> {
          if (operation.equals("review")) {
            long end = System.nanoTime() + Duration.ofMillis(40).toNanos();
            while (System.nanoTime() < end) {
              Thread.onSpinWait();
            }
          }
        };
    unavailable(
        new HierarchicalSynopsisService(model, Duration.ofMillis(20))
            .generate(input(65, 10, false), () -> true),
        "processing_timeout");
  }

  @Test
  void invalidArgumentsAndUnavailableAuthorityMakeNoCalls() {
    var model = new HierarchyFixtureModels();
    assertThrows(
        ApplicationException.class,
        () -> new HierarchicalSynopsisService(null, Duration.ofSeconds(1)));
    assertThrows(ApplicationException.class, () -> new HierarchicalSynopsisService(model, null));
    assertThrows(
        ApplicationException.class,
        () -> new HierarchicalSynopsisService(model, Duration.ofMillis(9)));
    assertThrows(
        ApplicationException.class,
        () -> new HierarchicalSynopsisService(model, Duration.ofMinutes(11)));
    assertThrows(ApplicationException.class, () -> service(model).generate(null, () -> true));
    assertThrows(
        ApplicationException.class, () -> service(model).generate(input(65, 10, false), null));
    unavailable(service(model).generate(input(65, 10, false), () -> false), "source_changed");
    unavailable(
        service(model)
            .generate(
                input(65, 10, false),
                () -> {
                  throw new IllegalStateException();
                }),
        "source_changed");
    Thread.currentThread().interrupt();
    try {
      unavailable(
          service(model).generate(input(65, 10, false), () -> true), "processing_interrupted");
    } finally {
      Thread.interrupted();
    }
    assertTrue(model.leaves.isEmpty());
  }

  private static HierarchicalSynopsisService service(HierarchyFixtureModels model) {
    return new HierarchicalSynopsisService(model, Duration.ofSeconds(10));
  }

  private static void unavailable(FileSynopsis result, String reason) {
    assertEquals(reason, result.unavailableReason());
    assertTrue(result.entries().isEmpty());
  }

  private static SynopsisFileInput input(int count, int textLength, boolean timed) {
    var evidence = new ArrayList<SynopsisEvidence>();
    for (int i = 0; i < count; i++) {
      evidence.add(
          new SynopsisEvidence(
              "source-" + i,
              timed ? SynopsisEvidence.Kind.AUDIO_TRANSCRIPT : SynopsisEvidence.Kind.TEXT,
              new SynopsisEvidence.Text("文".repeat(textLength)),
              timed ? new SynopsisEvidence.TimeRange(i * 1000L, (i + 1) * 1000L) : null));
    }
    return new SynopsisFileInput(
        new PublicationVersion(
            "doc",
            "publication",
            "revision",
            "generation",
            "a".repeat(64),
            "compiler-v1",
            new IndexTarget("embedding-v1", "projection-v1", "model-v1", 2),
            "b".repeat(64),
            count),
        evidence);
  }
}
