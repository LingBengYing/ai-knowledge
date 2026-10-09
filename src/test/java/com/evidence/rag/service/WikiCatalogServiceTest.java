package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.repository.WikiCatalogRepository;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.support.SubtitleCorpusFixture;
import com.evidence.rag.support.SynopsisCorpusFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiCatalogServiceTest {
  @TempDir Path directory;
  private static final Actor OWNER = new Actor("org", "owner");

  @Test
  void completeFileSearchCountsBeforePaginationKeepsDuplicatesAndLiteralCaseInsensitiveTerms() {
    try (var corpus = new PublishedCorpusFixture(directory)) {
      String text = "前缀😀".repeat(750) + " Project灯塔 UNIQUE_% 灯塔";
      var first = corpus.publish(OWNER, text);
      var second = corpus.publish(OWNER, text);
      corpus.publish(new Actor("other", "owner"), text);
      var service = service(corpus.authority.store());
      var found = service.list(new Actor("org", "member"), 0, 1, "灯塔", "document");
      assertEquals(2, found.total());
      assertEquals(1, found.items().size());
      assertEquals(2, found.items().getFirst().matchCount());
      assertTrue(found.items().getFirst().excerpt().contains("灯塔"));
      assertTrue(found.items().getFirst().answerable());
      assertEquals("indexed", found.items().getFirst().state());
      var next = service.list(OWNER, 1, 1, "灯塔", "document");
      assertEquals(2, next.total());
      assertFalse(
          next.items().getFirst().documentId().equals(found.items().getFirst().documentId()));
      assertEquals(
          List.of(first.documentId(), second.documentId()).stream().sorted().toList(),
          service.list(OWNER, 0, 100, "project", "").items().stream()
              .map(i -> i.documentId())
              .sorted()
              .toList());
      assertEquals(2, service.list(OWNER, 0, 100, "_%", "").total());
      assertEquals(0, service.list(OWNER, 0, 100, "%' OR 1=1 --", "").total());
      assertEquals(0, service.list(OWNER, 0, 100, "灯塔", "image").total());
      assertEquals(0, service.list(OWNER, 20, 1, "灯塔", "").items().size());
    }
  }

  @Test
  void nameSearchListsUnpublishedSourceWithoutPretendingItsBodyIsSearchable() {
    try (var corpus = new PublishedCorpusFixture(directory)) {
      corpus
          .authority
          .ingestion()
          .uploadDocument(
              OWNER, "待解析灯塔.txt", "text/plain", "私有未发布正文".getBytes(StandardCharsets.UTF_8));
      corpus.authority.registerSyntheticDocument(
          OWNER,
          new SyntheticDocument(
              "synthetic", "图例.png", "image", "image/png", "revision", "a".repeat(64), 12),
          Map.of());
      var service = service(corpus.authority.store());
      var file = service.list(OWNER, 0, 20, "灯塔", "document").items().getFirst();
      assertFalse(file.answerable());
      assertEquals("queued", file.state());
      assertEquals("", file.excerpt());
      assertEquals(1, file.matchCount());
      assertEquals(0, service.list(OWNER, 0, 20, "私有未发布正文", "").total());
      assertEquals(1, service.list(OWNER, 0, 20, "", "image").total());
      assertThrows(
          ApplicationException.class,
          () -> service.list(new Actor("other", "owner"), 0, 20, "", ""));
      assertThrows(ApplicationException.class, () -> service.list(OWNER, 0, 101, "", ""));
      assertThrows(ApplicationException.class, () -> service.list(OWNER, 0, 20, "", "caption"));
    }
  }

  @Test
  void findsOcrAudioAndSubtitleTailButNeverVisualRecallCaptions() {
    try (var corpus = new PublishedCorpusFixture(directory)) {
      var fixtures =
          new SynopsisCorpusFixture(corpus.authority.store(), OWNER, PublishedCorpusFixture.TARGET);
      fixtures.image(false);
      fixtures.image(true);
      fixtures.audio(List.of("开头", "", "完整末尾音频灯塔"));
      fixtures.video(2);
      SubtitleCorpusFixture.publish(corpus, OWNER, 30);
      var service = service(corpus.authority.store());
      assertEquals(1, service.list(OWNER, 0, 100, "Budget42", "image").total());
      assertEquals(0, service.list(OWNER, 0, 100, "Misleading caption", "image").total());
      assertEquals(1, service.list(OWNER, 0, 100, "末尾音频灯塔", "audio").total());
      assertEquals(1, service.list(OWNER, 0, 100, "远处转录", "video").total());
      assertEquals(1, service.list(OWNER, 0, 100, "OCR42", "video").total());
      assertEquals(1, service.list(OWNER, 0, 100, "TAIL-917", "video").total());
      assertEquals(5, service.list(OWNER, 0, 100, "", "").total());
    }
  }

  private static WikiCatalogService service(SqliteAuthorityStore store) {
    return new WikiCatalogService(
        store, new WikiCatalogRepository(store), new EvidenceRepository(store), "org");
  }
}
