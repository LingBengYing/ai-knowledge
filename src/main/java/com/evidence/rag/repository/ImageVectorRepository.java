package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.ImageVectorBinding;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VectorBindingIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SQL boundary for immutable independent image-vector receipts; callers own transactions. */
public final class ImageVectorRepository {
  private final SqliteAuthorityStore store;

  public ImageVectorRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public void insert(ImageVectorPublication value) {
    var base = value.basePublication();
    var target = value.target();
    store.execute(
        """
        INSERT INTO image_vector_publications(id,publication_id,document_id,source_revision_id,
          source_sha256,image_evidence_id,base_physical_segment_id,vector_generation_id,
          vector_physical_segment_id,embedding_identity,projection_identity,model_revision,
          dimensions,entry_sha256,manifest_sha256,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        value.id(),
        base.publicationId(),
        base.documentId(),
        base.sourceRevisionId(),
        base.sourceSha256(),
        value.imageEvidenceId(),
        value.basePhysicalSegmentId(),
        value.vectorGenerationId(),
        value.vectorPhysicalSegmentId(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        value.entrySha256(),
        value.manifestSha256(),
        value.createdAt());
  }

  public List<ImageVectorPublication> findPublications(
      String workspaceId, List<PublicationVersion> publications, IndexTarget target) {
    if (publications.isEmpty()) {
      return List.of();
    }
    var byId = new HashMap<String, PublicationVersion>();
    for (var publication : publications) {
      if (byId.put(publication.publicationId(), publication) != null) {
        throw ModelValues.invalid();
      }
    }
    var args = new ArrayList<Object>();
    args.add(workspaceId);
    args.addAll(publications.stream().map(PublicationVersion::publicationId).toList());
    args.add(target.embeddingIdentity());
    args.add(target.projectionIdentity());
    args.add(target.modelRevision());
    args.add(target.dimensions());
    var rows =
        store.rows(
            "SELECT v.* FROM image_vector_publications v JOIN documents d ON d.id=v.document_id"
                + " WHERE d.workspace_id=? AND v.publication_id IN ("
                + String.join(",", Collections.nCopies(publications.size(), "?"))
                + ") AND v.embedding_identity=? AND v.projection_identity=? AND v.model_revision=?"
                + " AND v.dimensions=? ORDER BY v.document_id,v.id",
            args.toArray());
    var found = new ArrayList<ImageVectorPublication>();
    for (var row : rows) {
      var base = byId.get(text(row, "publication_id"));
      if (base == null
          || !base.documentId().equals(text(row, "document_id"))
          || !base.sourceRevisionId().equals(text(row, "source_revision_id"))
          || !base.sourceSha256().equals(text(row, "source_sha256"))) {
        throw ModelValues.invalid();
      }
      found.add(
          new ImageVectorPublication(
              text(row, "id"),
              base,
              text(row, "image_evidence_id"),
              text(row, "base_physical_segment_id"),
              text(row, "vector_generation_id"),
              text(row, "vector_physical_segment_id"),
              new IndexTarget(
                  text(row, "embedding_identity"),
                  text(row, "projection_identity"),
                  text(row, "model_revision"),
                  integer(row, "dimensions")),
              text(row, "entry_sha256"),
              text(row, "manifest_sha256"),
              text(row, "created_at")));
    }
    return List.copyOf(found);
  }

  public void insertBinding(ImageVectorBinding binding, String createdAt) {
    if (binding == null || binding.inheritedFromPublicationId() == null) {
      throw ModelValues.invalid();
    }
    store.execute(
        "INSERT INTO image_vector_bindings(publication_id,origin_vector_publication_id,inherited_from_publication_id,binding_sha256,created_at,model_rebuild_id) VALUES(?,?,?,?,?,?)",
        binding.basePublication().publicationId(),
        binding.origin().id(),
        binding.inheritedFromPublicationId(),
        binding.bindingSha256(),
        createdAt,
        binding.modelRebuildId());
  }

  public List<ImageVectorBinding> findBindings(
      String workspaceId, List<PublicationVersion> publications, IndexTarget target) {
    if (target == null || publications == null) {
      throw ModelValues.invalid();
    }
    var result = new ArrayList<ImageVectorBinding>();
    var unique = new HashSet<String>();
    for (var base : publications) {
      if (base == null || !unique.add(base.publicationId())) {
        throw ModelValues.invalid();
      }
      result.addAll(bindings(workspaceId, base, target));
    }
    return List.copyOf(result);
  }

  public List<ImageVectorBinding> allBindings(String workspaceId, PublicationVersion base) {
    return bindings(workspaceId, base, null);
  }

  private List<ImageVectorBinding> bindings(
      String workspace, PublicationVersion base, IndexTarget target) {
    VectorBindingRows.requireBase(store, workspace, base);
    var result = new ArrayList<ImageVectorBinding>();
    var profiles = new HashSet<IndexTarget>();
    for (var row :
        VectorBindingRows.candidates(
            store, "image_vector_publications", "image_vector_bindings", base, target)) {
      var originBase =
          VectorBindingRows.publication(
              store, workspace, AuthorityRows.text(row, "publication_id"));
      var origins = findPublications(workspace, List.of(originBase), VectorBindingRows.target(row));
      var origin =
          origins.stream()
              .filter(v -> v.id().equals(AuthorityRows.text(row, "id")))
              .findFirst()
              .orElseThrow(ModelValues::invalid);
      if (!profiles.add(origin.target())
          || store.count(
                  "SELECT COUNT(*) FROM image_evidence i WHERE i.id=? AND i.revision_id=?",
                  origin.imageEvidenceId(),
                  base.sourceRevisionId())
              != 1
          || !VectorBindingRows.physical(store, originBase, origin.imageEvidenceId(), true)
              .equals(origin.basePhysicalSegmentId())) {
        throw ModelValues.invalid();
      }
      String current = VectorBindingRows.physical(store, base, origin.imageEvidenceId(), true);
      String from = AuthorityRows.text(row, "inherited_from_publication_id");
      VectorBindingRows.requireProvenance(store, base, from, origin.id(), true);
      String modelRebuild = AuthorityRows.text(row,"model_rebuild_id");
      VectorBindingRows.requireModelRebuildProvenance(store,base,from,origin.id(),modelRebuild,true);
      String digest = VectorBindingIdentity.imageSha256(base, origin, current, from, modelRebuild);
      if (from != null && !digest.equals(AuthorityRows.text(row, "binding_sha256"))) {
        throw ModelValues.invalid();
      }
      var binding = new ImageVectorBinding(base, origin, current, from, digest, modelRebuild);
      if (!VectorBindingIdentity.manifestSha256(
              workspace,
              base.documentId(),
              origin.vectorGenerationId(),
              Map.of(origin.vectorPhysicalSegmentId(), origin.entrySha256()))
          .equals(origin.manifestSha256())) {
        throw ModelValues.invalid();
      }
      result.add(binding);
    }
    return List.copyOf(result);
  }
}
