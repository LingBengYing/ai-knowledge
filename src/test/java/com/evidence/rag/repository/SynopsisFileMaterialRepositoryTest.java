package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisSourceMaterial;
import com.evidence.rag.support.SynopsisCorpusFixture;
import com.evidence.rag.support.VideoCompilationFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Complete long-file material over real immutable SQLite publications, without a vector query. */
class SynopsisFileMaterialRepositoryTest {
  private static final Actor OWNER = new Actor("org-main", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("embedding", "b".repeat(64), "model-v1", 2);
  @TempDir Path directory;

  @Test
  void longTextContainsTheFinalConditionAndUsesOneRealPublicationAcrossAllBatches() {
    try (var store = new SqliteAuthorityStore(directory)) {
      String text = "早期预算为650。" + "中间记录。".repeat(16000) + "最后约束：650预算仅在审核通过后生效，否则预算为0。";
      var publication = new SynopsisCorpusFixture(store, OWNER, TARGET).text(text);
      var repository = new SynopsisMaterialRepository(store);
      var file = store.transaction(() -> repository.document(OWNER, publication));
      assertTrue(file.evidence().size() > 64);
      assertFalse(file.bounded());
      assertEquals(publication.segmentCount(), file.evidence().size());
      assertTrue(
          ((SynopsisEvidence.Text) file.evidence().getLast().content()).text().contains("否则预算为0"));
      var batches = SynopsisBatch.partition(file);
      assertEquals(file.evidence(), batches.stream().flatMap(b -> b.evidence().stream()).toList());
      assertTrue(batches.stream().allMatch(b -> b.publication().equals(publication)));
      assertEquals(
          file.fingerprint(),
          store.transaction(() -> repository.document(OWNER, publication)).fingerprint());
      var tail = file.evidence().getLast();
      var source =
          store.transaction(
              () ->
                  repository.source(
                      OWNER,
                      publication,
                      new FileSynopsis.Reference(
                          tail.id(), tail.sha256(), tail.kind(), tail.time())));
      assertTrue(((SynopsisEvidence.Text) source.evidence().content()).text().contains("否则预算为0"));
      assertEquals(
          "input_capacity_exceeded",
          assertThrows(
                  ApplicationException.class,
                  () -> store.transaction(() -> repository.load(OWNER, publication)))
              .code());
    }
  }

  @Test
  void largeTranscriptSplitsByCharactersAndKeepsTheFinalSpanOriginalTime() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var texts = IntStream.range(0, 17).mapToObj(i -> "字".repeat(3990) + "尾部" + i).toList();
      var publication = new SynopsisCorpusFixture(store, OWNER, TARGET).audio(texts);
      var repository = new SynopsisMaterialRepository(store);
      var file = store.transaction(() -> repository.document(OWNER, publication));
      assertFalse(file.bounded());
      assertEquals(2, SynopsisBatch.partition(file).size());
      var tail = file.evidence().getLast();
      assertEquals(texts.getLast(), ((SynopsisEvidence.Text) tail.content()).text());
      assertEquals(new SynopsisEvidence.TimeRange(16_000_000, 17_000_000), tail.time());
      var source =
          store.transaction(
              () ->
                  repository.source(
                      OWNER,
                      publication,
                      new FileSynopsis.Reference(
                          tail.id(), tail.sha256(), tail.kind(), tail.time())));
      assertEquals(16, ((SynopsisSourceMaterial.Audio) source.locator()).spanOrdinal());
    }
  }

  @Test
  void nineFrameMixedVideoRetainsFinalFrameTranscriptsOcrAndOriginalPixels() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var publication = new SynopsisCorpusFixture(store, OWNER, TARGET).video(9);
      var repository = new SynopsisMaterialRepository(store);
      var file = store.transaction(() -> repository.document(OWNER, publication));
      assertFalse(file.bounded());
      assertEquals(19, file.evidence().size());
      assertEquals(
          9,
          file.evidence().stream()
              .filter(e -> e.kind() == SynopsisEvidence.Kind.VIDEO_FRAME)
              .count());
      assertEquals(
          9,
          file.evidence().stream()
              .filter(e -> e.kind() == SynopsisEvidence.Kind.VIDEO_TRANSCRIPT)
              .count());
      assertEquals(SynopsisEvidence.Kind.VIDEO_OCR, file.evidence().getLast().kind());
      assertEquals(
          file.evidence(),
          SynopsisBatch.partition(file).stream().flatMap(b -> b.evidence().stream()).toList());
      var lastFrame = file.evidence().get(8);
      var source =
          store.transaction(
              () ->
                  repository.source(
                      OWNER,
                      publication,
                      new FileSynopsis.Reference(
                          lastFrame.id(), lastFrame.sha256(), lastFrame.kind(), lastFrame.time())));
      assertEquals(8, ((SynopsisSourceMaterial.VideoFrame) source.locator()).frameOrdinal());
      assertArrayEquals(VideoCompilationFixture.image().content(), source.frame().content());
      assertArrayEquals(SynopsisCorpusFixture.ORIGINAL_VIDEO, source.content());
    }
  }

  @Test
  void shortCompatibilityAndCurrentAuthorizationStayUnchanged() {
    try (var store = new SqliteAuthorityStore(directory)) {
      var fixture = new SynopsisCorpusFixture(store, OWNER, TARGET);
      var repository = new SynopsisMaterialRepository(store);
      for (var publication :
          List.of(
              fixture.text("短文😀"),
              fixture.image(false),
              fixture.image(true),
              fixture.audio(List.of("音频")),
              fixture.video(2))) {
        var before = store.transaction(() -> repository.load(OWNER, publication));
        var file = store.transaction(() -> repository.document(OWNER, publication));
        assertTrue(file.bounded());
        assertEquals(before.fingerprint(), file.fingerprint());
        assertEquals(
            before.evidence().stream().map(SynopsisEvidence::id).toList(),
            file.evidence().stream().map(SynopsisEvidence::id).toList());
        assertEquals(
            before.evidence().stream().map(SynopsisEvidence::sha256).toList(),
            file.evidence().stream().map(SynopsisEvidence::sha256).toList());
      }
      var privateFile = fixture.text("私有");
      assertTrue(
          store
              .transaction(
                  () -> repository.document(new Actor(OWNER.workspaceId(), "member"), privateFile))
              .bounded());
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      store.transaction(
                          () ->
                              repository.document(
                                  new Actor("other-workspace", "outsider"), privateFile)))
              .code());
      store.transaction(
          () -> {
            store.execute(
                "INSERT INTO document_tombstones VALUES(?,?,?,?)",
                privateFile.documentId(),
                OWNER.workspaceId(),
                OWNER.principalId(),
                "2026-10-08T00:00:00Z");
            return null;
          });
      assertEquals(
          "not_found",
          assertThrows(
                  ApplicationException.class,
                  () -> store.transaction(() -> repository.document(OWNER, privateFile)))
              .code());
    }
  }
}
