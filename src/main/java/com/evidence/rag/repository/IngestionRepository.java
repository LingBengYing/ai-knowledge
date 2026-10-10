package com.evidence.rag.repository;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.AudioEvidence;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageEvidence;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.TextPage;
import com.evidence.rag.model.domain.TextSegment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoEvidenceGroup;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrEvidence;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoOcrSegmentEvidence;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleCueEvidence;
import com.evidence.rag.model.domain.VideoSubtitleEvidence;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import com.evidence.rag.model.domain.VideoSubtitleTrackEvidence;
import com.evidence.rag.model.domain.VideoTranscriptEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.entity.TaskEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Persistence for immutable originals, evidence and ingestion attempts. No parsing or policy. */
public final class IngestionRepository {
  private final SqliteAuthorityStore store;

  public IngestionRepository(SqliteAuthorityStore store) {
    this.store = Objects.requireNonNull(store);
  }

  public long pendingCount(String workspaceId) {
    return store.count(
        "SELECT COUNT(*) FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state IN ('queued','processing') AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
        workspaceId);
  }

  public long storedBytes(String workspaceId) {
    return store.count(
        """
        SELECT COALESCE(SUM(resident_bytes),0) FROM (
          SELECT length(c.original_blob) resident_bytes FROM corpus_documents c JOIN documents d ON d.id=c.document_id WHERE d.workspace_id=?
          UNION ALL SELECT length(c.original_blob) FROM sound_originals c JOIN documents d ON d.id=c.document_id WHERE d.workspace_id=?
          UNION ALL SELECT length(c.original_blob) FROM video_av_originals c JOIN documents d ON d.id=c.document_id WHERE d.workspace_id=?
          UNION ALL SELECT length(c.original_blob) FROM document_original_revisions c JOIN documents d ON d.id=c.document_id WHERE d.workspace_id=?)
        """,
        workspaceId,
        workspaceId,
        workspaceId,
        workspaceId);
  }

  public void insertOriginal(
      String documentId,
      String revisionId,
      String parserRevision,
      String sourceHash,
      byte[] original,
      String now) {
    store.execute(
        "INSERT INTO corpus_revisions(id,document_id,parser_revision,source_sha256,created_at) VALUES(?,?,?,?,?)",
        revisionId,
        documentId,
        parserRevision,
        sourceHash,
        now);
    store.execute(
        "INSERT INTO corpus_documents(document_id,original_blob,initial_revision_id) VALUES(?,?,?)",
        documentId,
        original,
        revisionId);
  }

  public void insertJob(
      String jobId, String documentId, String revisionId, String creator, String now) {
    store.execute(
        "INSERT INTO ingestion_jobs(id,document_id,revision_id,state,attempt,created_by,created_at,updated_at) VALUES(?,?,?,'queued',1,?,?,?)",
        jobId,
        documentId,
        revisionId,
        creator,
        now,
        now);
  }

  public void insertReplacementJob(
      String jobId,
      String documentId,
      String revisionId,
      String creator,
      String replacementId,
      String parserRevision,
      String sourceHash,
      String now) {
    store.execute(
        "INSERT INTO corpus_revisions(id,document_id,parser_revision,source_sha256,created_at) VALUES(?,?,?,?,?)",
        revisionId,
        documentId,
        parserRevision,
        sourceHash,
        now);
    store.execute(
        "INSERT INTO ingestion_jobs(id,document_id,revision_id,state,attempt,created_by,created_at,updated_at,replacement_id) VALUES(?,?,?,'queued',1,?,?,?,?)",
        jobId,
        documentId,
        revisionId,
        creator,
        now,
        now,
        replacementId);
    new DocumentUpdateRepository(store).attachIngestionJob(replacementId, jobId, now);
  }

  public boolean replacementCurrent(String jobId) {
    return new DocumentUpdateRepository(store).replacementCurrent(jobId);
  }

  public boolean sourceCurrent(TaskEntity task) {
    return replacementCurrent(task.id());
  }

