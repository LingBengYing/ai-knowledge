package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
}
