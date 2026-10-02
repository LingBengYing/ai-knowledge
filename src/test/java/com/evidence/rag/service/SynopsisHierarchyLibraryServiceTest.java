package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.HierarchicalSynopsisModels;
import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisBatchReview;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.SynopsisCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real authority publications exercise the complete long-file task, result and source path. */
class SynopsisHierarchyLibraryServiceTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("embedding", "b".repeat(64), "model-v1", 2);
  @TempDir Path directory;

  @Test
  void longTextPersistsCompleteFingerprintAndReadsTheLastConditionAfterRestart() {
    String original = "中间记录。".repeat(16000) + "末尾条件：先审核，否则预算为0。";
    String documentId;
    String synopsisId;
    String fingerprint;
    var shortModels = new ShortModels();
    var hierarchy = new HierarchyModels();
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication = corpus(store).text(original);
      var input = input(store, publication);
      assertTrue(input.evidence().size() > 64);
      hierarchy.prepare(input, List.of(input.evidence().getLast()));
      var library = library(store, shortModels, hierarchy);
      synopsisId = generate(library, shortModels, hierarchy, input);
      documentId = publication.documentId();
      fingerprint = input.fingerprint();
      var source = library.source(OWNER, synopsisId, 0, 0);
      assertTrue(((SynopsisEvidence.Text) source.evidence().content()).text().contains("否则预算为0"));
      assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), source.content());
      assertEquals(0, shortModels.drafts);
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var library = library(store, shortModels, hierarchy);
      assertEquals(synopsisId, library.get(OWNER, documentId).synopsisId());
      assertEquals(fingerprint, library.get(OWNER, documentId).synopsis().inputFingerprint());
      assertArrayEquals(
          original.getBytes(StandardCharsets.UTF_8),
          library.source(OWNER, synopsisId, 2, 0).content());
      assertTrue(library.claim(OWNER.workspaceId()).isEmpty());
    }
  }

  @Test
  void largeAudioKeepsEverySpanAndReturnsTheLastRealTimeAndOriginalAudio() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication =
          corpus(store)
              .audio(IntStream.range(0, 17).mapToObj(i -> "字".repeat(3990) + "尾部" + i).toList());
      var input = input(store, publication);
      assertFalse(input.bounded());
      assertEquals(17, input.evidence().size());
      var shortModels = new ShortModels();
      var hierarchy = new HierarchyModels();
      hierarchy.prepare(input, List.of(input.evidence().getLast()));
      var library = library(store, shortModels, hierarchy);
      var id = generate(library, shortModels, hierarchy, input);
      var source = library.source(OWNER, id, 3, 0);
      var locator = (SynopsisSourceMaterial.Audio) source.locator();
      assertEquals(16, locator.spanOrdinal());
      assertEquals(new SynopsisEvidence.TimeRange(16_000_000, 17_000_000), locator.time());
      assertTrue(((SynopsisEvidence.Text) source.evidence().content()).text().endsWith("尾部16"));
      assertEquals(
          publication.sourceSha256(),
          com.evidence.rag.model.domain.ModelValues.sha256(source.content()));
      assertEquals(0, shortModels.drafts);
    }
  }

  @Test
  void nineFrameVideoKeepsLastFrameTranscriptAndOcrAsTypedOriginalSources() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication = corpus(store).video(9);
      var input = input(store, publication);
      var sources =
          List.of(input.evidence().get(8), input.evidence().get(17), input.evidence().get(18));
      assertEquals(19, input.evidence().size());
      var shortModels = new ShortModels();
      var hierarchy = new HierarchyModels();
      hierarchy.prepare(input, sources);
      var library = library(store, shortModels, hierarchy);
      var id = generate(library, shortModels, hierarchy, input);
      for (int i = 0; i < sources.size(); i++) {
        var material = library.source(OWNER, id, 3, i);
        assertEquals(sources.get(i).id(), material.evidence().id());
        assertEquals(sources.get(i).sha256(), material.evidence().sha256());
        assertArrayEquals(SynopsisCorpusFixture.ORIGINAL_VIDEO, material.content());
      }
      var frame = library.source(OWNER, id, 3, 0);
      assertEquals(8, ((SynopsisSourceMaterial.VideoFrame) frame.locator()).frameOrdinal());
      assertArrayEquals(
          ((SynopsisEvidence.Image) sources.getFirst().content()).image().content(),
          frame.frame().content());
      assertEquals(
          8,
          ((SynopsisSourceMaterial.VideoTranscript) library.source(OWNER, id, 3, 1).locator())
              .spanOrdinal());
      assertEquals(
          "OCR42",
          ((SynopsisEvidence.Text) library.source(OWNER, id, 3, 2).evidence().content()).text());
      assertEquals(0, shortModels.drafts);
    }
  }

  @Test
  void originalShortProfileAndResultStayReadableWhenOnlyHierarchyModelChanges() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication = corpus(store).text("短文件旧结果。");
      var shortModels = new ShortModels();
      var oldLibrary =
          new SynopsisLibraryService(
              store,
              new SynopsisRepository(store),
              new SynopsisMaterialRepository(store),
              new ManagementRepository(store),
              new DocumentPermissionPolicy(),
              shortModels::revision);
      var processor =
          new SynopsisTaskProcessor(
              oldLibrary,
              new SynopsisService(shortModels, Duration.ofSeconds(10)),
              OWNER.workspaceId());
      var id = processor.create(OWNER, publication.documentId()).taskId();
      var claim = processor.claim().orElseThrow();
      processor.process(claim);
      var expected = oldLibrary.get(OWNER, publication.documentId()).synopsis();
      var hierarchy = new HierarchyModels();
      var library = library(store, shortModels, hierarchy);
      hierarchy.revision.set("hierarchy-model-v2");
      assertEquals(expected, library.get(OWNER, publication.documentId()).synopsis());
      assertEquals(id, library.create(OWNER, publication.documentId()).taskId());
      assertEquals(SynopsisService.POLICY_REVISION, expected.policyRevision());
      assertEquals(shortModels.revision(), expected.modelRevision());
      assertEquals(1, shortModels.drafts);
      assertTrue(hierarchy.drafted.isEmpty());
    }
  }

  @Test
  void hierarchyRevisionChangeFencesLongClaimsAndResultsButNotShortModelChanges() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication = corpus(store).text("长文。".repeat(27000));
      var input = input(store, publication);
      var shortModels = new ShortModels();
      var hierarchy = new HierarchyModels();
      hierarchy.prepare(input, List.of(input.evidence().getLast()));
      var library = library(store, shortModels, hierarchy);
      var first = library.create(OWNER, publication.documentId());
      var claim = library.claim(OWNER.workspaceId()).orElseThrow();
      assertEquals(HierarchicalSynopsisService.POLICY_REVISION, claim.policyRevision());
      shortModels.revision.set("short-model-v2");
      assertTrue(library.current(claim));
      hierarchy.revision.set("hierarchy-model-v2");
      assertFalse(library.current(claim));
      assertTrue(library.fail(claim, "configuration_changed"));
      var next = generate(library, shortModels, hierarchy, input);
      assertNotEquals(first.taskId(), next);
      hierarchy.revision.set("hierarchy-model-v3");
      assertThrows(ApplicationException.class, () -> library.get(OWNER, publication.documentId()));
      assertThrows(ApplicationException.class, () -> library.task(OWNER, next));
      assertThrows(ApplicationException.class, () -> library.source(OWNER, next, 0, 0));
    }
  }

  private static String generate(
      SynopsisLibraryService library,
      ShortModels shortModels,
      HierarchyModels hierarchy,
      SynopsisFileInput input) {
    var processor =
        new SynopsisTaskProcessor(
            library,
            new SynopsisService(shortModels, Duration.ofSeconds(10)),
            new HierarchicalSynopsisService(hierarchy, Duration.ofSeconds(10)),
            OWNER.workspaceId());
    var task = processor.create(OWNER, input.publication().documentId());
    assertEquals(task.taskId(), processor.create(OWNER, input.publication().documentId()).taskId());
    var claim = processor.claim().orElseThrow();
    assertEquals(input.publication(), claim.publication());
    assertEquals(input.fingerprint(), claim.input().fingerprint());
    assertEquals(input.evidence().size(), claim.input().evidence().size());
    assertEquals(hierarchy.revision(), claim.modelRevision());
    assertEquals(HierarchicalSynopsisService.POLICY_REVISION, claim.policyRevision());
    processor.process(claim);
    assertEquals("available", library.task(OWNER, task.taskId()).state());
    var result = library.get(OWNER, input.publication().documentId()).synopsis();
    assertEquals(input.fingerprint(), result.inputFingerprint());
    assertEquals(input.evidence().stream().map(SynopsisEvidence::id).toList(), hierarchy.drafted);
    assertEquals(hierarchy.drafted, hierarchy.reviewed);
    assertEquals(result.entries().size(), hierarchy.verifications);
    assertFalse(processor.isCurrent(claim));
    assertTrue(processor.claim().isEmpty());
    int before = hierarchy.drafted.size();
    processor.process(claim);
    assertEquals(before, hierarchy.drafted.size());
    return task.taskId();
  }

  private static SynopsisLibraryService library(
      SqliteAuthorityStore store, ShortModels shortModels, HierarchyModels hierarchy) {
    return new SynopsisLibraryService(
        store,
        new SynopsisRepository(store),
        new SynopsisMaterialRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        shortModels::revision,
        hierarchy::revision);
  }

  private static SynopsisCorpusFixture corpus(SqliteAuthorityStore store) {
    return new SynopsisCorpusFixture(store, OWNER, TARGET);
  }

  private static SynopsisFileInput input(
      SqliteAuthorityStore store, PublicationVersion publication) {
    return store.transaction(
        () -> new SynopsisMaterialRepository(store).document(OWNER, publication));
  }

  private static List<SynopsisDraft.Item> items(List<SynopsisEvidence> sources) {
    var result = new ArrayList<SynopsisDraft.Item>();
    var ids = sources.stream().map(SynopsisEvidence::id).toList();
    for (var section :
        List.of(
            SynopsisDraft.Section.OVERVIEW,
            SynopsisDraft.Section.TOPIC,
            SynopsisDraft.Section.TERM)) {
      result.add(new SynopsisDraft.Item(section, "合成夹具内容与末尾条件。", ids));
    }
    if (sources.stream().allMatch(source -> source.time() != null)) {
      result.add(new SynopsisDraft.Item(SynopsisDraft.Section.TIMELINE, "该真实时间范围包含所列来源。", ids));
    }
    return result;
  }

  private static final class ShortModels implements SynopsisModels {
    private final AtomicReference<String> revision = new AtomicReference<>("short-model-v1");
    private int drafts;

    @Override
    public SynopsisDraft draft(SynopsisInput input) {
      drafts++;
      return new SynopsisDraft(false, items(List.of(input.evidence().getLast())));
    }

    @Override
    public boolean verify(SynopsisDraft.Item item, List<SynopsisEvidence> evidence) {
      return true;
    }

    @Override
    public String revision() {
      return revision.get();
    }
  }

  private static final class HierarchyModels implements HierarchicalSynopsisModels {
    private final AtomicReference<String> revision = new AtomicReference<>("hierarchy-model-v1");
    private final List<String> drafted = new ArrayList<>();
    private final List<String> reviewed = new ArrayList<>();
    private SynopsisFileInput expected;
    private List<SynopsisDraft.Item> finalItems;
    private int verifications;

    void prepare(SynopsisFileInput input, List<SynopsisEvidence> sources) {
      expected = input;
      finalItems = items(sources);
    }

    @Override
    public SynopsisDraft draftLeaf(SynopsisBatch batch) {
      assertEquals(expected.publication(), batch.publication());
      assertEquals(expected.fingerprint(), batch.inputFingerprint());
      drafted.addAll(batch.evidence().stream().map(SynopsisEvidence::id).toList());
      var lastByKind = new LinkedHashMap<SynopsisEvidence.Kind, SynopsisEvidence>();
      batch.evidence().forEach(source -> lastByKind.put(source.kind(), source));
      return new SynopsisDraft(
          false,
          lastByKind.values().stream()
              .map(
                  source ->
                      new SynopsisDraft.Item(
                          SynopsisDraft.Section.TOPIC, "未证明的合成候选。", List.of(source.id())))
              .toList());
    }

    @Override
    public SynopsisDraft reduce(SynopsisReductionInput input) {
      return new SynopsisDraft(false, finalItems);
    }

    @Override
    public SynopsisBatchReview review(SynopsisBatch batch, List<SynopsisDraft.Item> items) {
      reviewed.addAll(batch.evidence().stream().map(SynopsisEvidence::id).toList());
      return new SynopsisBatchReview(
          true,
          IntStream.range(0, items.size())
              .mapToObj(i -> new SynopsisBatchReview.ItemReview(i, true))
              .toList());
    }

    @Override
    public boolean verify(SynopsisDraft.Item item, List<SynopsisEvidence> evidence) {
      verifications++;
      assertEquals(item.evidenceIds(), evidence.stream().map(SynopsisEvidence::id).toList());
      for (var source : evidence) {
        assertEquals(
            expected.evidence().stream()
                .filter(e -> e.id().equals(source.id()))
                .findFirst()
                .orElseThrow()
                .sha256(),
            source.sha256());
      }
      return true;
    }

    @Override
    public String revision() {
      return revision.get();
    }
  }
}
