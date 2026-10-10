package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WikiCatalogRepositoryTest {
  @TempDir Path directory;
  private static final Actor OWNER = new Actor("org", "owner");

  @Test
  void rebuiltRevisionIsListedOnceWithItsLatestIndexingState() {
    try (var corpus = new PublishedCorpusFixture(directory)) {
      var claim = corpus.publish(OWNER, "重建后的资料只出现一次");
      var store = corpus.authority.store();
      var catalog = new WikiCatalogRepository(store);
      store.transaction(
          () -> {
            var publication =
                store
                    .rows(
                        "SELECT id FROM index_publications WHERE document_id=?", claim.documentId())
                    .getFirst()
                    .get("id");
            store.execute(
                "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at,rebuild_sequence,base_publication_id) SELECT 'rebuild-job',document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,'queued',1,created_by,'2999-01-01T00:00:00Z','2999-01-01T00:00:00Z',1,? FROM indexing_jobs WHERE id=?",
                publication,
                claim.jobId());
            return null;
          });
      var documents = store.transaction(() -> catalog.documents(OWNER, ""));
      assertEquals(1, documents.size());
      assertEquals("queued", documents.getFirst().state());
    }
  }
}