  public Optional<String> nextQueuedId(String workspaceId) {
    return store
        .rows(
            "SELECT j.id FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id WHERE d.workspace_id=? AND j.state='queued' AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id) ORDER BY j.created_at,j.id LIMIT 1",
            workspaceId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "id"));
  }

  public void markProcessing(String jobId, String tokenHash, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='processing',claim_token_sha256=?,updated_at=? WHERE id=? AND state='queued'",
        tokenHash,
        now,
        jobId);
    replacementState(jobId, "processing", now);
  }

  public Optional<TaskEntity> findInternalTask(String jobId) {
    return store
        .rows(
            "SELECT j.*,d.workspace_id,COALESCE(o.filename,d.filename) filename,COALESCE(o.media_type,d.mime_type) mime_type,r.source_sha256,r.parser_revision FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id JOIN corpus_revisions r ON r.id=j.revision_id AND r.document_id=d.id LEFT JOIN document_original_revisions o ON o.revision_id=j.revision_id AND o.document_id=d.id WHERE j.id=?",
            jobId)
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, false));
  }

  public Optional<TaskEntity> findAuthorizedTask(Actor actor, String jobId, boolean edit) {
    return store
        .rows(
            "SELECT j.*,COALESCE(o.filename,d.filename) filename,'member' AS current_role FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id LEFT JOIN document_original_revisions o ON o.revision_id=j.revision_id AND o.document_id=d.id WHERE j.id=? AND d.workspace_id=? AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
            jobId,
            actor.workspaceId())
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.task(row, false));
  }

  public byte[] original(String documentId) {
    return ((byte[])
            store
                .rows("SELECT original_blob FROM corpus_documents WHERE document_id=?", documentId)
                .getFirst()
                .get("original_blob"))
        .clone();
  }

  public byte[] original(String documentId, String revisionId) {
    return new DocumentUpdateRepository(store)
        .original(documentId, revisionId)
        .orElseThrow(ModelValues::invalid)
        .content();
  }

  public void insertPage(String revisionId, TextPage page, String textHash) {
    store.execute(
        "INSERT INTO corpus_pages(revision_id,page_number,text,text_sha256) VALUES(?,?,?,?)",
        revisionId,
        page.number(),
        page.text(),
        textHash);
  }

  public void insertSegment(
      String segmentId, String revisionId, TextSegment segment, String textHash) {
    store.execute(
        "INSERT INTO corpus_segments(id,revision_id,ordinal,page_number,start_offset,end_offset,text,text_sha256) VALUES(?,?,?,?,?,?,?,?)",
        segmentId,
        revisionId,
        segment.ordinal(),
        segment.page(),
        segment.start(),
        segment.end(),
        segment.text(),
        textHash);
  }

  public void insertImageRegion(String revisionId, int ordinal, ImageTextRegion region) {
    store.execute(
        "INSERT INTO image_text_regions VALUES(?,1,?,?,?,?,?,?,?)",
        revisionId,
        ordinal,
        region.start(),
        region.end(),
        region.left(),
        region.top(),
        region.right(),
        region.bottom());
  }

  public void insertImageEvidence(ImageEvidence evidence, String now) {
    store.execute(
        "INSERT INTO image_evidence(id,revision_id,width,height,recall_text,recall_sha256,description_revision,created_at) VALUES(?,?,?,?,?,?,?,?)",
        evidence.id(),
        evidence.revisionId(),
        evidence.width(),
        evidence.height(),
        evidence.recallText(),
        evidence.recallSha256(),
        evidence.descriptionRevision(),
        now);
  }

  public Optional<ImageEvidence> findImageEvidence(String revisionId) {
    return store.rows("SELECT * FROM image_evidence WHERE revision_id=?", revisionId).stream()
        .findFirst()
        .map(
            row ->
                new ImageEvidence(
                    AuthorityRows.text(row, "id"),
                    AuthorityRows.text(row, "revision_id"),
                    AuthorityRows.integer(row, "width"),
                    AuthorityRows.integer(row, "height"),
                    AuthorityRows.text(row, "recall_text"),
                    AuthorityRows.text(row, "recall_sha256"),
                    AuthorityRows.text(row, "description_revision")));
  }

  public void insertAudioCompilation(String revisionId, AudioCompilation compilation, String now) {
    var evidence = AudioEvidence.fromCompilation(revisionId, compilation);
    store.execute(
        "INSERT INTO audio_compilations(revision_id,source_sha256,decoder_revision,model_revision,compiler_revision,duration_ms,span_count,projection_count,transcript_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        revisionId,
        compilation.sourceSha256(),
        compilation.decoderRevision(),
        compilation.modelRevision(),
        compilation.compilerRevision(),
        compilation.durationMs(),
        evidence.size(),
        evidence.stream().filter(span -> span.indexOrdinal() != null).count(),
        transcriptSha256(compilation),
        now);
    for (var span : evidence) {
      store.execute(
          "INSERT INTO audio_spans(id,revision_id,ordinal,start_ms,end_ms,text,text_sha256,index_ordinal) VALUES(?,?,?,?,?,?,?,?)",
          span.id(),
          span.revisionId(),
          span.ordinal(),
          span.startMs(),
          span.endMs(),
          span.text(),
          span.textSha256(),
          span.indexOrdinal());
    }
  }

  public void insertVideoCompilation(String revisionId, VideoCompilation compilation, String now) {
    var evidence = VideoEvidence.fromCompilation(revisionId, compilation);
    boolean subtitleCompiler = compilation.compilerRevision().startsWith("java-video-compiler-v3:");
    if (!compilation.textEvidenceOnly() && subtitleCompiler != (compilation.subtitles() != null)) {
      throw ModelValues.invalid();
    }
    var subtitles =
        compilation.subtitles() == null
            ? null
            : VideoSubtitleEvidence.fromCompilation(revisionId, compilation);
    var audio = compilation.audio();
    store.execute(
        "INSERT INTO video_compilations(revision_id,source_sha256,decoder_revision,compiler_revision,timeline_origin_us,duration_us,frame_count,span_count,group_count,projection_count,frame_bytes,manifest_sha256,audio_model_revision,audio_transcription_revision,audio_sample_count,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        revisionId,
        compilation.sourceSha256(),
        compilation.decoderRevision(),
        compilation.compilerRevision(),
        compilation.timelineOriginUs(),
        compilation.durationUs(),
        evidence.frames().size(),
        evidence.spans().size(),
        evidence.groups().size(),
        evidence.projectionCount(),
        evidence.frameBytes(),
        evidence.manifestSha256(),
        audio == null ? null : audio.modelRevision(),
        audio == null ? null : audio.transcriptionRevision(),
        audio == null ? null : audio.sampleCount(),
        now);
    for (var entry : evidence.frames()) {
      var frame = entry.material().frame();
      var recall = entry.material().recall();
      store.execute(
          "INSERT INTO video_frames(id,revision_id,ordinal,presentation_us,duration_us,media_type,frame_blob,frame_sha256,width,height,recall_text,recall_sha256,description_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          entry.id(),
          revisionId,
          frame.ordinal(),
          frame.presentationUs(),
          frame.durationUs(),
          frame.image().mediaType(),
          frame.image().content(),
          frame.image().sha256(),
          frame.width(),
          frame.height(),
          recall == null ? null : recall.recallText(),
          recall == null
              ? null
              : ModelValues.sha256(recall.recallText().getBytes(StandardCharsets.UTF_8)),
          recall == null ? null : recall.modelRevision());
    }
    for (var entry : evidence.spans()) {
      var span = entry.span();
      store.execute(
          "INSERT INTO video_transcript_spans(id,revision_id,ordinal,start_ms,end_ms,text,text_sha256,index_ordinal) VALUES(?,?,?,?,?,?,?,?)",
          entry.id(),
          revisionId,
          span.ordinal(),
          span.startMs(),
          span.endMs(),
          span.text(),
          span.textSha256(),
          entry.indexOrdinal());
    }
    for (var group : evidence.groups()) {
      store.execute(
          "INSERT INTO video_evidence_groups(id,revision_id,ordinal,start_us,end_us,frame_id,transcript_span_id) VALUES(?,?,?,?,?,?,?)",
          group.id(),
          revisionId,
          group.ordinal(),
          group.startUs(),
          group.endUs(),
          group.frameId(),
          group.transcriptSpanId());
    }
    if (compilation.ocr() != null) {
      insertVideoOcr(revisionId, compilation, evidence, now);
    }
    if (subtitles != null) {
      insertVideoSubtitles(revisionId, compilation, evidence, subtitles, now);
    }
  }

  private void insertVideoSubtitles(
      String revisionId,
      VideoCompilation compilation,
      VideoEvidence base,
      VideoSubtitleEvidence evidence,
      String now) {
    var subtitles = compilation.subtitles();
    var ocr =
        compilation.ocr() == null
            ? null
            : VideoOcrEvidence.fromCompilation(revisionId, compilation);
    store.execute(
        "INSERT INTO video_subtitle_compilations(revision_id,video_epoch_pts,video_time_base_numerator,video_time_base_denominator,track_count,cue_count,projection_count,base_manifest_sha256,native_manifest_sha256,manifest_sha256,ocr_expected,ocr_manifest_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        revisionId,
        subtitles.videoEpochPts(),
        subtitles.videoTimeBaseNumerator(),
        subtitles.videoTimeBaseDenominator(),
        evidence.tracks().size(),
        evidence.tracks().stream().mapToInt(track -> track.cues().size()).sum(),
        evidence.projectionCount(),
        base.manifestSha256(),
        subtitles.manifestSha256(),
        evidence.manifestSha256(),
        ocr == null ? 0 : 1,
        ocr == null ? null : ocr.manifestSha256(),
        now);
    for (var entry : evidence.tracks()) {
      var track = entry.track();
      store.execute(
          "INSERT INTO video_subtitle_tracks(id,revision_id,stream_index,codec,time_base_numerator,time_base_denominator,language,cue_count,text,text_sha256) VALUES(?,?,?,?,?,?,?,?,?,?)",
          entry.id(),
          revisionId,
          track.streamIndex(),
          track.codec(),
          track.timeBaseNumerator(),
          track.timeBaseDenominator(),
          track.language(),
          track.cues().size(),
          entry.text(),
          entry.textSha256());
      for (var packet : entry.cues()) {
        var cue = packet.cue();
        store.execute(
            "INSERT INTO video_subtitle_cues(id,revision_id,track_id,stream_index,ordinal,pts,duration,text,text_sha256,payload_sha256,start_offset,end_offset,start_us,end_us,index_ordinal) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            packet.id(),
            revisionId,
            packet.trackId(),
            packet.streamIndex(),
            cue.ordinal(),
            cue.pts(),
            cue.duration(),
            cue.text(),
            packet.textSha256(),
            cue.payloadSha256(),
            packet.startOffset(),
            packet.endOffset(),
            packet.startUs(),
            packet.endUs(),
            packet.indexOrdinal());
      }
    }
  }

  private void insertVideoOcr(
      String revisionId, VideoCompilation compilation, VideoEvidence base, String now) {
    var ocr = compilation.ocr();
    var evidence = VideoOcrEvidence.fromCompilation(revisionId, compilation);
    store.execute(
        "INSERT INTO video_ocr_compilations(revision_id,ocr_revision,frame_count,projection_count,base_manifest_sha256,manifest_sha256,created_at) VALUES(?,?,?,?,?,?,?)",
        revisionId,
        ocr.ocrRevision(),
        ocr.frames().size(),
        evidence.segments().size(),
        base.manifestSha256(),
        evidence.manifestSha256(),
        now);
    for (var frame : ocr.frames()) {
      String frameId = VideoEvidence.frameIdentity(revisionId, frame.frameOrdinal());
      store.execute(
          "INSERT INTO video_frame_ocr(revision_id,frame_id,ordinal,frame_sha256,width,height,text,text_sha256,segment_count,region_count) VALUES(?,?,?,?,?,?,?,?,?,?)",
          revisionId,
          frameId,
          frame.frameOrdinal(),
          frame.frameSha256(),
          frame.dimensions().width(),
          frame.dimensions().height(),
          frame.text(),
          sha(frame.text()),
          frame.segments().size(),
          frame.regions().size());
      for (var segment : frame.segments()) {
        store.execute(
            "INSERT INTO video_ocr_segments(id,revision_id,frame_id,ordinal,start_offset,end_offset,text,text_sha256) VALUES(?,?,?,?,?,?,?,?)",
            VideoOcrSegmentEvidence.identity(revisionId, frameId, segment.ordinal()),
            revisionId,
            frameId,
            segment.ordinal(),
            segment.start(),
            segment.end(),
            segment.text(),
            sha(segment.text()));
      }
      for (int ordinal = 0; ordinal < frame.regions().size(); ordinal++) {
        var region = frame.regions().get(ordinal);
        store.execute(
            "INSERT INTO video_ocr_regions(revision_id,frame_id,ordinal,start_offset,end_offset,left_pixel,top_pixel,right_pixel,bottom_pixel) VALUES(?,?,?,?,?,?,?,?,?)",
            revisionId,
            frameId,
            ordinal,
            region.start(),
            region.end(),
            region.left(),
            region.top(),
            region.right(),
            region.bottom());
      }
    }
  }

  private static String sha(String text) {
    return ModelValues.sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  public Optional<VideoCompilation> findVideoCompilation(String revisionId) {
    var headers = store.rows("SELECT * FROM video_compilations WHERE revision_id=?", revisionId);
    if (headers.isEmpty()) {
      return Optional.empty();
    }
    var header = headers.getFirst();
    var frames = new java.util.ArrayList<VideoFrameRecall>();
    for (var row :
        store.rows("SELECT * FROM video_frames WHERE revision_id=? ORDER BY ordinal", revisionId)) {
      var image =
          new VisualImage(AuthorityRows.text(row, "media_type"), (byte[]) row.get("frame_blob"));
      boolean textOnly =
          VideoCompilation.isTextEvidenceOnlyRevision(
              AuthorityRows.text(header, "compiler_revision"));
      if (textOnly != (row.get("recall_text") == null)
          || textOnly != (row.get("recall_sha256") == null)
          || textOnly != (row.get("description_revision") == null)) {
        throw ModelValues.invalid();
      }
      var recall =
          textOnly
              ? null
              : new ImageRecall(
                  AuthorityRows.text(row, "recall_text"),
                  AuthorityRows.text(row, "description_revision"));
      int ordinal = AuthorityRows.integer(row, "ordinal");
      if (!VideoEvidence.frameIdentity(revisionId, ordinal).equals(AuthorityRows.text(row, "id"))
          || !image.sha256().equals(AuthorityRows.text(row, "frame_sha256"))
          || (recall != null
              && !ModelValues.sha256(recall.recallText().getBytes(StandardCharsets.UTF_8))
                  .equals(AuthorityRows.text(row, "recall_sha256")))) {
        throw ModelValues.invalid();
      }
      frames.add(
          new VideoFrameRecall(
              new VideoFrame(
                  ordinal,
                  AuthorityRows.number(row, "presentation_us"),
                  AuthorityRows.number(row, "duration_us"),
                  image,
                  AuthorityRows.integer(row, "width"),
                  AuthorityRows.integer(row, "height")),
              recall));
    }
    var spans =
        store
            .rows(
                "SELECT * FROM video_transcript_spans WHERE revision_id=? ORDER BY ordinal",
                revisionId)
            .stream()
            .map(
                row -> {
                  var span =
                      new AudioTranscriptSpan(
                          AuthorityRows.integer(row, "ordinal"),
                          AuthorityRows.number(row, "start_ms"),
                          AuthorityRows.number(row, "end_ms"),
                          AuthorityRows.text(row, "text"));
                  if (!span.textSha256().equals(AuthorityRows.text(row, "text_sha256"))) {
                    throw ModelValues.invalid();
                  }
                  return new VideoTranscriptEvidence(
                      AuthorityRows.text(row, "id"),
                      revisionId,
                      span,
                      row.get("index_ordinal") == null
                          ? null
                          : AuthorityRows.integer(row, "index_ordinal"));
                })
            .toList();
    var audio =
        header.get("audio_sample_count") == null
            ? null
            : new AudioTranscription(
                AuthorityRows.text(header, "source_sha256"),
                    AuthorityRows.text(header, "decoder_revision"),
                AuthorityRows.text(header, "audio_model_revision"),
                    AuthorityRows.text(header, "audio_transcription_revision"),
                AuthorityRows.number(header, "audio_sample_count"),
                    spans.stream().map(VideoTranscriptEvidence::span).toList());
    var subtitles = findVideoSubtitles(revisionId);
    var compilation =
        new VideoCompilation(
            AuthorityRows.text(header, "source_sha256"),
            AuthorityRows.text(header, "decoder_revision"),
            AuthorityRows.text(header, "compiler_revision"),
            AuthorityRows.number(header, "timeline_origin_us"),
            AuthorityRows.number(header, "duration_us"),
            frames,
            audio,
            findVideoOcrCompilation(revisionId),
            subtitles == null ? null : subtitles.compilation());
    var expected = VideoEvidence.fromCompilation(revisionId, compilation);
    if (expected.frames().size() != AuthorityRows.integer(header, "frame_count")
        || expected.spans().size() != AuthorityRows.integer(header, "span_count")
        || expected.groups().size() != AuthorityRows.integer(header, "group_count")
        || expected.projectionCount() != AuthorityRows.integer(header, "projection_count")
        || expected.frameBytes() != AuthorityRows.number(header, "frame_bytes")
        || !expected.manifestSha256().equals(AuthorityRows.text(header, "manifest_sha256"))
        || !expected.spans().equals(spans)
        || !expected.groups().equals(videoGroups(revisionId))) {
      throw ModelValues.invalid();
    }
    if (compilation.ocr() != null) {
      var ocrHeader =
          store
              .rows("SELECT * FROM video_ocr_compilations WHERE revision_id=?", revisionId)
              .getFirst();
      if (!expected.manifestSha256().equals(AuthorityRows.text(ocrHeader, "base_manifest_sha256"))
          || !VideoOcrEvidence.fromCompilation(revisionId, compilation)
              .manifestSha256()
              .equals(AuthorityRows.text(ocrHeader, "manifest_sha256"))) {
        throw ModelValues.invalid();
      }
    }
    if (!compilation.textEvidenceOnly()
        && compilation.compilerRevision().startsWith("java-video-compiler-v3:")
            != (subtitles != null)) {
      throw ModelValues.invalid();
    }
    if (subtitles != null) {
      var expectedSubtitles = VideoSubtitleEvidence.fromCompilation(revisionId, compilation);
      var expectedOcr =
          compilation.ocr() == null
              ? null
              : VideoOcrEvidence.fromCompilation(revisionId, compilation);
      if (!expected.manifestSha256().equals(subtitles.baseManifestSha256())
          || subtitles.ocrExpected() != (expectedOcr != null)
          || !Objects.equals(
              subtitles.ocrManifestSha256(),
              expectedOcr == null ? null : expectedOcr.manifestSha256())
          || !expectedSubtitles.tracks().equals(subtitles.tracks())
          || !expectedSubtitles.manifestSha256().equals(subtitles.manifestSha256())) {
        throw ModelValues.invalid();
      }
    }
    return Optional.of(compilation);
  }

  /** Complete validated original video product; callers must hold the authority transaction. */
  public static Optional<VideoCompilation> readVideoCompilation(
      SqliteAuthorityStore store, String revisionId) {
    return new IngestionRepository(store).findVideoCompilation(revisionId);
  }

  /** Complete subtitle sidecar, never a partial per-hit interpretation of its manifest. */
  public static Optional<VideoSubtitleEvidence> readVideoSubtitleEvidence(
      SqliteAuthorityStore store, String revisionId) {
    return readVideoCompilation(store, revisionId)
        .filter(compilation -> compilation.subtitles() != null)
        .map(compilation -> VideoSubtitleEvidence.fromCompilation(revisionId, compilation));
  }

  private StoredSubtitles findVideoSubtitles(String revisionId) {
    var headers =
        store.rows("SELECT * FROM video_subtitle_compilations WHERE revision_id=?", revisionId);
    if (headers.isEmpty()) {
      return null;
    }
    var header = headers.getFirst();
    var tracks = new java.util.ArrayList<VideoSubtitleTrackEvidence>();
    for (var row :
        store.rows(
            "SELECT * FROM video_subtitle_tracks WHERE revision_id=? ORDER BY stream_index",
            revisionId)) {
      String trackId = AuthorityRows.text(row, "id");
      var cues = new java.util.ArrayList<VideoSubtitleCueEvidence>();
      for (var packet :
          store.rows(
              "SELECT * FROM video_subtitle_cues WHERE revision_id=? AND track_id=? ORDER BY ordinal",
              revisionId,
              trackId)) {
        var cue =
            new VideoSubtitleCue(
                AuthorityRows.integer(packet, "ordinal"),
                AuthorityRows.number(packet, "pts"),
                AuthorityRows.number(packet, "duration"),
                AuthorityRows.text(packet, "text"),
                AuthorityRows.text(packet, "payload_sha256"));
        var evidence =
            new VideoSubtitleCueEvidence(
                AuthorityRows.text(packet, "id"),
                revisionId,
                trackId,
                AuthorityRows.integer(packet, "stream_index"),
                cue,
                AuthorityRows.integer(packet, "start_offset"),
                AuthorityRows.integer(packet, "end_offset"),
                packet.get("start_us") == null ? null : AuthorityRows.number(packet, "start_us"),
                packet.get("end_us") == null ? null : AuthorityRows.number(packet, "end_us"),
                packet.get("index_ordinal") == null
                    ? null
                    : AuthorityRows.integer(packet, "index_ordinal"));
        if (!evidence.textSha256().equals(AuthorityRows.text(packet, "text_sha256"))) {
          throw ModelValues.invalid();
        }
        cues.add(evidence);
      }
      if (cues.size() != AuthorityRows.integer(row, "cue_count")) {
        throw ModelValues.invalid();
      }
      var track =
          new VideoSubtitleTrack(
              AuthorityRows.integer(row, "stream_index"), AuthorityRows.text(row, "codec"),
              AuthorityRows.number(row, "time_base_numerator"),
                  AuthorityRows.number(row, "time_base_denominator"),
              AuthorityRows.text(row, "language"),
                  cues.stream().map(VideoSubtitleCueEvidence::cue).toList());
      tracks.add(
          new VideoSubtitleTrackEvidence(
              trackId,
              revisionId,
              track,
              AuthorityRows.text(row, "text"),
              AuthorityRows.text(row, "text_sha256"),
              cues));
    }
    var nativeProduct =
        new VideoSubtitleCompilation(
            AuthorityRows.number(header, "video_epoch_pts"),
            AuthorityRows.number(header, "video_time_base_numerator"),
            AuthorityRows.number(header, "video_time_base_denominator"),
            tracks.stream().map(VideoSubtitleTrackEvidence::track).toList());
    int cues = tracks.stream().mapToInt(track -> track.cues().size()).sum();
    int projections =
        (int)
            tracks.stream()
                .flatMap(track -> track.cues().stream())
                .filter(cue -> cue.indexOrdinal() != null)
                .count();
    int ocrExpected = AuthorityRows.integer(header, "ocr_expected");
    if (tracks.size() != AuthorityRows.integer(header, "track_count")
        || cues != AuthorityRows.integer(header, "cue_count")
        || cues
            != store.count(
                "SELECT COUNT(*) FROM video_subtitle_cues WHERE revision_id=?", revisionId)
        || projections != AuthorityRows.integer(header, "projection_count")
        || !nativeProduct
            .manifestSha256()
            .equals(AuthorityRows.text(header, "native_manifest_sha256"))
        || (ocrExpected != 0 && ocrExpected != 1)) {
      throw ModelValues.invalid();
    }
    return new StoredSubtitles(
        nativeProduct,
        List.copyOf(tracks),
        AuthorityRows.text(header, "base_manifest_sha256"),
        AuthorityRows.text(header, "ocr_manifest_sha256"),
        ocrExpected == 1,
        AuthorityRows.text(header, "manifest_sha256"));
  }

  private record StoredSubtitles(
      VideoSubtitleCompilation compilation,
      List<VideoSubtitleTrackEvidence> tracks,
      String baseManifestSha256,
      String ocrManifestSha256,
      boolean ocrExpected,
      String manifestSha256) {
    @Override
    public String toString() {
      return "StoredSubtitles[redacted]";
    }
  }

  private VideoOcrCompilation findVideoOcrCompilation(String revisionId) {
    var headers =
        store.rows("SELECT * FROM video_ocr_compilations WHERE revision_id=?", revisionId);
    if (headers.isEmpty()) {
      return null;
    }
    var header = headers.getFirst();
    var frames =
        store
            .rows(
                "SELECT frame_id FROM video_frame_ocr WHERE revision_id=? ORDER BY ordinal",
                revisionId)
            .stream()
            .map(row -> readVideoFrameOcr(store, revisionId, AuthorityRows.text(row, "frame_id")))
            .toList();
    if (frames.size() != AuthorityRows.integer(header, "frame_count")
        || frames.stream().mapToInt(frame -> frame.segments().size()).sum()
            != AuthorityRows.integer(header, "projection_count")) {
      throw ModelValues.invalid();
    }
    return new VideoOcrCompilation(AuthorityRows.text(header, "ocr_revision"), frames);
  }

  static VideoFrameOcr readVideoFrameOcr(
      SqliteAuthorityStore store, String revisionId, String frameId) {
    var rows =
        store.rows(
            "SELECT * FROM video_frame_ocr WHERE revision_id=? AND frame_id=?",
            revisionId,
            frameId);
    if (rows.size() != 1) {
      throw ModelValues.invalid();
    }
    var row = rows.getFirst();
    String text = AuthorityRows.text(row, "text");
    if (!sha(text).equals(AuthorityRows.text(row, "text_sha256"))) {
      throw ModelValues.invalid();
    }
    var segments =
        store
            .rows(
                "SELECT * FROM video_ocr_segments WHERE revision_id=? AND frame_id=? ORDER BY ordinal",
                revisionId,
                frameId)
            .stream()
            .map(
                segment -> {
                  var value =
                      new VideoOcrSegment(
                          AuthorityRows.integer(segment, "ordinal"),
                          AuthorityRows.integer(segment, "start_offset"),
                          AuthorityRows.integer(segment, "end_offset"),
                          AuthorityRows.text(segment, "text"));
                  if (!sha(value.text()).equals(AuthorityRows.text(segment, "text_sha256"))
                      || !VideoOcrSegmentEvidence.identity(revisionId, frameId, value.ordinal())
                          .equals(AuthorityRows.text(segment, "id"))) {
                    throw ModelValues.invalid();
                  }
                  return value;
                })
            .toList();
    var regions =
        store
            .rows(
                "SELECT * FROM video_ocr_regions WHERE revision_id=? AND frame_id=? ORDER BY ordinal",
                revisionId,
                frameId)
            .stream()
            .map(
                region ->
                    new ImageTextRegion(
                        AuthorityRows.integer(region, "start_offset"),
                        AuthorityRows.integer(region, "end_offset"),
                        AuthorityRows.integer(region, "left_pixel"),
                        AuthorityRows.integer(region, "top_pixel"),
                        AuthorityRows.integer(region, "right_pixel"),
                        AuthorityRows.integer(region, "bottom_pixel")))
            .toList();
    if (segments.size() != AuthorityRows.integer(row, "segment_count")
        || regions.size() != AuthorityRows.integer(row, "region_count")) {
      throw ModelValues.invalid();
    }
    return new VideoFrameOcr(
        AuthorityRows.integer(row, "ordinal"),
        AuthorityRows.text(row, "frame_sha256"),
        new ImageDimensions(
            AuthorityRows.integer(row, "width"), AuthorityRows.integer(row, "height")),
        text,
        segments,
        regions);
  }

  public List<VideoEvidenceGroup> findVideoGroups(String revisionId) {
    return findVideoCompilation(revisionId).isEmpty() ? List.of() : videoGroups(revisionId);
  }

  private List<VideoEvidenceGroup> videoGroups(String revisionId) {
    return store
        .rows(
            "SELECT * FROM video_evidence_groups WHERE revision_id=? ORDER BY ordinal", revisionId)
        .stream()
        .map(
            row ->
                new VideoEvidenceGroup(
                    AuthorityRows.text(row, "id"),
                    revisionId,
                    AuthorityRows.integer(row, "ordinal"),
                    AuthorityRows.number(row, "start_us"),
                    AuthorityRows.number(row, "end_us"),
                    row.get("frame_id") == null ? null : AuthorityRows.text(row, "frame_id"),
                    row.get("transcript_span_id") == null
                        ? null
                        : AuthorityRows.text(row, "transcript_span_id")))
        .toList();
  }

  public Optional<AudioCompilation> findAudioCompilation(String revisionId) {
    var headers = store.rows("SELECT * FROM audio_compilations WHERE revision_id=?", revisionId);
    if (headers.isEmpty()) {
      return Optional.empty();
    }
    var header = headers.getFirst();
    var evidence =
        store
            .rows("SELECT * FROM audio_spans WHERE revision_id=? ORDER BY ordinal", revisionId)
            .stream()
            .map(
                row ->
                    new AudioEvidence(
                        AuthorityRows.text(row, "id"),
                        AuthorityRows.text(row, "revision_id"),
                        AuthorityRows.integer(row, "ordinal"),
                        AuthorityRows.number(row, "start_ms"),
                        AuthorityRows.number(row, "end_ms"),
                        AuthorityRows.text(row, "text"),
                        AuthorityRows.text(row, "text_sha256"),
                        row.get("index_ordinal") == null
                            ? null
                            : AuthorityRows.integer(row, "index_ordinal")))
            .toList();
    var compilation =
        new AudioCompilation(
            AuthorityRows.text(header, "source_sha256"),
            AuthorityRows.text(header, "decoder_revision"),
            AuthorityRows.text(header, "model_revision"),
            AuthorityRows.text(header, "compiler_revision"),
            AuthorityRows.number(header, "duration_ms"),
            evidence.stream()
                .map(
                    span ->
                        new AudioTranscriptSpan(
                            span.ordinal(), span.startMs(), span.endMs(), span.text()))
                .toList());
    if (evidence.size() != AuthorityRows.integer(header, "span_count")
        || evidence.stream().filter(span -> span.indexOrdinal() != null).count()
            != AuthorityRows.integer(header, "projection_count")
        || !evidence.equals(AudioEvidence.fromCompilation(revisionId, compilation))
        || !transcriptSha256(compilation).equals(AuthorityRows.text(header, "transcript_sha256"))) {
      throw ModelValues.invalid();
    }
    return Optional.of(compilation);
  }

  private static String transcriptSha256(AudioCompilation compilation) {
    String transcript =
        compilation.spans().stream()
            .map(AudioTranscriptSpan::text)
            .collect(Collectors.joining("\n"));
    return ModelValues.sha256(transcript.getBytes(StandardCharsets.UTF_8));
  }

  public void markParsed(
      String jobId, String documentId, String revisionId, int pages, int segments, String now) {
    store.execute(
        "UPDATE corpus_revisions SET parsed_at=?,page_count=?,segment_count=? WHERE id=?",
        now,
        pages,
        segments,
        revisionId);
    var replacement = new DocumentUpdateRepository(store).findByRevision(documentId, revisionId);
    if (replacement.isEmpty()) {
      store.execute(
          "UPDATE corpus_documents SET parsed_revision_id=? WHERE document_id=?",
          revisionId,
          documentId);
    } else {
      new DocumentUpdateRepository(store).markParsed(replacement.get().id(), now);
    }
    store.execute(
        "UPDATE ingestion_jobs SET state='parsed',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
    if (replacement.isEmpty()) {
      store.execute("UPDATE documents SET updated_at=? WHERE id=?", now, documentId);
    }
  }

  public void markFailed(String jobId, String safeCode, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='failed',claim_token_sha256=NULL,error_code=?,updated_at=? WHERE id=?",
        safeCode,
        now,
        jobId);
    replacementState(jobId, "failed", now);
  }

  public void markCancelled(String jobId, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='cancelled',claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        now,
        jobId);
    replacementState(jobId, "cancelled", now);
  }

  public void markQueued(String jobId, int attempt, String now) {
    store.execute(
        "UPDATE ingestion_jobs SET state='queued',attempt=?,claim_token_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?",
        attempt,
        now,
        jobId);
    replacementState(jobId, "queued", now);
  }

  private void replacementState(String jobId, String state, String now) {
    store
        .rows(
            "SELECT replacement_id FROM ingestion_jobs WHERE id=? AND replacement_id IS NOT NULL",
            jobId)
        .stream()
        .findFirst()
        .ifPresent(
            row ->
                new DocumentUpdateRepository(store)
                    .state(AuthorityRows.text(row, "replacement_id"), state, now));
  }

  public List<String> processingIds() {
    return store.rows("SELECT id FROM ingestion_jobs WHERE state='processing'").stream()
        .map(row -> AuthorityRows.text(row, "id"))
        .toList();
  }

  public Optional<String> authorizedParsedRevision(Actor actor, String documentId) {
    return store
        .rows(
            "SELECT c.parsed_revision_id FROM corpus_documents c JOIN documents d ON d.id=c.document_id WHERE d.id=? AND d.workspace_id=? AND c.parsed_revision_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM document_tombstones t WHERE t.document_id=d.id)",
            documentId,
            actor.workspaceId())
        .stream()
        .findFirst()
        .map(row -> AuthorityRows.text(row, "parsed_revision_id"));
  }

  public ParsedText parsedEvidence(String revisionId) {
    var pages =
        store
            .rows(
                "SELECT page_number,text FROM corpus_pages WHERE revision_id=? ORDER BY page_number",
                revisionId)
            .stream()
            .map(
                row ->
                    new TextPage(
                        AuthorityRows.integer(row, "page_number"), AuthorityRows.text(row, "text")))
            .toList();
    var segments =
        store
            .rows(
                "SELECT ordinal,page_number,start_offset,end_offset,text FROM corpus_segments WHERE revision_id=? ORDER BY ordinal",
                revisionId)
            .stream()
            .map(
                row ->
                    new TextSegment(
                        AuthorityRows.integer(row, "ordinal"),
                        AuthorityRows.integer(row, "page_number"),
                        AuthorityRows.integer(row, "start_offset"),
                        AuthorityRows.integer(row, "end_offset"),
                        AuthorityRows.text(row, "text")))
            .toList();
    return new ParsedText(pages, segments);
  }
}
