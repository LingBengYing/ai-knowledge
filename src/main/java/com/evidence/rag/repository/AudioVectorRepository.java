package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Immutable complete original-audio vector receipts; callers own the authority transaction. */
public final class AudioVectorRepository {
  private final SqliteAuthorityStore store;

  public AudioVectorRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public void insert(AudioVectorPublication value) {
    for (var entry : value.entries()) {
      store.execute(
          """
          INSERT INTO audio_vector_entries(audio_vector_publication_id,audio_evidence_id,
            base_physical_segment_id,vector_physical_segment_id,ordinal,start_sample,end_sample,
            pcm_sha256,entry_sha256) VALUES(?,?,?,?,?,?,?,?,?)
          """,
          value.id(),
          entry.audioEvidenceId(),
          entry.basePhysicalSegmentId(),
          entry.vectorPhysicalSegmentId(),
          entry.ordinal(),
          entry.startSample(),
          entry.endSample(),
          entry.pcmSha256(),
          entry.entrySha256());
    }
    var base = value.basePublication();
    var target = value.target();
    store.execute(
        """
        INSERT INTO audio_vector_publications(id,publication_id,document_id,source_revision_id,
          source_sha256,vector_generation_id,embedding_identity,projection_identity,model_revision,
          dimensions,decoder_revision,manifest_sha256,segment_count,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        value.id(),
        base.publicationId(),
        base.documentId(),
        base.sourceRevisionId(),
        base.sourceSha256(),
        value.vectorGenerationId(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        value.decoderRevision(),
        value.manifestSha256(),
        value.entries().size(),
        value.createdAt());
  }

  public List<AudioVectorPublication> findPublications(
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
            "SELECT v.* FROM audio_vector_publications v JOIN documents d ON d.id=v.document_id"
                + " WHERE d.workspace_id=? AND v.publication_id IN ("
                + String.join(",", Collections.nCopies(publications.size(), "?"))
                + ") AND v.embedding_identity=? AND v.projection_identity=? AND v.model_revision=?"
                + " AND v.dimensions=? ORDER BY v.document_id,v.id",
            args.toArray());
    var found = new ArrayList<AudioVectorPublication>();
    for (var row : rows) {
      var base = byId.get(text(row, "publication_id"));
      if (base == null
          || !base.documentId().equals(text(row, "document_id"))
          || !base.sourceRevisionId().equals(text(row, "source_revision_id"))
          || !base.sourceSha256().equals(text(row, "source_sha256"))) {
        throw ModelValues.invalid();
      }
      var entries = new ArrayList<AudioVectorEntry>();
      for (var entry :
          store.rows(
              "SELECT * FROM audio_vector_entries WHERE audio_vector_publication_id=? ORDER BY ordinal",
              text(row, "id"))) {
        entries.add(
            new AudioVectorEntry(
                text(entry, "audio_evidence_id"),
                text(entry, "base_physical_segment_id"),
                text(entry, "vector_physical_segment_id"),
                integer(entry, "ordinal"),
                number(entry, "start_sample"),
                number(entry, "end_sample"),
                text(entry, "pcm_sha256"),
                text(entry, "entry_sha256")));
      }
      if (entries.size() != integer(row, "segment_count")) {
        throw ModelValues.invalid();
      }
      found.add(
          new AudioVectorPublication(
              text(row, "id"),
              base,
              new IndexTarget(
                  text(row, "embedding_identity"),
                  text(row, "projection_identity"),
                  text(row, "model_revision"),
                  integer(row, "dimensions")),
              text(row, "vector_generation_id"),
              text(row, "decoder_revision"),
              entries,
              text(row, "manifest_sha256"),
              text(row, "created_at")));
    }
    return List.copyOf(found);
  }
}
