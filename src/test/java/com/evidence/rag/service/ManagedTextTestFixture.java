package com.evidence.rag.service;

import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.TextModelConfiguration;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;

/** Real temporary authority and immutable bundle; providers are local recording stand-ins. */
final class ManagedTextTestFixture implements AutoCloseable {
  final AnswerTestContext context;
  final ManagedTextRuntime runtime;

  ManagedTextTestFixture(Path directory) {
    context = new AnswerTestContext(directory, Duration.ofSeconds(3), 2);
    runtime =
        new ManagedTextRuntime(
            context.authority.store(), (version, configuration) -> snapshot(version, () -> {}));
  }

  TextRuntimeSnapshot snapshot(long version, Runnable release) {
    return snapshot(version, context.target, release);
  }

  TextRuntimeSnapshot snapshot(long version, IndexTarget indexingTarget, Runnable release) {
    var indexing =
        new IndexingTaskProcessor(
            context.authority.indexing(),
            context.owner.workspaceId(),
            indexingTarget,
            Duration.ofSeconds(3),
            ignored -> {
              throw new AssertionError("Retrieval must not create a worker");
            });
    var answers =
        new AnswerService(
            context.evidence,
            context.models,
            context.projection,
            context.target,
            Duration.ofSeconds(3),
            2);
    try {
      return new TextRuntimeSnapshot(
          version, context.models, context.projection, context.target, answers, indexing, release);
    } catch (RuntimeException failure) {
      answers.close();
      throw failure;
    }
  }

  void activate(long version) {
    var candidate = runtime.prepare(version, configuration());
    try (var maintenance =
        context.authority.store().operationGate().tryMaintenance().orElseThrow()) {
      runtime.install(candidate, maintenance, () -> {});
    }
  }

  static TextModelConfiguration configuration() {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding(
            "test/embedding", "synthetic-configuration-key", 2, "embedding-v1"),
        new TextModelConfiguration.Role("test/rerank", "synthetic-rerank-key"),
        new TextModelConfiguration.Role("test/generation", "synthetic-generation-key"));
  }

  @Override
  public void close() {
    runtime.close();
    context.close();
  }
}
