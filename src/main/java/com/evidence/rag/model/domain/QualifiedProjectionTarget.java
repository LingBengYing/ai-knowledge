package com.evidence.rag.model.domain;

import java.net.URI;

/** Non-secret physical projection address pinned before any possible remote write. */
public record QualifiedProjectionTarget(
    String endpoint,
    String database,
    String collection,
    String workspaceId,
    String embeddingIdentity,
    int dimensions,
    String projectionIdentity) {
  public QualifiedProjectionTarget {
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (RuntimeException bad) {
      throw ModelValues.invalid();
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
        || database == null
        || !database.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
        || collection == null
        || !collection.matches("java_[A-Za-z0-9_]{1,122}")
        || dimensions < 2
        || dimensions > 32768
        || projectionIdentity == null
        || !projectionIdentity.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(workspaceId, 128);
    ModelValues.identifier(embeddingIdentity, 200);
  }

  @Override
  public String toString() {
    return "QualifiedProjectionTarget[redacted]";
  }
}
