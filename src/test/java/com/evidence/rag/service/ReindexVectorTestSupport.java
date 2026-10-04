package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexClaim;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.model.domain.ReindexVectorVerification;
import com.evidence.rag.model.domain.VerifiedReindexVectors;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/** Authority unit fixture. HTTP tests independently execute the actual remote verifier. */
final class ReindexVectorTestSupport {
  private ReindexVectorTestSupport() {}

  static IndexingService service(AnswerTestContext context) {
    return service(context, (route, target) -> true);
  }

  static IndexingService service(
      AnswerTestContext context, BiPredicate<String, IndexTarget> targets) {
    var store = context.authority.store();
    return new IndexingService(
        store,
        new IndexingRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        targets);
  }

  static IndexClaim claim(AnswerTestContext context, IndexingService service, String document) {
    var store = context.authority.store();
    String base =
        store.transaction(
            () -> new IndexingRepository(store).activePublication(document).orElseThrow().id());
    service.createReindexing(context.owner, document, base, context.target);
    return service.claimIndexing(context.owner.workspaceId()).orElseThrow();
  }

  static void rebuild(AnswerTestContext context, String document) {
    var service = service(context);
    var claim = claim(context, service, document);
    var plan = service.reindexVectorPlan(claim).orElseThrow();
    assertTrue(complete(context, service, claim, verified(plan)));
  }

  static VerifiedReindexVectors verified(ReindexVectorPlan plan) {
    var values = new ArrayList<ReindexVectorVerification>();
    for (var binding : plan.images()) {
      var origin = binding.origin();
      values.add(
          new ReindexVectorVerification(
              "image",
              origin.id(),
              origin.target(),
              new VerifiedRevision(
                  origin.target().projectionIdentity(), origin.manifestSha256(), 1)));
    }
    for (var binding : plan.audios()) {
      var origin = binding.origin();
      values.add(
          new ReindexVectorVerification(
              "audio",
              origin.id(),
              origin.target(),
              new VerifiedRevision(
                  origin.target().projectionIdentity(),
                  origin.manifestSha256(),
                  origin.entries().size())));
    }
    return new VerifiedReindexVectors(plan, values);
  }

  static boolean complete(
      AnswerTestContext context,
      IndexingService service,
      IndexClaim claim,
      VerifiedReindexVectors vectors) {
    var entries =
        claim.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), item.evidenceId()),
                        claim.workspaceId(),
                        claim.documentId(),
                        claim.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    context.projection.data.initialize();
    context.projection.data.upsert(entries);
    var digests = new TreeMap<String, String>();
    entries.forEach(
        entry -> digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry)));
    var manifest =
        new RetrievalProjection.RevisionManifest(
            claim.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
    return service.completeIndexing(
        claim, digests, context.projection.data.verify(manifest), vectors);
  }
}
