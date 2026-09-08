package com.evidence.rag.client.vector;

import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.VerifiedRevision;
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
  public String identity() {
    return "d".repeat(64);
  }

  @Override
  public VerifiedRevision verify(RevisionManifest manifest) {
    requireInitialized();
    if (manifest == null || !workspace.equals(manifest.workspaceId())) {
      throw new ProjectionException("projection_invalid_input");
    }
    var actual = new TreeMap<String, String>();
    entries.values().stream()
        .filter(entry -> entry.revisionId().equals(manifest.revisionId()))
        .forEach(
            entry -> {
              if (!entry.workspaceId().equals(manifest.workspaceId())
                  || !entry.documentId().equals(manifest.documentId())) {
                throw new ProjectionException("projection_invalid_response");
              }
              actual.put(entry.segmentId(), RetrievalProjection.entryDigest(entry));
            });
    if (!actual.equals(manifest.entryDigests())) {
      throw new ProjectionException("projection_invalid_response");
    }
    return new VerifiedRevision(identity(), manifest.sha256(), actual.size());
  }

  @Override
  public void initialize() {
    initialized = true;
  }

  @Override
  public void prepareSearch() {
    requireInitialized();
  }

  @Override
  public void upsert(List<Entry> batch) {
    if (batch == null || batch.size() > MAX_BATCH) {
      throw new ProjectionException("projection_invalid_input");
    }
    var ids = new HashSet<String>();
    for (Entry entry : batch) {
      if (entry == null
          || !workspace.equals(entry.workspaceId())
          || entry.vector().size() != dimension
          || !ids.add(entry.segmentId())) {
        throw new ProjectionException("projection_invalid_input");
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
      throw new ProjectionException("projection_invalid_input");
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
      throw new ProjectionException("projection_not_initialized");
    }
  }
}
