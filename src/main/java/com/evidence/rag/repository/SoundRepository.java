package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SoundManagedEvidence;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundProof;
import com.evidence.rag.model.domain.SoundProofIdentity;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundPublishedSpan;
import com.evidence.rag.model.domain.SoundScope;
import com.evidence.rag.model.domain.SoundSource;
import com.evidence.rag.model.domain.SoundSpan;
import com.evidence.rag.model.domain.SoundTraceDraft;
import com.evidence.rag.model.domain.SoundTraceReceipt;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Complete sound authority. Every method participates in its caller's single transaction. */
public final class SoundRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final SqliteAuthorityStore store;
  private final ManagementRepository management;

  public SoundRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
    management = new ManagementRepository(store);
  }

  public void insertOriginal(DocumentOriginal original, String createdAt) {
    if (!"audio".equals(original.documentType())) {
      throw ModelValues.invalid();
    }
    store.execute(
        """
        INSERT INTO sound_originals(document_id,source_revision_id,source_sha256,filename,
          media_type,size_bytes,original_blob,created_at) VALUES(?,?,?,?,?,?,?,?)
        """,
        original.documentId(),
        original.revisionId(),
        original.sourceSha256(),
        original.filename(),
        original.mediaType(),
        original.sizeBytes(),
        original.content(),
        createdAt);
  }

  public Optional<DocumentOriginal> findOriginal(Actor actor, String documentId) {
    return management
        .findDocumentOriginal(actor, documentId)
        .filter(value -> "audio".equals(value.documentType()));
  }

  public void insertPublication(SoundPublication publication) {
    validatePublication(publication);
    for (var span : publication.spans()) {
      store.execute(
          """
          INSERT INTO sound_spans(publication_id,id,ordinal,start_sample,end_sample,pcm_sha256,
            recall_text,physical_segment_id,entry_sha256) VALUES(?,?,?,?,?,?,?,?,?)
          """,
          publication.id(),
          span.id(),
          span.ordinal(),
          span.startSample(),
          span.endSample(),
          span.pcmSha256(),
          span.recallText(),
          span.physicalSegmentId(),
          span.entrySha256());
    }
    var target = publication.target();
    store.execute(
        """
        INSERT INTO sound_publications(id,workspace_id,document_id,source_revision_id,source_sha256,
          filename,media_type,size_bytes,generation_id,embedding_identity,projection_identity,
          embedding_model_revision,dimensions,sound_model_revision,decoder_revision,chunk_seconds,
          sample_count,span_count,manifest_sha256,profile_fingerprint,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        publication.id(),
        publication.workspaceId(),
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.filename(),
        publication.mediaType(),
        publication.sizeBytes(),
        publication.generationId(),
        target.embeddingIdentity(),
        target.projectionIdentity(),
        target.modelRevision(),
        target.dimensions(),
        publication.soundModelRevision(),
        publication.decoderRevision(),
        publication.chunkSeconds(),
        publication.sampleCount(),
        publication.spans().size(),
        publication.manifestSha256(),
        publication.profileFingerprint(),
        publication.createdAt());
  }

  public Optional<SoundPublication> findPublication(
      DocumentOriginal original, IndexTarget target, String profile) {
    return publication(original.documentId(), target, profile)
        .filter(value -> matches(value, original));
  }

  public Optional<SoundManagedEvidence> managedEvidence(String documentId) {
    var rows =
        store.rows(
            """
        SELECT s.source_revision_id,
          (SELECT p.id FROM sound_publications p WHERE p.document_id=s.document_id
             AND p.source_revision_id=s.source_revision_id AND p.source_sha256=s.source_sha256
             ORDER BY p.created_at DESC,p.id DESC LIMIT 1) AS publication_id
        FROM sound_originals s JOIN documents d ON d.id=s.document_id
        WHERE s.document_id=? AND s.source_revision_id=d.active_revision_id
          AND s.source_sha256=d.source_sha256
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            documentId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    var row = rows.getFirst();
    String id = text(row, "publication_id");
    int count =
        id == null
            ? 0
            : Math.toIntExact(
                store.count("SELECT COUNT(*) FROM sound_spans WHERE publication_id=?", id));
    return Optional.of(new SoundManagedEvidence(text(row, "source_revision_id"), id, count));
  }

  /** No original bytes are selected by the initial authorization and receipt gate. */
  public SoundScope scope(
      Actor actor, DocumentSelection selection, IndexTarget target, String profile) {
    if (actor == null || selection == null || target == null || profile == null) {
      throw ModelValues.invalid();
    }
    List<String> ids = selection.documentIds();
    if (selection.all()) {
      ids =
          store
              .rows(
                  """
          SELECT d.id FROM documents d JOIN document_acl a ON a.document_id=d.id
          WHERE d.workspace_id=? AND a.principal_id=? AND a.role IN ('owner','editor','reader')
            AND d.document_type='audio'
            AND (EXISTS(SELECT 1 FROM sound_originals s WHERE s.document_id=d.id)
              OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
          ORDER BY d.id LIMIT 129
          """,
                  actor.workspaceId(),
                  actor.principalId())
              .stream()
              .map(row -> text(row, "id"))
              .toList();
      if (ids.size() > 128) {
        throw new ApplicationException(
            FailureKind.CAPACITY_EXCEEDED, "evidence_capacity_exceeded", "声音资料范围超过处理容量。");
      }
    }
    var publications = new ArrayList<SoundPublication>();
    for (String id : ids) {
      var metadata = metadata(actor, id).orElseThrow(ModelValues::notFound);
      var value = publication(id, target, profile).orElseThrow(SoundRepository::indexRequired);
      if (!matches(value, metadata) || !actor.workspaceId().equals(value.workspaceId())) {
        throw indexRequired();
      }
      publications.add(value);
    }
    return new SoundScope(actor, selection, publications);
  }

  public List<SoundPublishedSpan> hydrate(SoundScope scope, List<String> physicalIds) {
    if (scope == null
        || physicalIds == null
        || physicalIds.size() > 64
        || new HashSet<>(physicalIds).size() != physicalIds.size()) {
      throw ModelValues.invalid();
    }
    if (scope.publications().isEmpty()) {
      requireEmptyScopeCurrent(scope);
    } else {
      var first = scope.publications().getFirst();
      if (!current(scope, first.target(), first.profileFingerprint())) {
        throw changed();
      }
    }
    var available = new HashMap<String, SoundPublishedSpan>();
    for (var publication : scope.publications()) {
      for (var span : publication.spans()) {
        if (available.put(span.physicalSegmentId(), new SoundPublishedSpan(publication, span))
            != null) {
          throw changed();
        }
      }
    }
    var result = new ArrayList<SoundPublishedSpan>();
    for (String id : physicalIds) {
      var value = available.get(id);
      if (value == null) {
        throw changed();
      }
      result.add(value);
    }
    return List.copyOf(result);
  }

  /** Revalidates full membership and then one original at a time, keeping memory bounded. */
  public boolean current(SoundScope expected, IndexTarget target, String profile) {
    try {
      var actual = scope(expected.actor(), expected.selection(), target, profile);
      if (!actual.equals(expected)) {
        return false;
      }
      for (var publication : expected.publications()) {
        var original =
            findOriginal(expected.actor(), publication.documentId())
                .orElseThrow(ModelValues::notFound);
        if (!matches(publication, original)) {
          return false;
        }
      }
      return true;
    } catch (ApplicationException invalid) {
      return false;
    }
  }

  public SoundTraceReceipt finish(SoundScope scope, SoundTraceDraft draft) {
    if (scope == null || draft == null) {
      throw ModelValues.invalid();
    }
    if (scope.publications().isEmpty()) {
      requireEmptyScopeCurrent(scope);
    } else {
      var first = scope.publications().getFirst();
      if (!current(scope, first.target(), first.profileFingerprint())) {
        throw changed();
      }
    }
    var publications = new HashMap<String, SoundPublication>();
    scope.publications().forEach(value -> publications.put(value.id(), value));
    var unique = new HashSet<String>();
    for (var proof : draft.citations()) {
      var source = proof.source();
      if (!source.publication().equals(publications.get(source.publication().id()))
          || !source.publication().spans().contains(source.span())
          || !unique.add(source.span().physicalSegmentId())
          || !draft.modelRevision().equals(source.publication().soundModelRevision())
          || !SoundProofIdentity.matches(
              proof, draft.questionSha256(), draft.modelRevision(), draft.policyRevision())) {
        throw ModelValues.invalid();
      }
    }
    if (!draft.citations().isEmpty()
        && !draft
            .answerSha256()
            .equals(
                SoundProofIdentity.sha(String.join("\n", draft.citations().getFirst().facts())))) {
      throw ModelValues.invalid();
    }
    String trace = UUID.randomUUID().toString();
    for (int index = 0; index < scope.publications().size(); index++) {
      store.execute(
          "INSERT INTO sound_trace_documents(trace_id,ordinal,publication_id) VALUES(?,?,?)",
          trace,
          index,
          scope.publications().get(index).id());
    }
    for (int index = 0; index < draft.citations().size(); index++) {
      var proof = draft.citations().get(index);
      store.execute(
          """
          INSERT INTO sound_trace_evidence(trace_id,ordinal,publication_id,span_id,facts_json,
            facts_sha256,proof_sha256) VALUES(?,?,?,?,?,?,?)
          """,
          trace,
          index + 1,
          proof.source().publication().id(),
          proof.source().span().id(),
          SoundProof.factsJson(proof.facts()),
          proof.factsSha256(),
          proof.proofSha256());
    }
    store.execute(
        """
        INSERT INTO sound_traces(id,workspace_id,actor_id,selection_all,scope_count,citation_count,
          question_sha256,answer_sha256,status,reason_code,model_revision,policy_revision,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        trace,
        scope.actor().workspaceId(),
        scope.actor().principalId(),
        scope.selection().all() ? 1 : 0,
        scope.publications().size(),
        draft.citations().size(),
        draft.questionSha256(),
        draft.answerSha256(),
        draft.status(),
        draft.reasonCode(),
        draft.modelRevision(),
        draft.policyRevision(),
        Instant.now().toString());
    return new SoundTraceReceipt(trace, draft.status(), draft.reasonCode());
  }

  public SoundSource source(
      Actor actor, String traceId, int ordinal, IndexTarget target, String profile) {
    ModelValues.indexIdentity(traceId);
    if (ordinal < 1 || ordinal > 32) {
      throw ModelValues.notFound();
    }
    var traces =
        store.rows(
            "SELECT * FROM sound_traces WHERE id=? AND workspace_id=? AND actor_id=? AND status='answered'",
            traceId,
            actor.workspaceId(),
            actor.principalId());
    if (traces.size() != 1) {
      throw ModelValues.notFound();
    }
    var trace = traces.getFirst();
    var rows =
        store.rows(
            "SELECT * FROM sound_trace_documents WHERE trace_id=? ORDER BY ordinal", traceId);
    if (rows.size() != integer(trace, "scope_count")) {
      throw ModelValues.notFound();
    }
    var publications = new ArrayList<SoundPublication>();
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      if (integer(row, "ordinal") != index) {
        throw ModelValues.notFound();
      }
      publications.add(
          publicationById(text(row, "publication_id")).orElseThrow(ModelValues::notFound));
    }
    var selection =
        integer(trace, "selection_all") == 1
            ? DocumentSelection.allDocuments()
            : DocumentSelection.selected(
                publications.stream().map(SoundPublication::documentId).toList());
    var scope = new SoundScope(actor, selection, publications);
    if (!current(scope, target, profile)) {
      throw ModelValues.notFound();
    }
    var evidence =
        store.rows("SELECT * FROM sound_trace_evidence WHERE trace_id=? ORDER BY ordinal", traceId);
    if (evidence.size() != integer(trace, "citation_count") || ordinal > evidence.size()) {
      throw ModelValues.notFound();
    }
    SoundProof selected = null;
    for (int index = 0; index < evidence.size(); index++) {
      var row = evidence.get(index);
      if (integer(row, "ordinal") != index + 1) {
        throw ModelValues.notFound();
      }
      var publication =
          publications.stream()
              .filter(value -> value.id().equals(text(row, "publication_id")))
              .findFirst()
              .orElseThrow(ModelValues::notFound);
      var span =
          publication.spans().stream()
              .filter(value -> value.id().equals(text(row, "span_id")))
              .findFirst()
              .orElseThrow(ModelValues::notFound);
      var proof =
          new SoundProof(
              new SoundPublishedSpan(publication, span),
              facts(text(row, "facts_json")),
              text(row, "facts_sha256"),
              text(row, "proof_sha256"));
      if (!SoundProofIdentity.matches(
              proof,
              text(trace, "question_sha256"),
              text(trace, "model_revision"),
              text(trace, "policy_revision"))
          || !text(trace, "answer_sha256")
              .equals(SoundProofIdentity.sha(String.join("\n", proof.facts())))) {
        throw ModelValues.notFound();
      }
      if (index + 1 == ordinal) {
        selected = proof;
      }
    }
    var original =
        findOriginal(actor, Objects.requireNonNull(selected).source().publication().documentId())
            .orElseThrow(ModelValues::notFound);
    return new SoundSource(traceId, ordinal, selected, original);
  }

  private static List<String> facts(String encoded) {
    try {
      var node = JSON.readTree(encoded);
      if (!node.isArray() || node.isEmpty() || node.size() > 16) {
        throw ModelValues.notFound();
      }
      var values = new ArrayList<String>();
      for (var fact : node) {
        if (!fact.isString()) {
          throw ModelValues.notFound();
        }
        values.add(fact.asString());
      }
      if (!encoded.equals(SoundProof.factsJson(values))) {
        throw ModelValues.notFound();
      }
      return List.copyOf(values);
    } catch (RuntimeException invalid) {
      throw ModelValues.notFound();
    }
  }

  private void requireEmptyScopeCurrent(SoundScope scope) {
    if (scope.selection().all()
        && store.count(
                """
        SELECT count(*) FROM documents d JOIN document_acl a ON a.document_id=d.id
        WHERE d.workspace_id=? AND a.principal_id=? AND a.role IN ('owner','editor','reader')
          AND d.document_type='audio'
          AND (EXISTS(SELECT 1 FROM sound_originals s WHERE s.document_id=d.id)
            OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
                scope.actor().workspaceId(),
                scope.actor().principalId())
            != 0) {
      throw changed();
    }
  }

  private Optional<SoundPublication> publication(String id, IndexTarget target, String profile) {
    return store
        .rows(
            """
        SELECT * FROM sound_publications WHERE document_id=? AND embedding_identity=?
          AND projection_identity=? AND embedding_model_revision=? AND dimensions=? AND profile_fingerprint=?
        ORDER BY created_at DESC,id DESC LIMIT 1
        """,
            id,
            target.embeddingIdentity(),
            target.projectionIdentity(),
            target.modelRevision(),
            target.dimensions(),
            profile)
        .stream()
        .findFirst()
        .map(this::publication);
  }

  private Optional<SoundPublication> publicationById(String id) {
    return store.rows("SELECT * FROM sound_publications WHERE id=?", id).stream()
        .findFirst()
        .map(this::publication);
  }

  private SoundPublication publication(Map<String, Object> row) {
    var spans =
        store
            .rows(
                "SELECT * FROM sound_spans WHERE publication_id=? ORDER BY ordinal",
                text(row, "id"))
            .stream()
            .map(
                span ->
                    new SoundSpan(
                        text(span, "id"),
                        integer(span, "ordinal"),
                        number(span, "start_sample"),
                        number(span, "end_sample"),
                        text(span, "pcm_sha256"),
                        text(span, "recall_text"),
                        text(span, "physical_segment_id"),
                        text(span, "entry_sha256")))
            .toList();
    if (spans.size() != integer(row, "span_count")) {
      throw ModelValues.notFound();
    }
    var value =
        new SoundPublication(
            text(row, "id"),
            text(row, "workspace_id"),
            text(row, "document_id"),
            text(row, "source_revision_id"),
            text(row, "source_sha256"),
            text(row, "filename"),
            text(row, "media_type"),
            number(row, "size_bytes"),
            text(row, "generation_id"),
            new IndexTarget(
                text(row, "embedding_identity"),
                text(row, "projection_identity"),
                text(row, "embedding_model_revision"),
                integer(row, "dimensions")),
            text(row, "sound_model_revision"),
            text(row, "decoder_revision"),
            integer(row, "chunk_seconds"),
            number(row, "sample_count"),
            spans,
            text(row, "manifest_sha256"),
            text(row, "profile_fingerprint"),
            text(row, "created_at"));
    validatePublication(value);
    return value;
  }

  private static void validatePublication(SoundPublication value) {
    if (!SoundProfile.fingerprint(
            value.target(),
            value.soundModelRevision(),
            value.decoderRevision(),
            value.chunkSeconds())
        .equals(value.profileFingerprint())) {
      throw ModelValues.invalid();
    }
    for (var span : value.spans()) {
      if (!SoundProfile.physicalSegmentId(value.generationId(), span.id())
          .equals(span.physicalSegmentId())) {
        throw ModelValues.invalid();
      }
    }
    if (!SoundProfile.manifestSha256(
            value.workspaceId(), value.documentId(), value.generationId(), value.spans())
        .equals(value.manifestSha256())) {
      throw ModelValues.invalid();
    }
  }

  private Optional<SourceMetadata> metadata(Actor actor, String id) {
    return store
        .rows(
            """
        SELECT d.id,d.workspace_id,d.active_revision_id,d.source_sha256,d.filename,d.mime_type,d.size_bytes
        FROM documents d JOIN document_acl a ON a.document_id=d.id
        LEFT JOIN sound_originals s ON s.document_id=d.id
        LEFT JOIN corpus_documents c ON c.document_id=d.id
        WHERE d.id=? AND d.workspace_id=? AND a.principal_id=? AND a.role IN ('owner','editor','reader')
          AND d.document_type='audio'
          AND ((s.source_revision_id=d.active_revision_id AND s.source_sha256=d.source_sha256
              AND s.filename=d.filename AND s.media_type=d.mime_type AND s.size_bytes=d.size_bytes AND length(s.original_blob)=d.size_bytes)
            OR (c.initial_revision_id=d.active_revision_id AND length(c.original_blob)=d.size_bytes
              AND EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=c.initial_revision_id
                AND r.document_id=d.id AND r.source_sha256=d.source_sha256)))
          AND d.size_bytes BETWEEN 1 AND 20971520
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            id,
            actor.workspaceId(),
            actor.principalId())
        .stream()
        .findFirst()
        .map(
            row ->
                new SourceMetadata(
                    text(row, "id"),
                    text(row, "workspace_id"),
                    text(row, "active_revision_id"),
                    text(row, "source_sha256"),
                    text(row, "filename"),
                    text(row, "mime_type"),
                    number(row, "size_bytes")));
  }

  private static boolean matches(SoundPublication value, DocumentOriginal original) {
    return value.documentId().equals(original.documentId())
        && value.sourceRevisionId().equals(original.revisionId())
        && value.sourceSha256().equals(original.sourceSha256())
        && value.filename().equals(original.filename())
        && value.mediaType().equals(original.mediaType())
        && value.sizeBytes() == original.sizeBytes();
  }

  private static boolean matches(SoundPublication value, SourceMetadata metadata) {
    return value.documentId().equals(metadata.id())
        && value.workspaceId().equals(metadata.workspace())
        && value.sourceRevisionId().equals(metadata.revision())
        && value.sourceSha256().equals(metadata.sha())
        && value.filename().equals(metadata.filename())
        && value.mediaType().equals(metadata.mime())
        && value.sizeBytes() == metadata.bytes();
  }

  private record SourceMetadata(
      String id,
      String workspace,
      String revision,
      String sha,
      String filename,
      String mime,
      long bytes) {
    @Override
    public String toString() {
      return "SourceMetadata[redacted]";
    }
  }

  private static ApplicationException indexRequired() {
    return new ApplicationException(
        FailureKind.CONFLICT, "sound_index_required", "请为当前范围的全部声音资料建立声音索引。");
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "声音资料范围、权限或发布版本已变化。");
  }
}
