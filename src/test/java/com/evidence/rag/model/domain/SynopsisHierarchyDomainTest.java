package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class SynopsisHierarchyDomainTest {
  private static final String SHA = "a".repeat(64);

  @Test
  void shortFilesRetainTheOriginalV1RawFingerprintAndCacheIt() {
    var input = new SynopsisInput(publication(1), List.of(text("first", "首😀\n尾")));
    assertEquals(
        "ca52dc3965fefeb3a4ac71fc5f90fe8321e046a2ac3482a4ed996f053df200fd", input.fingerprint());
    var file = new SynopsisFileInput(input);
    assertTrue(file.bounded());
    assertEquals(input, file.toBounded());
    assertEquals(input.fingerprint(), file.fingerprint());
    assertSame(file.fingerprint(), file.fingerprint());
    assertSame(input.publication(), file.publication());
  }

  @Test
  void completeFileIdentityCoversTheLastLeafAndVersionWithoutSubsetPublication() {
    var items = new ArrayList<>(leaves(65, "内容"));
    var file = new SynopsisFileInput(publication(65), items);
    assertEquals(65, file.evidence().size());
    assertFalse(file.bounded());
    invalid(file::toBounded);
    var changed = new ArrayList<>(items);
    changed.set(64, text("source64", "最后明确条件"));
    assertNotEquals(
        file.fingerprint(), new SynopsisFileInput(publication(65), changed).fingerprint());
    var original = publication(65);
    var revision =
        new PublicationVersion(
            original.documentId(),
            original.publicationId(),
            "revision-two",
            original.projectionGenerationId(),
            original.sourceSha256(),
            original.parserRevision(),
            original.target(),
            original.manifestSha256(),
            65);
    assertNotEquals(file.fingerprint(), new SynopsisFileInput(revision, items).fingerprint());
    items.clear();
    assertEquals(65, file.evidence().size());
    assertThrows(UnsupportedOperationException.class, () -> file.evidence().clear());
  }

  @Test
  void fileBudgetCoversAll4096LeavesAndAllUnicodeCodePoints() {
    assertEquals(
        4096, new SynopsisFileInput(publication(4096), leaves(4096, "😀")).evidence().size());
    var exact = new SynopsisFileInput(publication(40), leaves(40, "😀".repeat(62500)));
    assertEquals(40, exact.evidence().size());
    assertFalse(exact.bounded());
    invalid(() -> new SynopsisFileInput(publication(40), leaves(40, "😀".repeat(62501))));
    invalid(() -> new SynopsisFileInput(null, List.of(text("source", "x"))));
    invalid(() -> new SynopsisFileInput(publication(1), null));
    invalid(() -> new SynopsisFileInput(publication(1), List.of()));
    invalid(() -> new SynopsisFileInput(publication(2), List.of(text("source", "x"))));
    invalid(
        () ->
            new SynopsisFileInput(
                publication(2), List.of(text("source", "x"), text("source", "y"))));
    invalid(() -> new SynopsisFileInput(publication(1), Arrays.asList((SynopsisEvidence) null)));
    invalid(() -> new SynopsisFileInput((SynopsisInput) null));
  }

  @Test
  void fullFilePreservesImageCountByteBudgetAndDefensivePixels() {
    var small = new VisualImage("image/png", new byte[] {1});
    var images = IntStream.range(0, 128).mapToObj(i -> image("frame" + i, small)).toList();
    assertEquals(128, new SynopsisFileInput(publication(128), images).evidence().size());
    var excess = new ArrayList<>(images);
    excess.add(image("last", small));
    invalid(() -> new SynopsisFileInput(publication(129), excess));
    byte[] bytes = new byte[8 * 1024 * 1024];
    var original = new VisualImage("image/png", bytes);
    var four = IntStream.range(0, 4).mapToObj(i -> image("frame" + i, original)).toList();
    var file = new SynopsisFileInput(publication(4), four);
    assertFalse(file.bounded());
    String before = file.fingerprint();
    bytes[0] = 1;
    original.content()[0] = 2;
    assertEquals(before, file.fingerprint());
    assertEquals(
        0, ((SynopsisEvidence.Image) file.evidence().getFirst().content()).image().content()[0]);
    var five = new ArrayList<>(four);
    five.add(image("fifth", original));
    invalid(() -> new SynopsisFileInput(publication(5), five));
  }

  @Test
  void partitionGreedilyCoversEveryLeafOnceAndKeepsGlobalIdentity() {
    var file = new SynopsisFileInput(publication(130), leaves(130, "内容"));
    var batches = SynopsisBatch.partition(file);
    assertEquals(List.of(64, 64, 2), batches.stream().map(b -> b.evidence().size()).toList());
    assertEquals(List.of(0, 64, 128), batches.stream().map(SynopsisBatch::fromOrdinal).toList());
    assertEquals(List.of(64, 128, 130), batches.stream().map(SynopsisBatch::endOrdinal).toList());
    assertEquals(file.evidence(), batches.stream().flatMap(b -> b.evidence().stream()).toList());
    for (var batch : batches) {
      assertSame(file.publication(), batch.publication());
      assertEquals(file.fingerprint(), batch.inputFingerprint());
      assertEquals(130, batch.publication().segmentCount());
    }
    assertThrows(UnsupportedOperationException.class, () -> batches.clear());
  }

  @Test
  void partitionUsesUnicodeBudgetNotJustEvidenceCount() {
    var file = new SynopsisFileInput(publication(17), leaves(17, "😀".repeat(4000)));
    var batches = SynopsisBatch.partition(file);
    assertEquals(List.of(16, 1), batches.stream().map(b -> b.evidence().size()).toList());
    assertEquals(file.evidence().getLast(), batches.getLast().evidence().getLast());
    assertFalse(file.bounded());
  }

  @Test
  void partitionUsesBothImageCountAndTenMiBOriginalImageBudget() {
    var small = new VisualImage("image/png", new byte[] {1});
    var nine = IntStream.range(0, 9).mapToObj(i -> image("frame" + i, small)).toList();
    assertEquals(
        List.of(8, 1),
        SynopsisBatch.partition(new SynopsisFileInput(publication(9), nine)).stream()
            .map(b -> b.evidence().size())
            .toList());
    var six = new VisualImage("image/png", new byte[6 * 1024 * 1024]);
    var four = new VisualImage("image/png", new byte[4 * 1024 * 1024]);
    var items = List.of(image("six", six), image("four", four), image("tail", small));
    var batches = SynopsisBatch.partition(new SynopsisFileInput(publication(3), items));
    assertEquals(List.of(2, 1), batches.stream().map(b -> b.evidence().size()).toList());
    var single = image("ten", new VisualImage("image/png", new byte[10 * 1024 * 1024]));
    var file = new SynopsisFileInput(publication(1), List.of(single));
    assertFalse(file.bounded());
    assertEquals(List.of(single), SynopsisBatch.partition(file).getFirst().evidence());
  }

  @Test
  void mixedBatchKeepsFrameTranscriptAndOcrTypesAndRealTimes() {
    var time = new SynopsisEvidence.TimeRange(1_000_000, 1_200_000);
    var frame = image("frame", new VisualImage("image/png", new byte[] {1}));
    var transcript =
        new SynopsisEvidence(
            "spoken",
            SynopsisEvidence.Kind.VIDEO_TRANSCRIPT,
            new SynopsisEvidence.Text("尾部条件"),
            time);
    var ocr =
        new SynopsisEvidence(
            "ocr", SynopsisEvidence.Kind.VIDEO_OCR, new SynopsisEvidence.Text("OCR42"), time);
    var file = new SynopsisFileInput(publication(3), List.of(frame, transcript, ocr));
    var batch = SynopsisBatch.partition(file).getFirst();
    assertEquals(file.evidence(), batch.evidence());
    assertEquals(time, batch.evidence().getLast().time());
    assertEquals(file.toBounded().fingerprint(), file.fingerprint());
  }

  @Test
  void malformedBatchCannotExceedBoundsDuplicateOrLoseParentRange() {
    var one = List.of(text("source", "text"));
    invalid(() -> new SynopsisBatch(null, SHA, 0, one));
    invalid(() -> new SynopsisBatch(publication(1), "bad", 0, one));
    invalid(() -> new SynopsisBatch(publication(1), SHA, -1, one));
    invalid(() -> new SynopsisBatch(publication(1), SHA, 1, one));
    invalid(() -> new SynopsisBatch(publication(1), SHA, 0, List.of()));
    invalid(() -> new SynopsisBatch(publication(65), SHA, 0, leaves(65, "x")));
    invalid(
        () -> new SynopsisBatch(publication(2), SHA, 0, List.of(one.getFirst(), one.getFirst())));
    invalid(
        () -> new SynopsisBatch(publication(1), SHA, 0, Arrays.asList((SynopsisEvidence) null)));
    invalid(() -> new SynopsisBatch(publication(17), SHA, 0, leaves(17, "x".repeat(4000))));
    invalid(() -> SynopsisBatch.partition(null));
  }

  @Test
  void derivedNodesAndReductionAreImmutableConsecutiveAndNotOriginalEvidence() {
    var items = new ArrayList<>(List.of(item("first")));
    var first = new DerivedSynopsisNode("first-node", 0, 8, items);
    var second = new DerivedSynopsisNode("last-node", 8, 10, List.of(item("last")));
    items.clear();
    var nodes = new ArrayList<>(List.of(first, second));
    var reduction =
        new SynopsisReductionInput(publication(10), SHA, SynopsisReductionInput.Stage.FINAL, nodes);
    nodes.clear();
    assertEquals(2, reduction.nodes().size());
    assertEquals(1, first.items().size());
    assertFalse(SynopsisEvidence.class.isAssignableFrom(DerivedSynopsisNode.class));
    assertThrows(UnsupportedOperationException.class, () -> first.items().clear());
    assertThrows(UnsupportedOperationException.class, () -> reduction.nodes().clear());
    invalid(() -> new DerivedSynopsisNode("n", 1, 1, List.of(item("a"))));
    invalid(() -> new DerivedSynopsisNode("n", 0, 1, List.of()));
    invalid(
        () ->
            new DerivedSynopsisNode(
                "n", 0, 1, IntStream.range(0, 17).mapToObj(i -> item("a")).toList()));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(10), SHA, SynopsisReductionInput.Stage.FINAL, List.of(second, first)));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(10),
                SHA,
                SynopsisReductionInput.Stage.INTERMEDIATE,
                List.of(first, new DerivedSynopsisNode("gap", 9, 10, List.of(item("a"))))));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(10),
                SHA,
                SynopsisReductionInput.Stage.INTERMEDIATE,
                List.of(first, new DerivedSynopsisNode("first-node", 8, 10, List.of(item("a"))))));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(9), SHA, SynopsisReductionInput.Stage.FINAL, List.of(first, second)));
  }

  @Test
  void reductionAdmissionHasAtMostThreeNodesAndFortyEightCandidateItems() {
    var sixteen =
        IntStream.range(0, 16)
            .mapToObj(
                i ->
                    new SynopsisDraft.Item(
                        SynopsisDraft.Section.TOPIC, "😀".repeat(1024), List.of("source")))
            .toList();
    var nodes =
        IntStream.range(0, 3)
            .mapToObj(i -> new DerivedSynopsisNode("node" + i, i, i + 1, sixteen))
            .toList();
    var input =
        new SynopsisReductionInput(publication(3), SHA, SynopsisReductionInput.Stage.FINAL, nodes);
    assertEquals(48, input.nodes().stream().mapToInt(n -> n.items().size()).sum());
    var four = new ArrayList<>(nodes);
    four.add(new DerivedSynopsisNode("four", 3, 4, sixteen));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(4), SHA, SynopsisReductionInput.Stage.FINAL, four));
    invalid(() -> new SynopsisReductionInput(publication(1), SHA, null, List.of(nodes.getFirst())));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(1),
                "invalid",
                SynopsisReductionInput.Stage.FINAL,
                List.of(nodes.getFirst())));
    invalid(
        () ->
            new SynopsisReductionInput(
                publication(1), SHA, SynopsisReductionInput.Stage.FINAL, List.of()));
  }

  @Test
  void batchReviewPreservesExplicitIncompleteAndIncompatibleJudgmentsWithoutDuplicateIndices() {
    var items =
        new ArrayList<>(
            List.of(
                new SynopsisBatchReview.ItemReview(0, true),
                new SynopsisBatchReview.ItemReview(31, false)));
    var review = new SynopsisBatchReview(false, items);
    items.clear();
    assertFalse(review.complete());
    assertEquals(2, review.items().size());
    assertFalse(review.items().getLast().compatible());
    assertThrows(UnsupportedOperationException.class, () -> review.items().clear());
    invalid(() -> new SynopsisBatchReview.ItemReview(-1, true));
    invalid(() -> new SynopsisBatchReview.ItemReview(32, true));
    invalid(() -> new SynopsisBatchReview(true, List.of()));
    invalid(
        () ->
            new SynopsisBatchReview(
                true,
                List.of(
                    new SynopsisBatchReview.ItemReview(0, true),
                    new SynopsisBatchReview.ItemReview(0, false))));
    invalid(
        () -> new SynopsisBatchReview(true, Arrays.asList((SynopsisBatchReview.ItemReview) null)));
  }

  @Test
  void hierarchicalDiagnosticStringsDoNotExposeContent() {
    var file = new SynopsisFileInput(publication(1), List.of(text("private", "private text")));
    var batch = new SynopsisBatch(file.publication(), SHA, 0, file.evidence());
    var node = new DerivedSynopsisNode("private", 0, 1, List.of(item("private")));
    var reduction =
        new SynopsisReductionInput(
            file.publication(), SHA, SynopsisReductionInput.Stage.FINAL, List.of(node));
    var row = new SynopsisBatchReview.ItemReview(0, false);
    for (var value :
        List.of(file, batch, node, reduction, row, new SynopsisBatchReview(false, List.of(row)))) {
      assertTrue(value.toString().endsWith("[redacted]"));
      assertFalse(value.toString().contains("private"));
    }
  }

  private static SynopsisEvidence text(String id, String text) {
    return new SynopsisEvidence(
        id, SynopsisEvidence.Kind.TEXT, new SynopsisEvidence.Text(text), null);
  }

  private static SynopsisEvidence image(String id, VisualImage image) {
    return new SynopsisEvidence(
        id,
        SynopsisEvidence.Kind.VIDEO_FRAME,
        new SynopsisEvidence.Image(image),
        new SynopsisEvidence.TimeRange(0, 1));
  }

  private static List<SynopsisEvidence> leaves(int count, String text) {
    return IntStream.range(0, count).mapToObj(i -> text("source" + i, text)).toList();
  }

  private static SynopsisDraft.Item item(String source) {
    return new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, "派生候选", List.of(source));
  }

  private static PublicationVersion publication(int count) {
    return new PublicationVersion(
        "document",
        "publication",
        "revision",
        "generation",
        SHA,
        "parser-v1",
        new IndexTarget("embedding", "projection", "model-v1", 2),
        "b".repeat(64),
        count);
  }

  private static void invalid(Executable operation) {
    assertEquals("invalid_request", assertThrows(ApplicationException.class, operation).code());
  }
}
