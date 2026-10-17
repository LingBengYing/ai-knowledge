package com.evidence.rag.service;

import static com.evidence.rag.model.domain.ModelValues.identifier;
import static com.evidence.rag.model.domain.ModelValues.invalid;
import static com.evidence.rag.model.domain.ModelValues.notFound;
import static com.evidence.rag.model.domain.ModelValues.sha256;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.AudioCompilation;
import com.evidence.rag.model.domain.DocumentFormat;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.ImageEvidence;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.ParsedText;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.SyntheticDocument;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.model.dto.TaskResult;
import com.evidence.rag.model.entity.AuditEventEntity;
import com.evidence.rag.model.entity.TaskEntity;
import com.evidence.rag.repository.DocumentUpdateRepository;
import com.evidence.rag.repository.ImportIndexRepository;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ingestion admission, fencing and evidence acceptance. Remote parsing never holds a transaction.
 */
public final class IngestionService {
  private boolean automaticIndexingEnabled = true;

  public void setAutomaticIndexingEnabled(boolean enabled) {
    automaticIndexingEnabled = enabled;
  }

  private static final long MAX_ORIGINAL_BYTES = 256L * 1024 * 1024;
  private static final int MAX_PENDING_INGESTIONS = 32;
  private int pendingCapacity = MAX_PENDING_INGESTIONS;

  public void configureQueueCapacity(int value) {
    if (value < 32 || value > 10000)
      throw new IllegalArgumentException("Invalid ingestion queue capacity");
    pendingCapacity = value;
  }

