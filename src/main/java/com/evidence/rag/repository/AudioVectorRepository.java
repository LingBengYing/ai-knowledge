package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.AudioVectorBinding;
import com.evidence.rag.model.domain.AudioVectorEntry;
import com.evidence.rag.model.domain.AudioVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VectorBindingIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

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

  public void insertBinding(AudioVectorBinding binding, String createdAt) {
    if (binding == null || binding.inheritedFromPublicationId() == null) {
      throw ModelValues.invalid();
    }
    store.execute(
        "INSERT INTO audio_vector_bindings(publication_id,origin_vector_publication_id,inherited_from_publication_id,binding_sha256,created_at,model_rebuild_id) VALUES(?,?,?,?,?,?)",
        binding.basePublication().publicationId(),
        binding.origin().id(),
        binding.inheritedFromPublicationId(),
        binding.bindingSha256(),
        createdAt,
        binding.modelRebuildId());
  }

  public List<AudioVectorBinding> findBindings(
      String workspaceId, List<PublicationVersion> publications, IndexTarget target) {
    if (target == null || publications == null) {
      throw ModelValues.invalid();
    }
    var result = new ArrayList<AudioVectorBinding>();
    var unique = new HashSet<String>();
    for (var base : publications) {
      if (base == null || !unique.add(base.publicationId())) {
        throw ModelValues.invalid();
      }
      result.addAll(bindings(workspaceId, base, target));
    }
    return List.copyOf(result);
  }

  public List<AudioVectorBinding> allBindings(String workspaceId, PublicationVersion base) {
    return bindings(workspaceId, base, null);
  }

  private List<AudioVectorBinding> bindings(
      String workspace, PublicationVersion base, IndexTarget target) {
    VectorBindingRows.requireBase(store, workspace, base);
    var result = new ArrayList<AudioVectorBinding>();
    var profiles = new HashSet<String>();
    for (var row :
        VectorBindingRows.candidates(
            store, "audio_vector_publications", "audio_vector_bindings", base, target)) {
      var originBase =
          VectorBindingRows.publication(
              store, workspace, AuthorityRows.text(row, "publication_id"));
      var origins = findPublications(workspace, List.of(originBase), VectorBindingRows.target(row));
      var origin =
          origins.stream()
              .filter(v -> v.id().equals(AuthorityRows.text(row, "id")))
              .findFirst()
              .orElseThrow(ModelValues::invalid);
      if (!profiles.add(
          VectorBindingIdentity.targetSha256(origin.target()) + ":" + origin.decoderRevision())) {
        throw ModelValues.invalid();
      }
      var headers =
          store.rows(
              "SELECT decoder_revision,span_count,projection_count FROM audio_compilations WHERE revision_id=? AND source_sha256=? AND compiler_revision=?",
              base.sourceRevisionId(),
              base.sourceSha256(),
              base.parserRevision());
      if (headers.size() != 1
          || !origin
              .decoderRevision()
              .equals(AuthorityRows.text(headers.getFirst(), "decoder_revision"))
          || origin.entries().size()
              != AuthorityRows.integer(headers.getFirst(), "projection_count")
          || store.count(
                  "SELECT COUNT(*) FROM audio_publication_entries WHERE publication_id=?",
                  base.publicationId())
              != origin.entries().size()) {
        throw ModelValues.invalid();
      }
      var expected =
          store.rows(
              "SELECT id,ordinal,start_ms,end_ms FROM audio_spans WHERE revision_id=? AND index_ordinal IS NOT NULL ORDER BY ordinal",
              base.sourceRevisionId());
      if (expected.size() != origin.entries().size()) {
        throw ModelValues.invalid();
      }
      var current = new ArrayList<String>();
      var digests = new TreeMap<String, String>();
      for (int i = 0; i < expected.size(); i++) {
        var span = expected.get(i);
        var entry = origin.entries().get(i);
        boolean last =
            entry.ordinal() == AuthorityRows.integer(headers.getFirst(), "span_count") - 1;
        long end = AuthorityRows.number(span, "end_ms") * 16;
        if (!entry.audioEvidenceId().equals(AuthorityRows.text(span, "id"))
            || entry.ordinal() != AuthorityRows.integer(span, "ordinal")
            || entry.startSample() != AuthorityRows.number(span, "start_ms") * 16
            || (last
                ? entry.endSample() <= end - 16 || entry.endSample() > end
                : entry.endSample() != end)
            || !VectorBindingRows.physical(store, originBase, entry.audioEvidenceId(), false)
                .equals(entry.basePhysicalSegmentId())) {
          throw ModelValues.invalid();
        }
        current.add(VectorBindingRows.physical(store, base, entry.audioEvidenceId(), false));
        digests.put(entry.vectorPhysicalSegmentId(), entry.entrySha256());
      }
      String from = AuthorityRows.text(row, "inherited_from_publication_id");
      VectorBindingRows.requireProvenance(store, base, from, origin.id(), false);
      String modelRebuild = AuthorityRows.text(row,"model_rebuild_id");
      VectorBindingRows.requireModelRebuildProvenance(store,base,from,origin.id(),modelRebuild,false);
      String digest = VectorBindingIdentity.audioSha256(base, origin, current, from, modelRebuild);
      if (from != null && !digest.equals(AuthorityRows.text(row, "binding_sha256"))) {
        throw ModelValues.invalid();
      }
      var binding = new AudioVectorBinding(base, origin, current, from, digest, modelRebuild);
      if (!VectorBindingIdentity.manifestSha256(
              workspace, base.documentId(), origin.vectorGenerationId(), digests)
          .equals(origin.manifestSha256())) {
        throw ModelValues.invalid();
      }
      result.add(binding);
    }
    return List.copyOf(result);
  }
}
