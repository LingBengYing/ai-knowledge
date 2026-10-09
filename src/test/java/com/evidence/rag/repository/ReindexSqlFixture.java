package com.evidence.rag.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Synthetic authority-only fixture. No client, model, decoder or remote projection is called. */
final class ReindexSqlFixture {
  static final Actor OWNER = new Actor("reindex-fixture", "owner");
  private static final IndexTarget TARGET =
      new IndexTarget("synthetic-embedding-v1", "b".repeat(64), "synthetic-model-v1", 2);

  private ReindexSqlFixture() {}

  static void requireVersionTwentyFour(SqliteAuthorityStore store) {
    assertEquals(
        HistoricalSchemaV25Fixture.CURRENT_VERSION,
        store.transaction(() -> store.count("PRAGMA user_version")));
  }

  static Published publish(AuthorityTestContext authority) throws Exception {
    byte[] content =
        ("First saved fact. ".repeat(400) + "Second saved fact.").getBytes(StandardCharsets.UTF_8);
    authority.uploadDocument(OWNER, "facts.txt", "text/plain", content);
    var source = authority.claimIngestion(OWNER.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(
            source, new TextParser().parse("facts.txt", "text/plain", content)));
    authority.createIndexing(OWNER, source.documentId(), TARGET);
    var claim = authority.claimIndexing(OWNER.workspaceId()).orElseThrow();
    assertTrue(authority.completeIndexing(claim, digests(claim), receipt(claim)));
    return new Published(
        source.documentId(), active(authority.store(), source.documentId()), claim);
  }

  static String queue(SqliteAuthorityStore store, Published base, int sequence) {
    String id = UUID.randomUUID().toString();
    store.transaction(
        () -> {
          insert(
              store,
              base,
              id,
              sequence,
              base.publicationId(),
              base.claim().sourceSha256(),
              base.claim().parserRevision(),
              base.claim().target());
          return null;
        });
    return id;
  }

  static void insert(
      SqliteAuthorityStore store,
      Published base,
      String id,
      Object sequence,
      String baseId,
      String sourceSha,
      String parser,
      IndexTarget target) {
    String now = Instant.now().toString();
    store.execute(
        "INSERT INTO indexing_jobs(id,document_id,revision_id,source_sha256,parser_revision,embedding_identity,projection_identity,model_revision,dimensions,state,attempt,created_by,created_at,updated_at,rebuild_sequence,base_publication_id) VALUES(?,?,?,?,?,?,?,?,?,'queued',1,?,?,?,?,?)",
        id,
        base.documentId(),
        base.claim().revisionId(),
        sourceSha,
        parser,
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        OWNER.principalId(),
        now,
        now,
        sequence,
        baseId);
  }

  static String active(SqliteAuthorityStore store, String document) {
    return store.transaction(
        () ->
            (String)
                store
                    .rows(
                        "SELECT publication_id FROM active_corpus_publications WHERE document_id=?",
                        document)
                    .getFirst()
                    .get("publication_id"));
  }

  static Map<String, String> digests(IndexClaim claim) {
    var digests = new LinkedHashMap<String, String>();
    for (var item : claim.items()) {
      digests.put(
          RetrievalProjection.physicalSegmentId(claim.projectionGenerationId(), item.evidenceId()),
          "a".repeat(64));
    }
    return Map.copyOf(digests);
  }

  static VerifiedRevision receipt(IndexClaim claim) {
    var digests = digests(claim);
    return new VerifiedRevision(
        claim.target().projectionIdentity(),
        new RetrievalProjection.RevisionManifest(
                claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests)
            .sha256(),
        digests.size());
  }

  record Published(String documentId, String publicationId, IndexClaim claim) {}
}
