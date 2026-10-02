package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.HierarchicalSynopsisModels;
import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisBatchReview;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.SynopsisMaterialRepository;
import com.evidence.rag.repository.SynopsisRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.SubtitleCorpusFixture;
import com.evidence.rag.support.VideoSubtitleCompilationFixture;
import com.evidence.rag.web.converter.SynopsisResponseMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SynopsisSubtitleLibraryTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(ints = {2, 65})
  void shortAndHierarchicalTasksIncludeEverySubtitleAndReadTailAfterRestart(int cueCount)
      throws Exception {
    String documentId;
    String synopsisId;
    String fingerprint;
    var models = new Models();
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var publication = SubtitleCorpusFixture.publish(fixture, OWNER, cueCount);
      documentId = publication.documentId();
      var input =
          store.transaction(
              () -> new SynopsisMaterialRepository(store).document(OWNER, publication));
      assertEquals(cueCount + 3, input.evidence().size());
      assertEquals(
          cueCount,
          input.evidence().stream()
              .filter(e -> e.kind() == SynopsisEvidence.Kind.VIDEO_SUBTITLE)
              .count());
      assertEquals(
          SubtitleCorpusFixture.TAIL,
          ((SynopsisEvidence.Text) input.evidence().getLast().content()).text());
      models.expected = input;
      var library = library(store, models);
      var processor =
          new SynopsisTaskProcessor(
              library,
              new SynopsisService(models, Duration.ofSeconds(10)),
              new HierarchicalSynopsisService(models, Duration.ofSeconds(10)),
              OWNER.workspaceId());
      synopsisId = processor.create(OWNER, documentId).taskId();
      var claim = processor.claim().orElseThrow();
      assertEquals(input.publication(), claim.input().publication());
      assertEquals(input.fingerprint(), claim.input().fingerprint());
      assertEquals(
          input.evidence().stream().map(SynopsisEvidence::id).toList(),
          claim.input().evidence().stream().map(SynopsisEvidence::id).toList());
      assertEquals(
          input.evidence().stream().map(SynopsisEvidence::sha256).toList(),
          claim.input().evidence().stream().map(SynopsisEvidence::sha256).toList());
      processor.process(claim);
      assertEquals("available", library.task(OWNER, synopsisId).state());
      assertEquals(input.evidence().stream().map(SynopsisEvidence::id).toList(), models.drafted);
      if (cueCount == 65) {
        assertEquals(models.drafted, models.reviewed);
        assertEquals(HierarchicalSynopsisService.POLICY_REVISION, claim.policyRevision());
      } else {
        assertEquals(SynopsisService.POLICY_REVISION, claim.policyRevision());
      }
      var result = library.get(OWNER, documentId);
      fingerprint = result.synopsis().inputFingerprint();
      assertEquals(input.fingerprint(), fingerprint);
      assertSource(library, synopsisId, cueCount);
      assertEquals(cueCount + 3, count("SELECT input_count FROM synopsis_tasks"));
      assertEquals(
          cueCount,
          count(
              "SELECT COUNT(*) FROM synopsis_input_evidence WHERE kind='VIDEO_SUBTITLE' AND video_subtitle_cue_id IS NOT NULL"));
      assertEquals(0, count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var library = library(store, models);
      assertEquals(fingerprint, library.get(OWNER, documentId).synopsis().inputFingerprint());
      assertSource(library, synopsisId, cueCount);
      assertTrue(library.claim(OWNER.workspaceId()).isEmpty());
    }
  }

  @Test
  void sourceMaterialKeepsIndependentTypeOriginalPacketAndFullTrackIdentity() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var store = fixture.authority.store();
      var compilation = VideoSubtitleCompilationFixture.compilation(true);
      var publication = SubtitleCorpusFixture.publish(fixture, OWNER, compilation);
      var repository = new SynopsisMaterialRepository(store);
      var input = store.transaction(() -> repository.document(OWNER, publication));
      assertEquals(
          List.of(
              SynopsisEvidence.Kind.VIDEO_FRAME,
              SynopsisEvidence.Kind.VIDEO_FRAME,
              SynopsisEvidence.Kind.VIDEO_OCR,
              SynopsisEvidence.Kind.VIDEO_SUBTITLE,
              SynopsisEvidence.Kind.VIDEO_SUBTITLE,
              SynopsisEvidence.Kind.VIDEO_SUBTITLE),
          input.evidence().stream().map(SynopsisEvidence::kind).toList());
      var item = input.evidence().get(3);
      var reference =
          new FileSynopsis.Reference(item.id(), item.sha256(), item.kind(), item.time());
      var material = store.transaction(() -> repository.source(OWNER, publication, reference));
      var locator =
          assertInstanceOf(SynopsisSourceMaterial.VideoSubtitle.class, material.locator());
      assertEquals("\n预算😀42万元。\n补充条件：须经审批。\n", locator.subtitle().trackText());
      assertEquals(1, locator.subtitle().source().startOffset());
      assertEquals(1, locator.subtitle().source().cue().ordinal());
      assertNotEquals(item.sha256(), locator.subtitle().source().cue().payloadSha256());
      assertNull(material.frame());
      assertThrows(
          RuntimeException.class,
          () ->
              new SynopsisSourceMaterial(
                  item, material.filename(), material.mediaType(), locator, new byte[] {1}, null));
      assertThrows(
          RuntimeException.class,
          () ->
              new SynopsisSourceMaterial(
                  new SynopsisEvidence(
                      item.id(),
                      SynopsisEvidence.Kind.VIDEO_TRANSCRIPT,
                      item.content(),
                      item.time()),
                  material.filename(),
                  material.mediaType(),
                  locator,
                  material.content(),
                  null));
      var outsider = new Actor(OWNER.workspaceId(), "outsider");
      assertThrows(
          RuntimeException.class,
          () -> store.transaction(() -> repository.source(outsider, publication, reference)));
    }
  }

  private static void assertSource(SynopsisLibraryService library, String id, int cueCount) {
    var source = library.source(OWNER, id, 3, 0);
    assertNull(source.frame());
    assertArrayEquals(VideoSubtitleCompilationFixture.ORIGINAL, source.content());
    var json =
        JsonMapper.builder().build().valueToTree(SynopsisResponseMapper.source(id, 4, 1, source));
    assertEquals("video_subtitle", json.path("kind").asString());
    assertEquals("embedded_subtitle", json.path("proof_origin").asString());
    assertEquals("subtitle_cue", json.path("time_precision").asString());
    assertEquals(SubtitleCorpusFixture.TAIL, json.path("text").asString());
    assertTrue(json.path("frame_url").isNull());
    assertEquals(cueCount - 1, json.path("locator").path("cue_ordinal").asInt());
    assertEquals(
        500000 + (cueCount - 1) * 1000000L, json.path("locator").path("start_us").asLong());
    assertEquals(1250000 + (cueCount - 1) * 1000000L, json.path("locator").path("end_us").asLong());
    assertEquals(
        VideoSubtitleCompilation.TEXT_FORMAT, json.path("locator").path("text_format").asString());
    assertFalse(json.path("locator").has("frame_id"));
    assertFalse(json.path("locator").has("span_id"));
    assertFalse(json.path("locator").has("page"));
  }

  private static SynopsisLibraryService library(SqliteAuthorityStore store, Models models) {
    return new SynopsisLibraryService(
        store,
        new SynopsisRepository(store),
        new SynopsisMaterialRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        models::revision,
        models::revision);
  }

  private long count(String sql) throws Exception {
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  /** Deterministic protocol-free model adapter verifies routing, not real model quality. */
  private static final class Models implements SynopsisModels, HierarchicalSynopsisModels {
    SynopsisFileInput expected;
    final List<String> drafted = new ArrayList<>();
    final List<String> reviewed = new ArrayList<>();

    private List<SynopsisDraft.Item> items(SynopsisEvidence source) {
      return List.of(
              SynopsisDraft.Section.OVERVIEW,
              SynopsisDraft.Section.TOPIC,
              SynopsisDraft.Section.TERM,
              SynopsisDraft.Section.TIMELINE)
          .stream()
          .map(
              section ->
                  new SynopsisDraft.Item(section, SubtitleCorpusFixture.TAIL, List.of(source.id())))
          .toList();
    }

    public SynopsisDraft draft(SynopsisInput input) {
      drafted.addAll(input.evidence().stream().map(SynopsisEvidence::id).toList());
      assertEquals(expected.fingerprint(), input.fingerprint());
      assertEquals(
          expected.evidence().stream().map(SynopsisEvidence::id).toList(),
          input.evidence().stream().map(SynopsisEvidence::id).toList());
      return new SynopsisDraft(false, items(input.evidence().getLast()));
    }

    public SynopsisDraft draftLeaf(SynopsisBatch batch) {
      drafted.addAll(batch.evidence().stream().map(SynopsisEvidence::id).toList());
      return new SynopsisDraft(false, items(batch.evidence().getLast()));
    }

    public SynopsisDraft reduce(SynopsisReductionInput input) {
      return new SynopsisDraft(false, items(expected.evidence().getLast()));
    }

    public SynopsisBatchReview review(SynopsisBatch batch, List<SynopsisDraft.Item> items) {
      reviewed.addAll(batch.evidence().stream().map(SynopsisEvidence::id).toList());
      return new SynopsisBatchReview(
          true,
          IntStream.range(0, items.size())
              .mapToObj(i -> new SynopsisBatchReview.ItemReview(i, true))
              .toList());
    }

    public boolean verify(SynopsisDraft.Item item, List<SynopsisEvidence> evidence) {
      assertEquals(List.of(expected.evidence().getLast()), evidence);
      assertEquals(SubtitleCorpusFixture.TAIL, item.text());
      return true;
    }

    public String revision() {
      return "subtitle-synopsis-fixture-v1";
    }
  }
}