  private static final Set<String> ERRORS =
      Set.of(
          "unsupported_document",
          "text_configuration_required",
          "media_text_configuration_mismatch",
          "parser_failed",
          "parser_timeout",
          "parser_output_invalid",
          "worker_interrupted");
  private final SqliteAuthorityStore store;
  private final IngestionRepository ingestion;
  private final ManagementRepository management;
  private final DocumentPermissionPolicy permissions;
  private final ImageOcrOptions images;
  private final VisualIngestionOptions visual;
  private final String audioCompilerRevision;
  private final String videoCompilerRevision;
  private final boolean videoOcrExpected;
  private final boolean videoSubtitlesExpected;
  private final PdfOcrOptions pdfs;

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions) {
    this(store, ingestion, management, permissions, null);
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images) {
    this(store, ingestion, management, permissions, images, null);
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images,
      VisualIngestionOptions visual) {
    this(store, ingestion, management, permissions, images, visual, null);
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images,
      VisualIngestionOptions visual,
      String audioCompilerRevision) {
    this(store, ingestion, management, permissions, images, visual, audioCompilerRevision, null);
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images,
      VisualIngestionOptions visual,
      String audioCompilerRevision,
      String videoCompilerRevision) {
    this(
        store,
        ingestion,
        management,
        permissions,
        images,
        visual,
        audioCompilerRevision,
        videoCompilerRevision,
        videoCompilerRevision != null
            && videoCompilerRevision.startsWith("java-video-compiler-v2:"));
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images,
      VisualIngestionOptions visual,
      String audioCompilerRevision,
      String videoCompilerRevision,
      boolean videoOcrExpected) {
    this(
        store,
        ingestion,
        management,
        permissions,
        images,
        visual,
        audioCompilerRevision,
        videoCompilerRevision,
        videoOcrExpected,
        null);
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images,
      VisualIngestionOptions visual,
      String audioCompilerRevision,
      String videoCompilerRevision,
      boolean videoOcrExpected,
      PdfOcrOptions pdfs) {
    this(
        store,
        ingestion,
        management,
        permissions,
        images,
        visual,
        audioCompilerRevision,
        videoCompilerRevision,
        videoOcrExpected,
        videoCompilerRevision != null
            && videoCompilerRevision.startsWith("java-video-compiler-v3:"),
        pdfs);
  }

  public IngestionService(
      SqliteAuthorityStore store,
      IngestionRepository ingestion,
      ManagementRepository management,
      DocumentPermissionPolicy permissions,
      ImageOcrOptions images,
      VisualIngestionOptions visual,
      String audioCompilerRevision,
      String videoCompilerRevision,
      boolean videoOcrExpected,
      boolean videoSubtitlesExpected,
      PdfOcrOptions pdfs) {
    this.store = Objects.requireNonNull(store);
    this.ingestion = Objects.requireNonNull(ingestion);
    this.management = Objects.requireNonNull(management);
    this.permissions = Objects.requireNonNull(permissions);
    this.images = images;
    this.visual = visual;
    if (audioCompilerRevision != null
        && !audioCompilerRevision.matches("java-audio-compiler-v1:[0-9a-f]{64}")) {
      throw invalid();
    }
    this.audioCompilerRevision = audioCompilerRevision;
    if (videoCompilerRevision != null
        && !videoCompilerRevision.matches("java-video-compiler-v[1234]:[0-9a-f]{64}")) {
      throw invalid();
    }
    if (!VideoCompilation.isTextEvidenceOnlyRevision(videoCompilerRevision)
        && videoSubtitlesExpected
            != (videoCompilerRevision != null
                && videoCompilerRevision.startsWith("java-video-compiler-v3:"))) {
      throw invalid();
    }
    this.videoCompilerRevision = videoCompilerRevision;
    this.videoOcrExpected = videoOcrExpected;
    this.videoSubtitlesExpected = videoSubtitlesExpected;
    this.pdfs = pdfs;
  }

  public int maximumUploadBytes() {
    return TextParser.MAX_BYTES;
  }

  /** Admits file metadata before Web receives bytes; full content is revalidated on upload. */
  public String prepareUpload(String filename) {
    try {
      if (audioEnabled(filename)) {
        AudioInput.validateMetadata(filename, "application/octet-stream");
        return AudioInput.canonicalMime(filename);
      }
      if (imageEnabled(filename)) {
        ImageInput.validateMetadata(filename, "application/octet-stream");
        return ImageInput.canonicalMime(filename);
      }
      TextParser.validateMetadata(filename, "application/octet-stream");
      return canonicalMime(filename);
    } catch (TextParser.Failure failure) {
      throw unsupportedDocument();
    }
  }

  public String prepareUpload(String filename, String contentType) {
    if ("application/octet-stream".equalsIgnoreCase(contentType)) {
      return prepareUpload(filename);
    }
    try {
      if (!videoEnabled(filename, contentType)) {
        throw unsupportedDocument();
      }
      VideoInput.validateMetadata(filename, contentType);
      return VideoInput.canonicalMime(filename);
    } catch (TextParser.Failure failure) {
      throw unsupportedDocument();
    }
  }

  private static String canonicalMime(String filename) {
    return DocumentFormat.fromFilename(filename)
        .orElseThrow(() -> new TextParser.Failure("unsupported_document"))
        .mediaType();
  }

  public void validateUploadEnvelope(String filename, String mime, byte[] content) {
    try {
      if (videoRequested(mime)) {
        if (!videoEnabled(filename, mime)) {
          throw unsupportedDocument();
        }
        VideoInput.validateEnvelope(filename, mime, content);
      } else if (audioEnabled(filename)) {
        AudioInput.validateEnvelope(filename, mime, content);
      } else if (imageEnabled(filename)) {
        ImageInput.validateEnvelope(filename, mime, content);
      } else {
        TextParser.validateEnvelope(filename, mime, content);
      }
    } catch (TextParser.Failure failure) {
      throw unsupportedDocument();
    }
  }

  private boolean imageEnabled(String filename) {
    return (images != null || visual != null) && ImageInput.isImageName(filename);
  }

  private boolean audioEnabled(String filename) {
    return audioCompilerRevision != null && AudioInput.isAudioName(filename);
  }

  private static boolean videoRequested(String mime) {
    return mime != null && mime.startsWith("video/");
  }

  private boolean videoEnabled(String filename, String mime) {
    return videoCompilerRevision != null
        && VideoInput.isVideoName(filename)
        && VideoInput.canonicalMime(filename).equals(mime);
  }

  private String parserRevision(String filename) {
    if (audioEnabled(filename)) {
      return audioCompilerRevision;
    }
    if (!imageEnabled(filename)) {
      return pdfs != null && isPdf(filename)
          ? pdfs.parserRevision()
          : TextParser.revisionFor(filename);
    }
    return visual != null ? visual.parserRevision() : images.parserRevision();
  }

  private static boolean isPdf(String filename) {
    return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }

  private ApplicationException unsupportedDocument() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT,
        "unsupported_document",
        videoCompilerRevision != null
            ? "仅支持已启用且符合文件格式和大小限制的资料；视频需显式选择受支持的视频内容类型。"
            : audioCompilerRevision != null
                ? "仅支持已启用且符合文件格式和大小限制的文本、图片或音频资料。"
                : images == null && visual == null
                    ? "仅支持已启用且符合文件格式和大小限制的文档资料。"
                    : "仅支持已启用且符合文件格式和大小限制的文档或图片资料。");
  }

  public TaskResult uploadDocument(Actor owner, String filename, String mime, byte[] content) {
    return store.transaction(() -> uploadDocumentInTransaction(owner, filename, mime, content));
  }

  /** Package seam for atomic batch slot admission and original/task creation. */
  TaskResult uploadDocumentInTransaction(
      Actor owner, String filename, String mime, byte[] content) {
    if (owner == null
        || content == null
        || content.length == 0
        || content.length > TextParser.MAX_BYTES) {
      throw invalid();
    }
    byte[] original = content.clone();
    validateUploadEnvelope(filename, mime, original);
    boolean video = videoEnabled(filename, mime);
    boolean image = imageEnabled(filename);
    boolean audio = audioEnabled(filename);
    String canonicalMime =
        video
            ? VideoInput.canonicalMime(filename)
            : audio
                ? AudioInput.canonicalMime(filename)
                : image ? ImageInput.canonicalMime(filename) : canonicalMime(filename);
    String parserRevision = video ? videoCompilerRevision : parserRevision(filename);
    String sourceHash = sha256(original);
    checkPendingQuota(owner.workspaceId());
    if (ingestion.storedBytes(owner.workspaceId()) > MAX_ORIGINAL_BYTES - original.length) {
      throw quotaExceeded();
    }
    String documentId = UUID.randomUUID().toString(), revisionId = UUID.randomUUID().toString();
    String jobId = UUID.randomUUID().toString(), now = Instant.now().toString();
    management.insertDocument(
        owner,
        new SyntheticDocument(
            documentId,
            filename,
            video ? "video" : audio ? "audio" : image ? "image" : "document",
            canonicalMime,
            revisionId,
            sourceHash,
            original.length),
        now);
    management.insertGrant(documentId, owner.principalId(), "owner");
    ingestion.insertOriginal(documentId, revisionId, parserRevision, sourceHash, original, now);
    ingestion.insertJob(jobId, documentId, revisionId, owner.principalId(), now);
    if (automaticIndexingEnabled) {
      new ImportIndexRepository(store).insert(owner, documentId, revisionId, "corpus", null, null);
    }
    audit(
        owner,
        documentId,
        "ingestion_queued",
        null,
        values(
            "revision_id",
            revisionId,
            "source_sha256",
            sourceHash,
            "size_bytes",
            original.length,
            "state",
            "queued"),
        Set.of("revision_id", "source_sha256", "size_bytes", "state"));
    var task = authorizedTask(owner, jobId, false);
    return TaskResults.ingestion(
        task, permissions.canEdit(task.currentRole()), creatorCanWrite(task, owner.workspaceId()));
  }

  /** Called only in the replacement admission transaction after its original is saved. */
  TaskResult enqueueReplacementInTransaction(
      Actor actor, String replacementId, DocumentOriginal candidate) {
    permissions.require(management.currentRole(actor, candidate.documentId()), true);
    checkPendingQuota(actor.workspaceId());
    validateUploadEnvelope(candidate.filename(), candidate.mediaType(), candidate.content());
    String parser =
        videoEnabled(candidate.filename(), candidate.mediaType())
            ? videoCompilerRevision
            : parserRevision(candidate.filename());
    String jobId = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    ingestion.insertReplacementJob(
        jobId,
        candidate.documentId(),
        candidate.revisionId(),
        actor.principalId(),
        replacementId,
        parser,
        candidate.sourceSha256(),
        now);
    var task = authorizedTask(actor, jobId, false);
    return TaskResults.ingestion(task, true, true);
  }

  public Optional<IngestionClaim> claimIngestion(String workspaceId) {
    Actor worker = new Actor(workspaceId, "system:ingestion");
    return store.transaction(
        () -> {
          for (int candidate = 0; candidate < MAX_PENDING_INGESTIONS; candidate++) {
            var jobId = ingestion.nextQueuedId(workspaceId).orElse(null);
            if (jobId == null) {
              return Optional.empty();
            }
            var task = ingestion.findInternalTask(jobId).orElseThrow();
            if (cancelIfUnauthorized(task)) {
              continue;
            }
            if (new com.evidence.rag.repository.DocumentBatchRepository(store)
                .haltedImportIngestion(jobId)) {
              // A halted batch starts no more automatic work: its own automatic import task ends
              // visibly instead of waiting forever. An explicit user retry runs.
              ingestion.markCancelled(jobId, Instant.now().toString());
              audit(
                  worker,
                  task.documentId(),
                  "ingestion_cancelled",
                  values("state", "queued"),
                  values("state", "cancelled"),
                  Set.of("state"));
              continue;
            }
            if (!new DocumentUpdateRepository(store).replacementCurrent(jobId)) {
              finishFailed(task, "parser_output_invalid");
              continue;
            }
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            ingestion.markProcessing(
                jobId, sha256(token.getBytes(StandardCharsets.UTF_8)), Instant.now().toString());
            audit(
                worker,
                task.documentId(),
                "ingestion_claimed",
                values("state", "queued"),
                values("state", "processing", "attempt", task.attempt()),
                Set.of("state", "attempt"));
            return Optional.of(
                new IngestionClaim(
                    jobId,
                    task.documentId(),
                    task.revisionId(),
                    workspaceId,
                    task.attempt(),
                    token,
                    task.filename(),
                    task.mimeType(),
                    task.parserRevision(),
                    ingestion.original(task.documentId(), task.revisionId())));
          }
          return Optional.empty();
        });
  }

  public boolean completeIngestion(IngestionClaim claim, ParsedText parsed) {
    return completeParsed(claim, parsed, null);
  }

  public boolean completeImageIngestion(IngestionClaim claim, ParsedImage image) {
    return completeParsed(claim, image == null ? null : image.text(), image);
  }

  public boolean completeAudioIngestion(IngestionClaim claim, AudioCompilation compilation) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (audioCompilerRevision == null
              || !AudioInput.isAudioName(task.filename())
              || !audioCompilerRevision.equals(task.parserRevision())
              || !sha256(claim.content()).equals(task.sourceSha256())) {
            throw invalidParserOutput();
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          if (compilation == null
              || !audioCompilerRevision.equals(compilation.compilerRevision())
              || !task.sourceSha256().equals(compilation.sourceSha256())) {
            throw invalidParserOutput();
          }
          return persistCandidate(
              claim,
              () -> {
                String now = Instant.now().toString();
                ingestion.insertAudioCompilation(claim.revisionId(), compilation, now);
                ingestion.markParsed(
                    claim.jobId(), claim.documentId(), claim.revisionId(), 0, 0, now);
                audit(
                    new Actor(claim.workspaceId(), "system:ingestion"),
                    claim.documentId(),
                    "ingestion_parsed",
                    values("state", "processing"),
                    values(
                        "state", "parsed",
                        "revision_id", claim.revisionId(),
                        "page_count", 0,
                        "segment_count", 0,
                        "audio_span_count", compilation.spans().size(),
                        "audio_indexable_count",
                            compilation.spans().stream()
                                .filter(span -> !span.text().isBlank())
                                .count(),
                        "duration_ms", compilation.durationMs(),
                        "compiler_revision", compilation.compilerRevision()),
                    Set.of(
                        "state",
                        "revision_id",
                        "page_count",
                        "segment_count",
                        "audio_span_count",
                        "audio_indexable_count",
                        "duration_ms",
                        "compiler_revision"));
                return true;
              });
        });
  }

  public boolean completeVideoIngestion(IngestionClaim claim, VideoCompilation compilation) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (!videoEnabled(task.filename(), task.mimeType())
              || !videoCompilerRevision.equals(task.parserRevision())
              || !sha256(claim.content()).equals(task.sourceSha256())) {
            throw invalidParserOutput();
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          if (compilation == null
              || !videoCompilerRevision.equals(compilation.compilerRevision())
              || !task.sourceSha256().equals(compilation.sourceSha256())) {
            throw invalidParserOutput();
          }
          if (videoSubtitlesExpected != (compilation.subtitles() != null)
              || videoOcrExpected != (compilation.ocr() != null)) {
            throw invalidParserOutput();
          }
          validateVideoFrames(compilation);
          return persistCandidate(
              claim,
              () -> {
                String now = Instant.now().toString();
                ingestion.insertVideoCompilation(claim.revisionId(), compilation, now);
                ingestion.markParsed(
                    claim.jobId(), claim.documentId(), claim.revisionId(), 0, 0, now);
                audit(
                    new Actor(claim.workspaceId(), "system:ingestion"),
                    claim.documentId(),
                    "ingestion_parsed",
                    values("state", "processing"),
                    values(
                        "state",
                        "parsed",
                        "revision_id",
                        claim.revisionId(),
                        "page_count",
                        0,
                        "segment_count",
                        0,
                        "video_frame_count",
                        compilation.frames().size(),
                        "video_audio_span_count",
                        compilation.audio() == null ? 0 : compilation.audio().spans().size(),
                        "duration_us",
                        compilation.durationUs(),
                        "compiler_revision",
                        compilation.compilerRevision()),
                    Set.of(
                        "state",
                        "revision_id",
                        "page_count",
                        "segment_count",
                        "video_frame_count",
                        "video_audio_span_count",
                        "duration_us",
                        "compiler_revision"));
                return true;
              });
        });
  }

  private static void validateVideoFrames(VideoCompilation compilation) {
    try {
      for (var recall : compilation.frames()) {
        var frame = recall.frame();
        var image = frame.image();
        byte[] content = image.content();
        ImageInput.validateEnvelope(
            image.mediaType().equals("image/png") ? "frame.png" : "frame.jpeg",
            image.mediaType(),
            content);
        var dimensions = ImageInput.inspect(content);
        if (dimensions.width() != frame.width() || dimensions.height() != frame.height()) {
          throw invalidParserOutput();
        }
      }
    } catch (TextParser.Failure failure) {
      throw invalidParserOutput();
    }
  }

  public boolean completeVisualIngestion(IngestionClaim claim, ImageRecall recall) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (visual == null
              || !ImageInput.isImageName(task.filename())
              || !visual.parserRevision().equals(task.parserRevision())
              || !sha256(claim.content()).equals(task.sourceSha256())) {
            throw invalidParserOutput();
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          if (recall == null || !visual.modelRevision().equals(recall.modelRevision())) {
            throw invalidParserOutput();
          }
          var dimensions = imageDimensions(claim.content());
          String recallHash = sha256(recall.recallText().getBytes(StandardCharsets.UTF_8));
          String imageId =
              "image_"
                  + sha256(
                      (claim.workspaceId()
                              + "\u0000"
                              + claim.documentId()
                              + "\u0000"
                              + claim.revisionId()
                              + "\u0000visual-v1\u0000"
                              + task.sourceSha256()
                              + "\u0000"
                              + recallHash
                              + "\u0000"
                              + recall.modelRevision())
                          .getBytes(StandardCharsets.UTF_8));
          return persistCandidate(
              claim,
              () -> {
                String now = Instant.now().toString();
                ingestion.insertImageEvidence(
                    new ImageEvidence(
                        imageId,
                        claim.revisionId(),
                        dimensions.width(),
                        dimensions.height(),
                        recall.recallText(),
                        recallHash,
                        recall.modelRevision()),
                    now);
                ingestion.markParsed(
                    claim.jobId(), claim.documentId(), claim.revisionId(), 0, 0, now);
                audit(
                    new Actor(claim.workspaceId(), "system:ingestion"),
                    claim.documentId(),
                    "ingestion_parsed",
                    values("state", "processing"),
                    values(
                        "state",
                        "parsed",
                        "revision_id",
                        claim.revisionId(),
                        "page_count",
                        0,
                        "segment_count",
                        0,
                        "image_evidence_id",
                        imageId,
                        "recall_sha256",
                        recallHash,
                        "description_revision",
                        recall.modelRevision()),
                    Set.of(
                        "state",
                        "revision_id",
                        "page_count",
                        "segment_count",
                        "image_evidence_id",
                        "recall_sha256",
                        "description_revision"));
                return true;
              });
        });
  }

  private static com.evidence.rag.model.domain.ImageDimensions imageDimensions(byte[] original) {
    try {
      return ImageInput.inspect(original);
    } catch (TextParser.Failure failure) {
      throw invalidParserOutput();
    }
  }

  private boolean completeParsed(IngestionClaim claim, ParsedText parsed, ParsedImage image) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          String expectedRevision =
              ImageInput.isImageName(task.filename())
                  ? images == null ? null : images.parserRevision()
                  : pdfs != null && isPdf(task.filename())
                      ? pdfs.parserRevision()
                      : TextParser.revisionFor(task.filename());
          if (!Objects.equals(expectedRevision, task.parserRevision())
              || !sha256(claim.content()).equals(task.sourceSha256())) {
            throw invalidParserOutput();
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          if (!ImageInput.isImageName(task.filename())) {
            try {
              TextParser.validateEnvelope(task.filename(), task.mimeType(), claim.content());
            } catch (TextParser.Failure failure) {
              throw invalidParserOutput();
            }
          }
          validateParsed(parsed);
          if (imageEnabled(task.filename()) != (image != null)) {
            throw invalidParserOutput();
          }
          if (image != null) {
            validateImage(image, claim.content());
          }
          return persistCandidate(
              claim,
              () -> {
                ingestion.insertParsedText(claim, parsed);
                if (image != null) {
                  for (int ordinal = 0; ordinal < image.regions().size(); ordinal++) {
                    ingestion.insertImageRegion(
                        claim.revisionId(), ordinal, image.regions().get(ordinal));
                  }
                }
                ingestion.markParsed(
                    claim.jobId(),
                    claim.documentId(),
                    claim.revisionId(),
                    parsed.pages().size(),
                    parsed.segments().size(),
                    Instant.now().toString());
                audit(
                    new Actor(claim.workspaceId(), "system:ingestion"),
                    claim.documentId(),
                    "ingestion_parsed",
                    values("state", "processing"),
                    values(
                        "state",
                        "parsed",
                        "revision_id",
                        claim.revisionId(),
                        "page_count",
                        parsed.pages().size(),
                        "segment_count",
                        parsed.segments().size()),
                    Set.of("state", "revision_id", "page_count", "segment_count"));
                return true;
              });
        });
  }

  private boolean persistCandidate(IngestionClaim claim, Supplier<Boolean> persist) {
    var updates = new DocumentUpdateRepository(store);
    var replacement = updates.findByRevision(claim.documentId(), claim.revisionId()).orElse(null);
    return replacement == null
        ? persist.get()
        : updates.withCandidateSource(replacement.id(), persist);
  }

  private static void validateImage(ParsedImage image, byte[] original) {
    try {
      if (!ImageInput.inspect(original).equals(image.dimensions())) {
        throw invalidParserOutput();
      }
    } catch (TextParser.Failure failure) {
      throw invalidParserOutput();
    }
    if (image.text().pages().size() != 1
        || image.regions().isEmpty()
        || image.regions().size() > 50_000) {
      throw invalidParserOutput();
    }
    int[] points = image.text().pages().getFirst().text().codePoints().toArray();
    int covered = 0;
    for (var region : image.regions()) {
      if (region.start() < covered
          || region.end() <= region.start()
          || region.end() > points.length
          || region.left() < 0
          || region.top() < 0
          || region.right() <= region.left()
          || region.bottom() <= region.top()
          || region.right() > image.dimensions().width()
          || region.bottom() > image.dimensions().height()) {
        throw invalidParserOutput();
      }
      for (; covered < region.start(); covered++) {
        if (!Character.isWhitespace(points[covered]) && !Character.isSpaceChar(points[covered])) {
          throw invalidParserOutput();
        }
      }
      for (; covered < region.end(); covered++) {
        if (Character.isWhitespace(points[covered]) || Character.isSpaceChar(points[covered])) {
          throw invalidParserOutput();
        }
      }
    }
    for (; covered < points.length; covered++) {
      if (!Character.isWhitespace(points[covered]) && !Character.isSpaceChar(points[covered])) {
        throw invalidParserOutput();
      }
    }
  }

  public boolean failIngestion(IngestionClaim claim, String safeCode) {
    if (safeCode == null || !ERRORS.contains(safeCode)) {
      throw invalid();
    }
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task == null) {
            return false;
          }
          if (cancelIfUnauthorized(task)) {
            return false;
          }
          finishFailed(task, safeCode);
          return true;
        });
  }

  public boolean isIngestionClaimCurrent(IngestionClaim claim) {
    return store.transaction(
        () -> {
          var task = currentClaim(claim);
          if (task != null
              && !new DocumentUpdateRepository(store).replacementCurrent(claim.jobId())) {
            finishFailed(task, "parser_output_invalid");
            return false;
          }
          return task != null && !cancelIfUnauthorized(task);
        });
  }

  public TaskResult ingestionStatus(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, false);
          return TaskResults.ingestion(
              task,
              permissions.canEdit(task.currentRole()),
              creatorCanWrite(task, actor.workspaceId()));
        });
  }

  public TaskResult retryIngestion(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, true);
          if (!Set.of("failed", "cancelled").contains(task.state())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_state_conflict", "当前任务状态不能重试。");
          }
          if (task.attempt() >= 3) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_retry_limit", "该任务已达到三次尝试上限。");
          }
          if (!creatorCanWrite(task, actor.workspaceId())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "authorization_changed", "任务创建者已无当前写权限。");
          }
          if (!new DocumentUpdateRepository(store).replacementCurrent(jobId)) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_state_conflict", "候选原件或当前资料已变化。");
          }
          checkPendingQuota(actor.workspaceId());
          ingestion.markQueued(jobId, task.attempt() + 1, Instant.now().toString());
          audit(
              actor,
              task.documentId(),
              "ingestion_retried",
              values("state", task.state(), "attempt", task.attempt()),
              values("state", "queued", "attempt", task.attempt() + 1),
              Set.of("state", "attempt"));
          var updated = authorizedTask(actor, jobId, false);
          return TaskResults.ingestion(
              updated,
              permissions.canEdit(updated.currentRole()),
              creatorCanWrite(updated, actor.workspaceId()));
        });
  }

  public TaskResult cancelIngestion(Actor actor, String jobId) {
    return store.transaction(
        () -> {
          var task = authorizedTask(actor, jobId, true);
          if (!Set.of("queued", "processing").contains(task.state())) {
            throw new ApplicationException(
                FailureKind.CONFLICT, "ingestion_state_conflict", "当前任务状态不能取消。");
          }
          // Cancelling one file never stops its batch; its siblings continue.
          ingestion.markCancelled(jobId, Instant.now().toString());
          audit(
              actor,
              task.documentId(),
              "ingestion_cancelled",
              values("state", task.state()),
              values("state", "cancelled"),
              Set.of("state"));
          var updated = authorizedTask(actor, jobId, false);
          return TaskResults.ingestion(
              updated,
              permissions.canEdit(updated.currentRole()),
              creatorCanWrite(updated, actor.workspaceId()));
        });
  }

  public ParsedText parsedEvidence(Actor actor, String documentId) {
    return store.transaction(
        () -> {
          String revisionId =
              ingestion.authorizedParsedRevision(actor, documentId).orElseThrow(() -> notFound());
          permissions.require(management.currentRole(actor, documentId), false);
          return ingestion.parsedEvidence(revisionId);
        });
  }

  /**
   * Called by startup composition before any ingestion worker is scheduled. A batch import task
   * that was parsing goes back to the queue as its next, automatic attempt; the three-attempt limit
   * bounds a crash loop. A halted batch's task stops as cancelled (resumable); everything else
   * keeps the explicit worker_interrupted failure.
   */
  public void recoverIngestions() {
    store.transaction(
        () -> {
          var batches = new com.evidence.rag.repository.DocumentBatchRepository(store);
          for (String jobId : ingestion.processingIds()) {
            var task = ingestion.findInternalTask(jobId).orElseThrow();
            if (cancelIfUnauthorized(task)) {
              continue;
            }
            var halted = batches.ingestionBatchHalted(jobId);
            String now = Instant.now().toString();
            if (halted.isPresent() && halted.orElseThrow()) {
              ingestion.markCancelled(jobId, now);
              audit(
                  new Actor(task.workspaceId(), "system:ingestion"),
                  task.documentId(),
                  "ingestion_cancelled",
                  values("state", "processing"),
                  values("state", "cancelled", "reason", "batch_halted"),
                  Set.of("state", "reason"));
            } else if (halted.isPresent() && task.attempt() < 3) {
              // The state machine requeues only from a stopped attempt: stop it, then queue the
              // next.
              ingestion.markCancelled(jobId, now);
              ingestion.markQueued(jobId, task.attempt() + 1, now);
              batches.markAutomatic("ingestion", jobId, task.attempt() + 1, "restart", now);
              audit(
                  new Actor(task.workspaceId(), "system:ingestion"),
                  task.documentId(),
                  "ingestion_requeued",
                  values("state", "processing", "attempt", task.attempt()),
                  values("state", "queued", "attempt", task.attempt() + 1),
                  Set.of("state", "attempt"));
            } else {
              finishFailed(task, "worker_interrupted");
            }
          }
          return null;
        });
  }

  /**
   * Batch bulk retry of one import parsing task, with the same eligibility as an explicit retry.
   * Returns false (and changes nothing) when it is not retryable.
   */
  public boolean retryBatchIngestionInTransaction(Actor actor, String jobId) {
    var task = ingestion.findAuthorizedTask(actor, jobId, true).orElse(null);
    if (task == null
        || !permissions.canEdit(task.currentRole())
        || !Set.of("failed", "cancelled").contains(task.state())
        || task.attempt() >= 3
        || !creatorCanWrite(task, actor.workspaceId())
        || !new DocumentUpdateRepository(store).replacementCurrent(jobId)
        || ingestion.pendingCount(actor.workspaceId()) >= pendingCapacity) {
      return false;
    }
    ingestion.markQueued(jobId, task.attempt() + 1, Instant.now().toString());
    audit(
        actor,
        task.documentId(),
        "ingestion_retried",
        values("state", task.state(), "attempt", task.attempt()),
        values("state", "queued", "attempt", task.attempt() + 1),
        Set.of("state", "attempt"));
    return true;
  }

  /** Batch cancel of one queued import parsing task; a running parse is left to finish. */
  public boolean cancelBatchIngestionInTransaction(Actor actor, String jobId) {
    var task = ingestion.findAuthorizedTask(actor, jobId, true).orElse(null);
    if (task == null
        || !permissions.canEdit(task.currentRole())
        || !"queued".equals(task.state())) {
      return false;
    }
    ingestion.markCancelled(jobId, Instant.now().toString());
    audit(
        actor,
        task.documentId(),
        "ingestion_cancelled",
        values("state", "queued"),
        values("state", "cancelled", "reason", "batch_cancelled"),
        Set.of("state", "reason"));
    return true;
  }

  /**
   * Resume of a halted batch: a cancelled automatic import parsing task is queued again as its next
   * attempt, still automatic. A task already at the attempt limit stays cancelled.
   */
  public boolean resumeBatchIngestionInTransaction(Actor actor, String jobId) {
    var task = ingestion.findInternalTask(jobId).orElse(null);
    if (task == null
        || !"cancelled".equals(task.state())
        || task.attempt() >= 3
        || !creatorCanWrite(task, task.workspaceId())
        || !new DocumentUpdateRepository(store).replacementCurrent(jobId)) {
      return false;
    }
    String now = Instant.now().toString();
    int attempt = task.attempt() + 1;
    new com.evidence.rag.repository.DocumentBatchRepository(store)
        .markAutomatic("ingestion", jobId, attempt, "resume", now);
    ingestion.markQueued(jobId, attempt, now);
    audit(
        actor,
        task.documentId(),
        "ingestion_resumed",
        values("state", "cancelled", "attempt", task.attempt()),
        values("state", "queued", "attempt", attempt),
        Set.of("state", "attempt"));
    return true;
  }

  private TaskEntity authorizedTask(Actor actor, String jobId, boolean edit) {
    if (actor == null) {
      throw invalid();
    }
    identifier(jobId, 100);
    var task = ingestion.findAuthorizedTask(actor, jobId, edit).orElseThrow(() -> notFound());
    permissions.require(task.currentRole(), edit);
    return task;
  }

  private TaskEntity currentClaim(IngestionClaim claim) {
    if (claim == null || claim.token() == null || claim.token().length() != 72) {
      return null;
    }
    var task = ingestion.findInternalTask(claim.jobId()).orElse(null);
    if (task == null
        || !"processing".equals(task.state())
        || !Objects.equals(claim.workspaceId(), task.workspaceId())
        || !Objects.equals(claim.documentId(), task.documentId())
        || !Objects.equals(claim.revisionId(), task.revisionId())
        || !Objects.equals(claim.filename(), task.filename())
        || !Objects.equals(claim.mimeType(), task.mimeType())
        || !Objects.equals(claim.parserRevision(), task.parserRevision())
        || claim.attempt() != task.attempt()
        || !MessageDigest.isEqual(
            sha256(claim.token().getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII),
            task.claimTokenSha256().getBytes(StandardCharsets.US_ASCII))) {
      return null;
    }
    return task;
  }

  private boolean creatorCanWrite(TaskEntity task, String workspaceId) {
    return permissions.canEdit(
        management.currentRole(new Actor(workspaceId, task.createdBy()), task.documentId()));
  }

  /** Only called with a current queued/internal task or a fully matched processing claim. */
  private boolean cancelIfUnauthorized(TaskEntity task) {
    if (creatorCanWrite(task, task.workspaceId())) {
      return false;
    }
    ingestion.markCancelled(task.id(), Instant.now().toString());
    audit(
        new Actor(task.workspaceId(), "system:ingestion"),
        task.documentId(),
        "ingestion_authorization_cancelled",
        values("state", task.state()),
        values("state", "cancelled", "reason", "authorization_changed"),
        Set.of("state", "reason"));
    return true;
  }

  private void checkPendingQuota(String workspaceId) {
    if (ingestion.pendingCount(workspaceId) >= pendingCapacity) {
      throw quotaExceeded();
    }
  }

  private static ApplicationException quotaExceeded() {
    return new ApplicationException(
        FailureKind.CONFLICT, "ingestion_quota_exceeded", "当前组织的待处理任务或原文件存储已达到开发配额。");
  }

  private static ApplicationException invalidParserOutput() {
    return new ApplicationException(
        FailureKind.INVALID_INPUT, "parser_output_invalid", "解析结果未通过证据完整性校验。");
  }

  private static void validateParsed(ParsedText parsed) {
    if (parsed == null
        || parsed.pages().isEmpty()
        || parsed.pages().size() > 500
        || parsed.segments().isEmpty()
        || parsed.segments().size() > 4096) {
      throw invalidParserOutput();
    }
    var pagePoints = new ArrayList<int[]>();
    long total = 0;
    for (var page : parsed.pages()) {
      if (page.number() != pagePoints.size() + 1 || page.text() == null) {
        throw invalidParserOutput();
      }
      int[] points = page.text().codePoints().toArray();
      total += points.length;
      if (total > 1_000_000) {
        throw invalidParserOutput();
      }
      for (int point : points) {
        if ((Character.isISOControl(point) && point != '\n' && point != '\t' && point != '\f')
            || (point >= 0xD800 && point <= 0xDFFF)) {
          throw invalidParserOutput();
        }
      }
      pagePoints.add(points);
    }
    int ordinal = 0, previousPage = 0, previousStart = -1;
    long segmentPoints = 0;
    for (var segment : parsed.segments()) {
      if (segment.ordinal() != ordinal++
          || segment.page() < 1
          || segment.page() > pagePoints.size()
          || segment.start() < 0
          || segment.end() <= segment.start()
          || segment.end() - segment.start() > 1200
          || segment.text() == null
          || segment
              .text()
              .codePoints()
              .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
          || segment.page() < previousPage
          || (segment.page() == previousPage && segment.start() <= previousStart)) {
        throw invalidParserOutput();
      }
      int[] points = pagePoints.get(segment.page() - 1);
      if (segment.end() > points.length
          || !segment
              .text()
              .equals(new String(points, segment.start(), segment.end() - segment.start()))) {
        throw invalidParserOutput();
      }
      segmentPoints += segment.end() - segment.start();
      if (segmentPoints > 1_500_000) {
        throw invalidParserOutput();
      }
      previousPage = segment.page();
      previousStart = segment.start();
    }
  }

  private void finishFailed(TaskEntity task, String safeCode) {
    // A failed file never stops its batch; only the budget and usage audit do.
    ingestion.markFailed(task.id(), safeCode, Instant.now().toString());
    audit(
        new Actor(task.workspaceId(), "system:ingestion"),
        task.documentId(),
        "ingestion_failed",
        values("state", "processing"),
        values("state", "failed", "error_code", safeCode),
        Set.of("state", "error_code"));
  }

  private void audit(
      Actor actor, String id, String action, Object before, Object after, Set<String> fields) {
    management.insertAudit(AuditEventEntity.create(actor, id, action, before, after, fields));
  }

  private static Map<String, Object> values(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }
}
