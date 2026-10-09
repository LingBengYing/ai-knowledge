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
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvAudioMetadata;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvEvidence;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvManagedEvidence;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvProof;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvPublication;
import com.evidence.rag.model.domain.VideoAvPublishedWindow;
import com.evidence.rag.model.domain.VideoAvQueryManifest;
import com.evidence.rag.model.domain.VideoAvQueryTrace;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import com.evidence.rag.model.domain.VideoAvScope;
import com.evidence.rag.model.domain.VideoAvSource;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvTraceDraft;
import com.evidence.rag.model.domain.VideoAvTraceReceipt;
import com.evidence.rag.model.domain.VideoAvVideoMetadata;
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

/**
 * Complete original video/audio authority. Every method participates in its caller's single
 * transaction.
 */
public final class VideoAvRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final SqliteAuthorityStore store;
  private final ManagementRepository management;

  public VideoAvRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
    management = new ManagementRepository(store);
  }

  public void insertOriginal(DocumentOriginal original, String createdAt) {
    if (!"video".equals(original.documentType())) {
      throw ModelValues.invalid();
    }
    store.execute(
        """
        INSERT INTO video_av_originals(document_id,source_revision_id,source_sha256,filename,
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
        .filter(value -> "video".equals(value.documentType()));
  }

  public void insertPublication(VideoAvPublication publication) {
    for (var window : publication.windows()) {
      var video = window.video();
      var audio = window.audio();
      store.execute(
          """
          INSERT INTO video_av_windows(publication_id,id,ordinal,start_tick,end_tick,
            clip_sha256,frame_count,frames_manifest_sha256,first_local_tick,end_local_tick,
            pcm_sha256,wav_sha256,audio_start_sample,audio_end_sample,sample_rate,
            visual_physical_id,visual_entry_sha256,audio_physical_id,audio_entry_sha256)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          publication.id(),
          window.id(),
          window.ordinal(),
          window.startTick(),
          window.endTick(),
          video == null ? null : video.clipSha256(),
          video == null ? null : video.frameCount(),
          video == null ? null : video.framesManifestSha256(),
          video == null ? null : video.firstLocalTick(),
          video == null ? null : video.endLocalTick(),
          audio == null ? null : audio.pcmSha256(),
          audio == null ? null : audio.wavSha256(),
          audio == null ? null : audio.startSample(),
          audio == null ? null : audio.endSample(),
          audio == null ? null : audio.sampleRate(),
          window.visualPhysicalId(),
          window.visualEntrySha256(),
          window.audioPhysicalId(),
          window.audioEntrySha256());
    }
    var visual = publication.visualTarget();
    var audio = publication.audioTarget();
    var epoch = publication.epoch();
    store.execute(
        """
        INSERT INTO video_av_publications(id,workspace_id,document_id,source_revision_id,source_sha256,
          filename,media_type,size_bytes,source_first_pts,source_time_base_num,source_time_base_den,
          ticks_per_second,duration_tick,has_audio,decoder_revision,analysis_model_revision,profile_fingerprint,
          visual_embedding_identity,visual_projection_identity,visual_model_revision,visual_dimensions,
          audio_embedding_identity,audio_projection_identity,audio_model_revision,audio_dimensions,
          chunk_seconds,window_count,window_manifest_sha256,visual_entry_count,visual_manifest_sha256,
          audio_entry_count,audio_manifest_sha256,created_at_ms)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        publication.id(),
        publication.workspaceId(),
        publication.documentId(),
        publication.sourceRevisionId(),
        publication.sourceSha256(),
        publication.filename(),
        publication.mediaType(),
        publication.sizeBytes(),
        epoch.sourceFirstPts(),
        epoch.sourceTimeBaseNumerator(),
        epoch.sourceTimeBaseDenominator(),
        epoch.ticksPerSecond(),
        publication.durationTick(),
        publication.hasAudio() ? 1 : 0,
        publication.decoderRevision(),
        publication.analysisModelRevision(),
        publication.profileFingerprint(),
        visual.embeddingIdentity(),
        visual.projectionIdentity(),
        visual.modelRevision(),
        visual.dimensions(),
        audio.embeddingIdentity(),
        audio.projectionIdentity(),
        audio.modelRevision(),
        audio.dimensions(),
        publication.chunkSeconds(),
        publication.windows().size(),
        publication.windowManifestSha256(),
        publication.visualReceipt().count(),
        publication.visualReceipt().manifestSha256(),
        publication.audioReceipt().count(),
        publication.audioReceipt().manifestSha256(),
        publication.createdAtMs());
  }

  public Optional<VideoAvPublication> findPublication(
      DocumentOriginal original, VideoAvTargets targets, String profile) {
    return publication(original.documentId(), targets, profile)
        .filter(value -> matches(value, original));
  }

  public Optional<VideoAvManagedEvidence> managedEvidence(String documentId) {
    var rows =
        store.rows(
            """
        SELECT s.source_revision_id,
          (SELECT p.id FROM video_av_publications p WHERE p.document_id=s.document_id
             AND p.source_revision_id=s.source_revision_id AND p.source_sha256=s.source_sha256
             ORDER BY p.created_at_ms DESC,p.id DESC LIMIT 1) AS publication_id
        FROM video_av_originals s JOIN documents d ON d.id=s.document_id
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
                store.count("SELECT COUNT(*) FROM video_av_windows WHERE publication_id=?", id));
    return Optional.of(new VideoAvManagedEvidence(text(row, "source_revision_id"), id, count));
  }

  /** No original bytes are selected by the initial authorization and receipt gate. */
  public VideoAvScope scope(
      Actor actor, DocumentSelection selection, VideoAvTargets targets, String profile) {
    if (actor == null || selection == null || targets == null || profile == null) {
      throw ModelValues.invalid();
    }
    List<String> ids = selection.documentIds();
    if (selection.all()) {
      ids =
          store
              .rows(
                  """
          SELECT d.id FROM documents d
          WHERE d.workspace_id=?
            AND d.document_type='video'
            AND (EXISTS(SELECT 1 FROM video_av_originals s WHERE s.document_id=d.id)
              OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
            AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
          ORDER BY d.id
          """,
                  actor.workspaceId())
              .stream()
              .map(row -> text(row, "id"))
              .toList();
    }
    var publications = new ArrayList<VideoAvPublication>();
    for (String id : ids) {
      var metadata = metadata(actor, id).orElseThrow(ModelValues::notFound);
      var value = publication(id, targets, profile).orElseThrow(VideoAvRepository::indexRequired);
      if (!matches(value, metadata) || !actor.workspaceId().equals(value.workspaceId())) {
        throw indexRequired();
      }
      publications.add(value);
    }
    return new VideoAvScope(actor, selection, publications);
  }

  public List<VideoAvEvidence> hydrate(
      VideoAvScope scope, VideoAvRoute route, List<String> physicalIds) {
    if (scope == null
        || route == null
        || physicalIds == null
        || physicalIds.size() > 64
        || new HashSet<>(physicalIds).size() != physicalIds.size()) {
      throw ModelValues.invalid();
    }
    requireCurrent(scope);
    var available = new HashMap<String, VideoAvEvidence>();
    for (var publication : scope.publications()) {
      for (var window : publication.windows()) {
        String id =
            route == VideoAvRoute.VISUAL ? window.visualPhysicalId() : window.audioPhysicalId();
        if (id != null && available.put(id, new VideoAvEvidence(publication, window)) != null) {
          throw changed();
        }
      }
    }
    var result = new ArrayList<VideoAvEvidence>();
    for (String id : physicalIds) {
      var value = available.get(id);
      if (value == null) {
        throw changed();
      }
      result.add(value);
    }
    return List.copyOf(result);
  }

  private void requireCurrent(VideoAvScope scope) {
    if (scope.publications().isEmpty()) {
      requireEmptyScopeCurrent(scope);
    } else {
      var first = scope.publications().getFirst();
      if (!current(
          scope,
          new VideoAvTargets(first.visualTarget(), first.audioTarget()),
          first.profileFingerprint())) {
        throw changed();
      }
    }
  }

  /** Revalidates full membership and then one original at a time, keeping memory bounded. */
  public boolean current(VideoAvScope expected, VideoAvTargets targets, String profile) {
    try {
      var actual = scope(expected.actor(), expected.selection(), targets, profile);
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

  public VideoAvTraceReceipt finish(VideoAvScope scope, VideoAvTraceDraft draft) {
    if (scope == null || draft == null) {
      throw ModelValues.invalid();
    }
    requireCurrent(scope);
    var publications = new HashMap<String, VideoAvPublication>();
    scope.publications().forEach(value -> publications.put(value.id(), value));
    var unique = new HashSet<String>();
    for (var proof : draft.citations()) {
      var source = proof.evidence();
      if (!source.publication().equals(publications.get(source.publication().id()))
          || !source.publication().windows().contains(source.window())
          || !unique.add(source.publication().id() + ":" + source.window().id())
          || proof.mode() != draft.mode()
          || !draft.modelRevision().equals(source.publication().analysisModelRevision())
          || !VideoAvProofIdentity.matches(
              proof, draft.questionSha256(), draft.modelRevision(), draft.policyRevision())) {
        throw ModelValues.invalid();
      }
    }
    if (!draft.citations().isEmpty()
        && !draft
            .answerSha256()
            .equals(VideoAvProofIdentity.sha(answerText(draft.citations().getFirst().facts())))) {
      throw ModelValues.invalid();
    }
    String trace = UUID.randomUUID().toString();
    for (int index = 0; index < scope.publications().size(); index++) {
      store.execute(
          "INSERT INTO video_av_trace_documents(trace_id,ordinal,publication_id) VALUES(?,?,?)",
          trace,
          index,
          scope.publications().get(index).id());
    }
    for (int index = 0; index < draft.citations().size(); index++) {
      var proof = draft.citations().get(index);
      store.execute(
          """
          INSERT INTO video_av_trace_evidence(trace_id,ordinal,publication_id,window_id,facts_json,
            facts_sha256,proof_sha256) VALUES(?,?,?,?,?,?,?)
          """,
          trace,
          index + 1,
          proof.evidence().publication().id(),
          proof.evidence().window().id(),
          VideoAvProofIdentity.canonicalFactsJson(proof.facts()),
          proof.factsSha256(),
          proof.proofSha256());
    }
    if (draft.queryTrace() != null) {
      insertQueryTrace(trace, scope, draft.queryTrace());
    }
    store.execute(
        """
        INSERT INTO video_av_traces(id,workspace_id,actor_id,selection_all,scope_count,citation_count,
          mode,question_sha256,answer_sha256,status,reason_code,model_revision,policy_revision,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        trace,
        scope.actor().workspaceId(),
        scope.actor().principalId(),
        scope.selection().all() ? 1 : 0,
        scope.publications().size(),
        draft.citations().size(),
        draft.mode().name(),
        draft.questionSha256(),
        draft.answerSha256(),
        draft.status(),
        draft.reasonCode(),
        draft.modelRevision(),
        draft.policyRevision(),
        Instant.now().toString());
    return new VideoAvTraceReceipt(trace, draft.status(), draft.reasonCode());
  }

  public VideoAvSource source(
      Actor actor, String traceId, int ordinal, VideoAvTargets targets, String profile) {
    ModelValues.indexIdentity(traceId);
    if (ordinal < 1 || ordinal > 32) {
      throw ModelValues.notFound();
    }
    var traces =
        store.rows(
            "SELECT * FROM video_av_traces WHERE id=? AND workspace_id=? AND status='answered'",
            traceId,
            actor.workspaceId());
    if (traces.size() != 1) {
      throw ModelValues.notFound();
    }
    var trace = traces.getFirst();
    var rows =
        store.rows(
            "SELECT * FROM video_av_trace_documents WHERE trace_id=? ORDER BY ordinal", traceId);
    if (rows.size() != integer(trace, "scope_count")) {
      throw ModelValues.notFound();
    }
    var publications = new ArrayList<VideoAvPublication>();
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
                publications.stream().map(VideoAvPublication::documentId).toList());
    var scope = new VideoAvScope(actor, selection, publications);
    if (!current(scope, targets, profile)) {
      throw ModelValues.notFound();
    }
    validateQueryTrace(traceId, trace, targets, profile);
    var evidence =
        store.rows(
            "SELECT * FROM video_av_trace_evidence WHERE trace_id=? ORDER BY ordinal", traceId);
    if (evidence.size() != integer(trace, "citation_count") || ordinal > evidence.size()) {
      throw ModelValues.notFound();
    }
    VideoAvProof selected = null;
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
          publication.windows().stream()
              .filter(value -> value.id().equals(text(row, "window_id")))
              .findFirst()
              .orElseThrow(ModelValues::notFound);
      var proof =
          new VideoAvProof(
              new VideoAvEvidence(publication, span),
              VideoAvMode.valueOf(text(trace, "mode")),
              facts(text(row, "facts_json")),
              text(row, "facts_sha256"),
              text(row, "proof_sha256"));
      if (!VideoAvProofIdentity.matches(
              proof,
              text(trace, "question_sha256"),
              text(trace, "model_revision"),
              text(trace, "policy_revision"))
          || !text(trace, "answer_sha256")
              .equals(VideoAvProofIdentity.sha(answerText(proof.facts())))) {
        throw ModelValues.notFound();
      }
      if (index + 1 == ordinal) {
        selected = proof;
      }
    }
    var original =
        findOriginal(actor, Objects.requireNonNull(selected).evidence().publication().documentId())
            .orElseThrow(ModelValues::notFound);
    return new VideoAvSource(traceId, ordinal, selected, original);
  }

  private void insertQueryTrace(String traceId, VideoAvScope scope, VideoAvQueryTrace query) {
    for (var publication : scope.publications()) {
      if (!publication.profileFingerprint().equals(query.profileFingerprint())
          || !publication.visualTarget().embeddingIdentity().equals(query.embeddingRevision())
          || !publication.audioTarget().embeddingIdentity().equals(query.embeddingRevision())) {
        throw ModelValues.invalid();
      }
    }
    for (var attachment : query.attachments()) {
      store.execute(
          """
          INSERT INTO video_av_query_attachments(trace_id,ordinal,source_sha256,media_kind,
            compiler_revision,content_sha256,window_count,visual_window_count,audio_window_count,
            audio_present,used_mode,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          traceId,
          attachment.ordinal(),
          attachment.sourceSha256(),
          attachment.mediaKind(),
          attachment.compilerRevision(),
          attachment.contentSha256(),
          attachment.windowCount(),
          attachment.visualWindowCount(),
          attachment.audioWindowCount(),
          attachment.audioPresent() == null ? null : attachment.audioPresent() ? 1 : 0,
          attachment.usedMode().name(),
          attachment.status());
    }
    store.execute(
        """
        INSERT INTO video_av_query_preparations(trace_id,attachment_count,mode,question_sha256,
          profile_fingerprint,embedding_revision,preparation_revision,manifest_sha256)
        VALUES(?,?,?,?,?,?,?,?)
        """,
        traceId,
        query.attachments().size(),
        query.mode().name(),
        query.questionSha256(),
        query.profileFingerprint(),
        query.embeddingRevision(),
        query.preparationRevision(),
        query.manifestSha256());
  }

  private void validateQueryTrace(
      String traceId, Map<String, Object> parent, VideoAvTargets targets, String profile) {
    var headers = store.rows("SELECT * FROM video_av_query_preparations WHERE trace_id=?", traceId);
    var rows =
        store.rows(
            "SELECT * FROM video_av_query_attachments WHERE trace_id=? ORDER BY ordinal", traceId);
    if (headers.isEmpty() && rows.isEmpty()) {
      return;
    }
    if (headers.size() != 1) {
      throw ModelValues.notFound();
    }
    try {
      var header = headers.getFirst();
      var attachments = new ArrayList<VideoAvQueryManifest>();
      for (var row : rows) {
        Boolean audioPresent = null;
        if (row.get("audio_present") != null) {
          int flag = queryInteger(row, "audio_present");
          if (flag != 0 && flag != 1) {
            throw ModelValues.notFound();
          }
          audioPresent = flag == 1;
        }
        attachments.add(
            new VideoAvQueryManifest(
                queryInteger(row, "ordinal"),
                text(row, "source_sha256"),
                text(row, "media_kind"),
                text(row, "compiler_revision"),
                text(row, "content_sha256"),
                nullableInteger(row, "window_count"),
                nullableInteger(row, "visual_window_count"),
                nullableInteger(row, "audio_window_count"),
                audioPresent,
                VideoAvMode.valueOf(text(row, "used_mode")),
                text(row, "status")));
      }
      var query =
          new VideoAvQueryTrace(
              VideoAvMode.valueOf(text(header, "mode")),
              text(header, "question_sha256"),
              text(header, "profile_fingerprint"),
              text(header, "embedding_revision"),
              attachments);
      if (queryInteger(header, "attachment_count") != attachments.size()
          || !query.mode().name().equals(text(parent, "mode"))
          || !query.questionSha256().equals(text(parent, "question_sha256"))
          || !query.profileFingerprint().equals(profile)
          || !query.embeddingRevision().equals(targets.visual().embeddingIdentity())
          || !query.embeddingRevision().equals(targets.audio().embeddingIdentity())
          || !query.preparationRevision().equals(text(header, "preparation_revision"))
          || !query.manifestSha256().equals(text(header, "manifest_sha256"))
          || "answered".equals(text(parent, "status"))
              && !"prepared".equals(attachments.getFirst().status())) {
        throw ModelValues.notFound();
      }
    } catch (RuntimeException invalid) {
      throw ModelValues.notFound();
    }
  }

  private static Integer nullableInteger(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : queryInteger(row, key);
  }

  private static int queryInteger(Map<String, Object> row, String key) {
    var value = row.get(key);
    if (!(value instanceof Integer) && !(value instanceof Long)) {
      throw ModelValues.notFound();
    }
    long exact = ((Number) value).longValue();
    if (exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE) {
      throw ModelValues.notFound();
    }
    return (int) exact;
  }

  private static List<VideoAvFact> facts(String encoded) {
    try {
      var node = JSON.readTree(encoded);
      if (!node.isArray() || node.isEmpty() || node.size() > 16) {
        throw ModelValues.notFound();
      }
      var values = new ArrayList<VideoAvFact>();
      for (var fact : node) {
        if (!fact.isObject()
            || fact.size() != 5
            || !fact.path("id").isString()
            || !fact.path("text").isString()
            || !fact.path("requirement").isString()
            || !fact.path("visual_contribution").isBoolean()
            || !fact.path("audio_contribution").isBoolean()) {
          throw ModelValues.notFound();
        }
        values.add(
            new VideoAvFact(
                fact.path("id").asString(),
                fact.path("text").asString(),
                VideoAvRequirement.valueOf(fact.path("requirement").asString()),
                fact.path("visual_contribution").asBoolean(),
                fact.path("audio_contribution").asBoolean()));
      }
      if (!encoded.equals(VideoAvProofIdentity.canonicalFactsJson(values))) {
        throw ModelValues.notFound();
      }
      return List.copyOf(values);
    } catch (RuntimeException invalid) {
      throw ModelValues.notFound();
    }
  }

  private static String answerText(List<VideoAvFact> facts) {
    return String.join("\n", facts.stream().map(VideoAvFact::text).toList());
  }

  private void requireEmptyScopeCurrent(VideoAvScope scope) {
    if (scope.selection().all()
        && store.count(
                """
        SELECT count(*) FROM documents d
        WHERE d.workspace_id=?
          AND d.document_type='video'
          AND (EXISTS(SELECT 1 FROM video_av_originals s WHERE s.document_id=d.id)
            OR EXISTS(SELECT 1 FROM corpus_documents c WHERE c.document_id=d.id))
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
                scope.actor().workspaceId())
            != 0) {
      throw changed();
    }
  }

  private Optional<VideoAvPublication> publication(
      String id, VideoAvTargets targets, String profile) {
    return store
        .rows(
            """
        SELECT * FROM video_av_publications WHERE document_id=? AND profile_fingerprint=?
          AND visual_embedding_identity=? AND visual_projection_identity=? AND visual_model_revision=? AND visual_dimensions=?
          AND audio_embedding_identity=? AND audio_projection_identity=? AND audio_model_revision=? AND audio_dimensions=?
        ORDER BY created_at_ms DESC,id DESC LIMIT 1
        """,
            id,
            profile,
            targets.visual().embeddingIdentity(),
            targets.visual().projectionIdentity(),
            targets.visual().modelRevision(),
            targets.visual().dimensions(),
            targets.audio().embeddingIdentity(),
            targets.audio().projectionIdentity(),
            targets.audio().modelRevision(),
            targets.audio().dimensions())
        .stream()
        .findFirst()
        .map(this::publication);
  }

  private Optional<VideoAvPublication> publicationById(String id) {
    return store.rows("SELECT * FROM video_av_publications WHERE id=?", id).stream()
        .findFirst()
        .map(this::publication);
  }

  private VideoAvPublication publication(Map<String, Object> row) {
    try {
      var windows =
          store
              .rows(
                  "SELECT * FROM video_av_windows WHERE publication_id=? ORDER BY ordinal",
                  text(row, "id"))
              .stream()
              .map(
                  window ->
                      new VideoAvPublishedWindow(
                          text(window, "id"),
                          integer(window, "ordinal"),
                          number(window, "start_tick"),
                          number(window, "end_tick"),
                          text(window, "clip_sha256") == null
                              ? null
                              : new VideoAvVideoMetadata(
                                  text(window, "clip_sha256"),
                                  integer(window, "frame_count"),
                                  text(window, "frames_manifest_sha256"),
                                  number(window, "first_local_tick"),
                                  number(window, "end_local_tick")),
                          text(window, "pcm_sha256") == null
                              ? null
                              : new VideoAvAudioMetadata(
                                  text(window, "pcm_sha256"),
                                  text(window, "wav_sha256"),
                                  number(window, "audio_start_sample"),
                                  number(window, "audio_end_sample"),
                                  integer(window, "sample_rate")),
                          text(window, "visual_physical_id"),
                          text(window, "visual_entry_sha256"),
                          text(window, "audio_physical_id"),
                          text(window, "audio_entry_sha256")))
              .toList();
      if (windows.size() != integer(row, "window_count")) {
        throw ModelValues.notFound();
      }
      var visual =
          new IndexTarget(
              text(row, "visual_embedding_identity"),
              text(row, "visual_projection_identity"),
              text(row, "visual_model_revision"),
              integer(row, "visual_dimensions"));
      var audio =
          new IndexTarget(
              text(row, "audio_embedding_identity"),
              text(row, "audio_projection_identity"),
              text(row, "audio_model_revision"),
              integer(row, "audio_dimensions"));
      return new VideoAvPublication(
          text(row, "id"),
          text(row, "workspace_id"),
          text(row, "document_id"),
          text(row, "source_revision_id"),
          text(row, "source_sha256"),
          text(row, "filename"),
          text(row, "media_type"),
          number(row, "size_bytes"),
          new VideoAvEpoch(
              number(row, "source_first_pts"),
              number(row, "source_time_base_num"),
              number(row, "source_time_base_den"),
              number(row, "ticks_per_second")),
          number(row, "duration_tick"),
          integer(row, "has_audio") == 1,
          text(row, "decoder_revision"),
          text(row, "analysis_model_revision"),
          text(row, "profile_fingerprint"),
          visual,
          audio,
          integer(row, "chunk_seconds"),
          text(row, "window_manifest_sha256"),
          windows,
          receipt(row, "visual", VideoAvRoute.VISUAL, visual),
          receipt(row, "audio", VideoAvRoute.AUDIO, audio),
          number(row, "created_at_ms"));
    } catch (RuntimeException invalid) {
      throw ModelValues.notFound();
    }
  }

  private static VideoAvRouteReceipt receipt(
      Map<String, Object> row, String prefix, VideoAvRoute route, IndexTarget target) {
    int count = integer(row, prefix + "_entry_count");
    String manifest = text(row, prefix + "_manifest_sha256");
    return new VideoAvRouteReceipt(
        route,
        count,
        manifest,
        count == 0 ? null : new VerifiedRevision(target.projectionIdentity(), manifest, count));
  }

  private Optional<SourceMetadata> metadata(Actor actor, String id) {
    return store
        .rows(
            """
        SELECT d.id,d.workspace_id,d.active_revision_id,d.source_sha256,d.filename,d.mime_type,d.size_bytes
        FROM documents d
        LEFT JOIN video_av_originals s ON s.document_id=d.id
        LEFT JOIN corpus_documents c ON c.document_id=d.id
        WHERE d.id=? AND d.workspace_id=?
          AND d.document_type='video'
          AND ((s.source_revision_id=d.active_revision_id AND s.source_sha256=d.source_sha256
              AND s.filename=d.filename AND s.media_type=d.mime_type AND s.size_bytes=d.size_bytes AND length(s.original_blob)=d.size_bytes)
            OR (c.initial_revision_id=d.active_revision_id AND length(c.original_blob)=d.size_bytes
              AND EXISTS(SELECT 1 FROM corpus_revisions r WHERE r.id=c.initial_revision_id
                AND r.document_id=d.id AND r.source_sha256=d.source_sha256)))
          AND d.size_bytes BETWEEN 1 AND 20971520
          AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)
        """,
            id,
            actor.workspaceId())
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

  private static boolean matches(VideoAvPublication value, DocumentOriginal original) {
    return value.documentId().equals(original.documentId())
        && value.sourceRevisionId().equals(original.revisionId())
        && value.sourceSha256().equals(original.sourceSha256())
        && value.filename().equals(original.filename())
        && value.mediaType().equals(original.mediaType())
        && value.sizeBytes() == original.sizeBytes();
  }

  private static boolean matches(VideoAvPublication value, SourceMetadata metadata) {
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
        FailureKind.CONFLICT, "video_av_index_required", "请为当前范围的全部视频音画资料建立视频音画索引。");
  }

  private static ApplicationException changed() {
    return new ApplicationException(FailureKind.CONFLICT, "scope_changed", "视频音画资料范围、权限或发布版本已变化。");
  }
}
