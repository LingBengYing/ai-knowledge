package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.TextIndexAnchor;
import java.util.concurrent.atomic.AtomicBoolean;

/** One immutable operation bundle. No provider request is made by construction. */
public final class TextRuntimeSnapshot implements AutoCloseable {
  private final long version;
  private final TextModels models;
  private final RetrievalProjection projection;
  private final IndexTarget target;
  private final TextIndexAnchor indexAnchor;
  private final String modelsRevision;
  private final AnswerService answers;
  private final IndexingTaskProcessor indexing;
  private final Runnable releaseClients;
  private final AtomicBoolean closed = new AtomicBoolean();

  public TextRuntimeSnapshot(
      long version,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      AnswerService answers,
      IndexingTaskProcessor indexing,
      Runnable releaseClients) {
    this(version, models, projection, target, answers, indexing, releaseClients, null);
  }

  public TextRuntimeSnapshot(
      long version,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      AnswerService answers,
      IndexingTaskProcessor indexing,
      Runnable releaseClients,
      TextIndexAnchor indexAnchor) {
    if (version < 1
        || version > 9_007_199_254_740_991L
        || models == null
        || projection == null
        || target == null
        || answers == null
        || indexing == null
        || releaseClients == null
        || (indexAnchor == null && !target.modelRevision().equals(models.revision()))
        || (indexAnchor != null
            && (!indexAnchor.target().equals(target) || indexAnchor.originatingVersion() > version))
        || !models.revision().equals(answers.runtimeModelsRevision())
        || !target.projectionIdentity().equals(projection.identity())
        || !target.equals(answers.runtimeTarget())
        || !target.equals(indexing.runtimeTarget())) {
      throw ModelValues.invalid();
    }
    this.version = version;
    this.models = models;
    this.projection = projection;
    this.target = target;
    this.indexAnchor = indexAnchor;
    this.modelsRevision = models.revision();
    this.answers = answers;
    this.indexing = indexing;
    this.releaseClients = releaseClients;
  }

  public long version() {
    return version;
  }

  public TextModels models() {
    return models;
  }

  public RetrievalProjection projection() {
    return projection;
  }

  public IndexTarget target() {
    return target;
  }

  public TextIndexAnchor indexAnchor() {
    return indexAnchor;
  }

  public String modelsRevision() {
    return modelsRevision;
  }

  boolean identityCurrent() {
    return modelsRevision.equals(models.revision())
        && modelsRevision.equals(answers.runtimeModelsRevision())
        && target.projectionIdentity().equals(projection.identity())
        && (indexAnchor == null
            ? target.modelRevision().equals(modelsRevision)
            : indexAnchor.target().equals(target));
  }

  public AnswerService answers() {
    return answers;
  }

  public IndexingTaskProcessor indexing() {
    return indexing;
  }

  boolean isOpen() {
    return !closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        answers.close();
      } finally {
        releaseClients.run();
      }
    }
  }

  @Override
  public String toString() {
    return "TextRuntimeSnapshot[redacted]";
  }
}
