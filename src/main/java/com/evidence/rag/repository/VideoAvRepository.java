package com.evidence.rag.repository;

import static com.evidence.rag.repository.AuthorityRows.integer;
import static com.evidence.rag.repository.AuthorityRows.number;
import static com.evidence.rag.repository.AuthorityRows.text;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvAudioMetadata;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvManagedEvidence;
import com.evidence.rag.model.domain.VideoAvPublication;
import com.evidence.rag.model.domain.VideoAvPublishedWindow;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import com.evidence.rag.model.domain.VideoAvTargets;
import com.evidence.rag.model.domain.VideoAvVideoMetadata;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete original video/audio authority. Every method participates in its caller's single
 * transaction.
 */
public final class VideoAvRepository {
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

  private static boolean matches(VideoAvPublication value, DocumentOriginal original) {
    return value.documentId().equals(original.documentId())
        && value.sourceRevisionId().equals(original.revisionId())
        && value.sourceSha256().equals(original.sourceSha256())
        && value.filename().equals(original.filename())
        && value.mediaType().equals(original.mediaType())
        && value.sizeBytes() == original.sizeBytes();
  }

  private static boolean matches(VideoAvPublication value, ManagedMediaSources.Metadata metadata) {
    return value.documentId().equals(metadata.id())
        && value.workspaceId().equals(metadata.workspace())
        && value.sourceRevisionId().equals(metadata.revision())
        && value.sourceSha256().equals(metadata.sha())
        && value.filename().equals(metadata.filename())
        && value.mediaType().equals(metadata.mime())
        && value.sizeBytes() == metadata.bytes();
  }
}
