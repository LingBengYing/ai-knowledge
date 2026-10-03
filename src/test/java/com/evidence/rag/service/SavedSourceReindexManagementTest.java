package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.dto.DocumentResult;
import com.evidence.rag.model.query.DocumentQuery;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.web.converter.ManagementResponseMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedSourceReindexManagementTest {
  @TempDir Path directory;

  @Test
  void serverEligibilityAndMappedControlsTrackPendingWorkWithoutChangingTheOldPublication() {
    var owner = new Actor("org", "owner");
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var original = fixture.publish(owner, "合成资料。完整保存后才允许明确重建。");
      var store = fixture.authority.store();
      var repository = new ManagementRepository(store);
      var management =
          new ManagementService(
              store,
              repository,
              new IngestionRepository(store),
              new IndexingRepository(store),
              new DocumentPermissionPolicy(),
              true);
      var old = document(management, owner);
      assertTrue(old.canReindex());
      assertFalse(old.canIndex());
      assertTrue(ManagementResponseMapper.document(old).canReindex());
      assertFalse(
          document(fixture.authority.management(), owner).canReindex(),
          "Legacy construction stays opt-in");
      var queued =
          fixture
              .authority
              .indexing()
              .createReindexing(owner, original.documentId(), old.indexPublicationId(), TARGET);
      var pending = document(management, owner);
      assertFalse(pending.canReindex());
      assertEquals(old.indexPublicationId(), pending.indexPublicationId());
      assertEquals(old.activeRevisionId(), pending.activeRevisionId());
      assertEquals(queued.taskId(), pending.latestIndexJob().taskId());
      fixture.authority.indexing().cancelIndexing(owner, queued.taskId());
      assertTrue(document(management, owner).canReindex());
      store.transaction(
          () -> {
            repository.insertGrant(original.documentId(), "reader", "reader");
            return null;
          });
      assertFalse(document(management, new Actor("org", "reader")).canReindex());
    }
  }

  private static DocumentResult document(ManagementService management, Actor actor) {
    return management
        .listDocuments(actor, new DocumentQuery("", null, null, null, null, "updated_desc", 1, 20))
        .items()
        .getFirst();
  }
}
