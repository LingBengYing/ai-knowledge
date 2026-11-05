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
  private final IndexingTaskProcessor indexing;
  private final boolean mediaRebound;
  private final Runnable releaseClients;
  private final AtomicBoolean closed = new AtomicBoolean();

  public TextRuntimeSnapshot(
      long version,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      IndexingTaskProcessor indexing,
      Runnable releaseClients) {
    this(version, models, projection, target, indexing, releaseClients, null);
  }

  public TextRuntimeSnapshot(
      long version,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      IndexingTaskProcessor indexing,
      Runnable releaseClients,
      TextIndexAnchor indexAnchor) {
    this(version, models, projection, target, indexing, releaseClients, indexAnchor, false);
  }

  public TextRuntimeSnapshot(
      long version,
      TextModels models,
      RetrievalProjection projection,
      IndexTarget target,
      IndexingTaskProcessor indexing,
      Runnable releaseClients,
      TextIndexAnchor indexAnchor,
      boolean mediaRebound) {
    if (version < 1
        || version > 9_007_199_254_740_991L
        || models == null
        || projection == null
        || target == null
        || indexing == null
        || releaseClients == null
        || (indexAnchor == null && !target.modelRevision().equals(models.revision()))
        || (indexAnchor != null
            && (!indexAnchor.target().equals(target) || indexAnchor.originatingVersion() > version))
        || !target.projectionIdentity().equals(projection.identity())
        || !target.equals(indexing.runtimeTarget())) {
      throw ModelValues.invalid();
    }
    this.version = version;
    this.models = models;
    this.projection = projection;
    this.target = target;
    this.indexAnchor = indexAnchor;
    this.modelsRevision = models.revision();
    this.indexing = indexing;
    this.mediaRebound = mediaRebound;
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
        && target.projectionIdentity().equals(projection.identity())
        && (indexAnchor == null
            ? target.modelRevision().equals(modelsRevision)
            : indexAnchor.target().equals(target));
  }

  public IndexingTaskProcessor indexing() {
    return indexing;
  }

  public boolean mediaRebound() {
    return mediaRebound;
  }

  boolean isOpen() {
    return !closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      releaseClients.run();
    }
  }

  @Override
  public String toString() {
    return "TextRuntimeSnapshot[redacted]";
  }
}
