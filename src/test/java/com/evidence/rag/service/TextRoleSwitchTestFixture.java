package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TextIndexAnchor;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.repository.EvidenceRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.AuthorityTestContext;
import com.evidence.rag.tool.parser.TextParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Temporary real authority plus recording providers with genuine complete configuration hashes. */
final class TextRoleSwitchTestFixture implements AutoCloseable {
  static final URI PROVIDER = URI.create("https://synthetic.invalid/v1");
  static final Actor ADMIN = new Actor("org-main", "owner");
  final AuthorityTestContext authority;
  final AnswerTestContext.RecordingProjection projection =
      new AnswerTestContext.RecordingProjection();
  final EvidenceService evidence;
  final ManagedTextRuntime runtime;
  final AtomicInteger released = new AtomicInteger();

  TextRoleSwitchTestFixture(Path directory) {
    authority = new AuthorityTestContext(directory);
    evidence =
        new EvidenceService(
            authority.store(),
            new EvidenceRepository(authority.store()),
            new ManagementRepository(authority.store()),
            new DocumentPermissionPolicy());
    runtime =
        ManagedTextRuntime.anchored(
            authority.store(),
            (version, roles, anchor) ->
                snapshot(version, roles, anchor, released::incrementAndGet));
  }

  TextRuntimeSnapshot snapshot(
      long version, TextModelConfiguration roles, TextIndexAnchor anchor, Runnable release) {
    var actual = new AnswerTestContext.RecordingModels();
    actual.modelRevision = revision(roles);
    var target =
        anchor == null
            ? new IndexTarget(embeddingIdentity(roles), projection.identity(), actual.revision(), 2)
            : anchor.target();
    var original =
        anchor == null
            ? roles
            : new TextModelConfiguration(
                roles.embedding(),
                new TextModelConfiguration.Role(anchor.rerankModel(), roles.rerank().apiKey()),
                new TextModelConfiguration.Role(
                    anchor.generationModel(), roles.generation().apiKey()));
    if (anchor != null
        && (!anchor.providerBaseUrl().equals(PROVIDER.toASCIIString())
            || !anchor.embeddingModel().equals(roles.embedding().model())
            || !anchor.embeddingRevision().equals(roles.embedding().revision())
            || anchor.dimensions() != roles.embedding().dimensions()
            || !target.modelRevision().equals(revision(original))
            || !target.embeddingIdentity().equals(embeddingIdentity(roles)))) {
      throw new ApplicationException(
          FailureKind.CONFLICT, "model_rebuild_required", "Synthetic incompatible index");
    }
    var fixed =
        anchor == null
            ? new TextIndexAnchor(
                version,
                PROVIDER.toASCIIString(),
                roles.embedding().model(),
                roles.embedding().revision(),
                roles.embedding().dimensions(),
                roles.rerank().model(),
                roles.generation().model(),
                target)
            : anchor;
    var processor =
        new IndexingTaskProcessor(
            authority.indexing(),
            ADMIN.workspaceId(),
            target,
            Duration.ofSeconds(3),
            ignored -> {
              throw new AssertionError("Activation and answers must not index");
            });
    var answers =
        new AnswerService(
            evidence, actual, projection, target, Duration.ofSeconds(3), 2, null, null, fixed);
    return new TextRuntimeSnapshot(
        version, actual, projection, target, answers, processor, release, fixed);
  }

  TextRuntimeSnapshot activate(long version, TextModelConfiguration roles, TextIndexAnchor anchor) {
    var snapshot = runtime.prepare(version, roles, anchor);
    try (var lease = authority.store().operationGate().tryMaintenance().orElseThrow()) {
      runtime.install(snapshot, lease, () -> {});
    }
    return snapshot;
  }

  String publish(IndexTarget target) {
    byte[] bytes = "星港项目的识别码为A-42。".getBytes(StandardCharsets.UTF_8);
    authority.uploadDocument(ADMIN, "plan.txt", "text/plain", bytes);
    var ingestion = authority.claimIngestion(ADMIN.workspaceId()).orElseThrow();
    assertTrue(
        authority.completeIngestion(
            ingestion, new TextParser().parse("plan.txt", "text/plain", bytes)));
    authority.createIndexing(ADMIN, ingestion.documentId(), target);
    var claim = authority.claimIndexing(ADMIN.workspaceId()).orElseThrow();
    projection.data.initialize();
    var entries =
        claim.items().stream()
            .map(
                item ->
                    new RetrievalProjection.Entry(
                        RetrievalProjection.physicalSegmentId(
                            claim.projectionGenerationId(), item.evidenceId()),
                        ADMIN.workspaceId(),
                        claim.documentId(),
                        claim.projectionGenerationId(),
                        item.recallText(),
                        List.of(1.0, 0.0)))
            .toList();
    var digests = new TreeMap<String, String>();
    projection.data.upsert(entries);
    for (var entry : entries) {
      digests.put(entry.segmentId(), RetrievalProjection.entryDigest(entry));
    }
    var manifest =
        new RetrievalProjection.RevisionManifest(
            ADMIN.workspaceId(), claim.documentId(), claim.projectionGenerationId(), digests);
    assertTrue(authority.completeIndexing(claim, digests, projection.data.verify(manifest)));
    return claim.documentId();
  }

  String scalar(String sql, String parameter) throws Exception {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + authority.store().libraryPath());
        var statement = connection.prepareStatement(sql)) {
      if (parameter != null) {
        statement.setString(1, parameter);
      }
      try (var result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  static TextModelConfiguration roles(String rerank, String generation) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            "fixture-embedding", "synthetic-embedding-key", 2, "fixture-v1"),
        new TextModelConfiguration.Role(rerank, "synthetic-rerank-key"),
        new TextModelConfiguration.Role(generation, "synthetic-generation-key"));
  }

  static String revision(TextModelConfiguration roles) {
    try (var models =
        new OpenAiCompatibleModels(
            new OpenAiCompatibleModels.Configuration(
                new OpenAiCompatibleModels.Endpoint(
                    PROVIDER, roles.embedding().model(), roles.embedding().apiKey()),
                new OpenAiCompatibleModels.Endpoint(
                    PROVIDER, roles.rerank().model(), roles.rerank().apiKey()),
                new OpenAiCompatibleModels.Endpoint(
                    PROVIDER, roles.generation().model(), roles.generation().apiKey()),
                roles.embedding().dimensions(),
                Duration.ofSeconds(1),
                4096,
                false))) {
      return models.revision();
    }
  }

  private static String embeddingIdentity(TextModelConfiguration roles) {
    return ModelValues.sha256(
        (PROVIDER
                + "\n"
                + roles.embedding().model()
                + "\n"
                + roles.embedding().revision()
                + "\n"
                + roles.embedding().dimensions())
            .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close() {
    runtime.close();
    authority.close();
  }
}
