package com.evidence.rag.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Real authority publication setup, with explicit deterministic projection verification evidence.
 */
public final class PublishedCorpusFixture implements AutoCloseable {
  public static final IndexTarget TARGET =
      new IndexTarget("embed-fixture", "b".repeat(64), "embed-v1", 2);
  public final AuthorityTestContext authority;
  public final EvidenceService evidence;

  public PublishedCorpusFixture(Path directory) {
    authority = new AuthorityTestContext(directory);
    evidence =
        new EvidenceService(
            authority.store(),
            new EvidenceRepository(authority.store()),
            new ManagementRepository(authority.store()),
            new DocumentPermissionPolicy());
  }

  public IndexClaim publish(Actor actor, String text) {
    return publish(actor, text, TARGET);
  }

  public IndexClaim publish(Actor actor, String text, IndexTarget target) {
    authority
        .ingestion()
        .uploadDocument(actor, "fixture.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    var parsed = authority.ingestion().claimIngestion(actor.workspaceId()).orElseThrow();
    assertTrue(
        authority
            .ingestion()
            .completeIngestion(
                parsed, new TextParser().parse("fixture.txt", "text/plain", parsed.content())));
    authority.indexing().createIndexing(actor, parsed.documentId(), target);
    var claim = authority.indexing().claimIndexing(actor.workspaceId()).orElseThrow();
    var digests = new LinkedHashMap<String, String>();
    for (String physicalId : physicalIds(claim)) {
      digests.put(physicalId, "a".repeat(64));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            actor.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
    assertTrue(
        authority
            .indexing()
            .completeIndexing(
                claim,
                digests,
                new VerifiedRevision(
                    target.projectionIdentity(), manifest.sha256(), digests.size())));
    return claim;
  }

  public static List<String> physicalIds(IndexClaim claim) {
    return claim.items().stream()
        .map(
            segment ->
                RetrievalProjection.physicalSegmentId(
                    claim.projectionGenerationId(), segment.evidenceId()))
        .toList();
  }

  @Override
  public void close() {
    authority.close();
  }
}
