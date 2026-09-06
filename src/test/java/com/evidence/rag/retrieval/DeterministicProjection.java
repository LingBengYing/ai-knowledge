package com.evidence.rag.retrieval;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Test Adapter: stable ID ordering, not a simulation of Milvus, BM25 quality, or durability. */
public final class DeterministicProjection implements RetrievalProjection {
  private final String workspace;
  private final int dimension;
  private final Map<String, Entry> entries = new TreeMap<>();
  private boolean initialized;

  public DeterministicProjection(String workspace, int dimension) {
    this.workspace = new AuthorizedScope(workspace, Map.of()).workspaceId();
    this.dimension = dimension;
  }

  @Override
  public void initialize() {
    initialized = true;
  }

  @Override
  public void upsert(List<Entry> batch) {
    if (batch == null || batch.size() > MAX_BATCH) {
      throw new Failure("projection_invalid_input");
    }
    var ids = new HashSet<String>();
    for (Entry entry : batch) {
      if (entry == null
          || !workspace.equals(entry.workspaceId())
          || entry.vector().size() != dimension
          || !ids.add(entry.segmentId())) {
        throw new Failure("projection_invalid_input");
      }
    }
    if (batch.isEmpty()) {
      return;
    }
    requireInitialized();
    batch.forEach(entry -> entries.put(entry.segmentId(), entry));
  }

  @Override
  public List<Candidate> search(Query query) {
    if (query == null
        || !workspace.equals(query.scope().workspaceId())
        || query.vector().size() != dimension) {
      throw new Failure("projection_invalid_input");
    }
    if (query.scope().documentRevisions().isEmpty()) {
      return List.of();
    }
    requireInitialized();
    return entries.values().stream()
        .filter(
            entry ->
                entry
                    .revisionId()
                    .equals(query.scope().documentRevisions().get(entry.documentId())))
        .limit(query.limit())
        .map(entry -> new Candidate(entry.segmentId(), 1))
        .toList();
  }

  private void requireInitialized() {
    if (!initialized) {
      throw new Failure("projection_not_initialized");
    }
  }
}
