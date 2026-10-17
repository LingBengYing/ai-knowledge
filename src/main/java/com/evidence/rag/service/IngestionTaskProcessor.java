package com.evidence.rag.service;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.TransientRetry;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ProcessImageParser;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** One parsing use case; authority transactions finish before the isolated worker is entered. */
public final class IngestionTaskProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(IngestionTaskProcessor.class);

  /**
   * Image OCR (one OCR child per JVM) and the native audio/video decoders (one ffmpeg session per
   * JVM) each admit a single child and reject a second caller with parser_busy. Parallel ingestion
   * consumers wait on the matching permit instead of failing the task; the two pools are separate,
   * so images keep flowing while audio or video decodes. A video whose frames are OCR'd also needs
   * the OCR child, so it takes both permits, always native media first (no lock-order cycle).
   */
  private static final java.util.concurrent.Semaphore IMAGE_OCR =
      new java.util.concurrent.Semaphore(1, true);

  private static final java.util.concurrent.Semaphore NATIVE_MEDIA =
      new java.util.concurrent.Semaphore(1, true);

  /** The permits one parse takes, in acquisition order (native media before OCR). */
  static List<java.util.concurrent.Semaphore> videoPermits(boolean ocr) {
    return ocr ? List.of(NATIVE_MEDIA, IMAGE_OCR) : List.of(NATIVE_MEDIA);
  }

  static List<java.util.concurrent.Semaphore> audioPermits() {
    return List.of(NATIVE_MEDIA);
  }

  static List<java.util.concurrent.Semaphore> imagePermits() {
    return List.of(IMAGE_OCR);
  }

  private static <T> T withPermits(List<java.util.concurrent.Semaphore> permits, Supplier<T> work) {
    int held = 0;
    try {
      for (var permit : permits) {
        permit.acquire();
        held++;
      }
      return work.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new TextParser.Failure("parser_interrupted");
    } finally {
      for (int i = held - 1; i >= 0; i--) {
        permits.get(i).release();
      }
    }
  }

  private final IngestionService authority;
  private final String workspace;
  private final Duration deadline;
  private final Function<Duration, ProcessTextParser> parsers;
  private final ImageOcrOptions images;
  private final VisionModels vision;
  private final AudioCompilationService audio;
  private final VideoCompilationService video;
  private final PdfOcrOptions pdfs;
  private final LegacyTextProfileGuard legacyText;
  private final Duration pdfDeadline;
  private final Supplier<String> pdfKey;

  /**
   * PDF OCR recognizes every scanned page remotely, so it gets its own, longer deadline; direct
   * SiliconFlow PDF OCR reads its key from the managed model settings at parse time. Optional
   * collaborators may be null.
   */
  public IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio,
      VideoCompilationService video,
      PdfOcrOptions pdfs,
      LegacyTextProfileGuard legacyText,
      Duration pdfDeadline,
      Supplier<String> pdfKey) {
    this(
        authority,
        workspace,
        deadline,
        ProcessTextParser::new,
        images,
        vision,
        audio,
        video,
        pdfs,
        legacyText,
        pdfDeadline,
        pdfKey);
  }

  // Production and controlled real child processes are the two Adapters at this internal Seam.
  IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers) {
    this(authority, workspace, deadline, parsers, null, null, null, null, null, null, null, null);
  }

  private IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio,
      VideoCompilationService video,
      PdfOcrOptions pdfs,
      LegacyTextProfileGuard legacyText,
      Duration pdfDeadline,
      Supplier<String> pdfKey) {
    this.authority = authority;
    this.workspace = workspace;
    this.deadline = deadline;
    this.parsers = parsers;
    this.images = images;
    this.vision = vision;
    this.audio = audio;
    this.video = video;
    this.pdfs = pdfs;
    this.legacyText = legacyText;
    this.pdfDeadline = pdfDeadline;
    this.pdfKey = pdfKey;
  }

  private volatile TransientRetry retry = TransientRetry.NONE;

  /**
   * Production wiring: bounded retry of a busy parser slot. Unconfigured processors never retry.
   */
  public void configureRetry(TransientRetry retry) {
    this.retry = java.util.Objects.requireNonNull(retry);
  }

  public Optional<IngestionClaim> claim() {
    return authority.claimIngestion(workspace);
  }

  public boolean isCurrent(IngestionClaim claim) {
    return authority.isIngestionClaimCurrent(claim);
  }

  public void process(IngestionClaim claim) {
    String previousTaskId = MDC.get("ingestion_task_id");
    if (claim != null) {
      MDC.put("ingestion_task_id", claim.jobId());
    }
    try {
      parseWhenFree(claim);
    } catch (TextParser.Failure failure) {
      String code =
          switch (failure.code()) {
            case "unsupported_document" -> "unsupported_document";
            case "parser_timeout" -> "parser_timeout";
            case "parser_interrupted", "parser_cancelled", "parser_closed" -> "worker_interrupted";
            case "parser_invalid_output" -> "parser_output_invalid";
            default -> "parser_failed";
          };
      logFailure(claim, failure, code);
      fail(claim, code);
    } catch (ApplicationException failure) {
      if (Set.of("text_configuration_required", "media_text_configuration_mismatch")
          .contains(failure.code())) {
        logFailure(claim, failure, failure.code());
        fail(claim, failure.code());
      } else {
        logFailure(claim, failure, "parser_failed");
        failUnexpected(claim);
      }
    } catch (RuntimeException failure) {
      logFailure(claim, failure, "parser_failed");
      failUnexpected(claim);
    } finally {
      if (previousTaskId == null) {
        MDC.remove("ingestion_task_id");
      } else {
        MDC.put("ingestion_task_id", previousTaskId);
      }
    }
  }

  /**
   * A busy parser slot (one native child per JVM, or the bounded parser capacity) rejects before
   * any work starts, so the same claim is simply tried again after a bounded backoff.
   */
  private void parseWhenFree(IngestionClaim claim) {
    var policy = retry;
    for (int busy = 0; ; busy++) {
      try {
        parseOnce(claim);
        return;
      } catch (TextParser.Failure failure) {
        if (!"parser_busy".equals(failure.code())
            || busy >= policy.retries()
            || !authority.isIngestionClaimCurrent(claim)) {
          throw failure;
        }
        try {
          Thread.sleep(policy.delay(busy, null));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new TextParser.Failure("parser_interrupted");
        }
      }
    }
  }

  private void parseOnce(IngestionClaim claim) {
    if (!authority.isIngestionClaimCurrent(claim)) {
      return;
    }
    if (claim.mimeType().startsWith("video/")
        || claim.parserRevision().startsWith("java-video-compiler-v1:")
        || claim.parserRevision().startsWith("java-video-compiler-v2:")
        || claim.parserRevision().startsWith("java-video-compiler-v3:")) {
      if (legacyText != null) {
        legacyText.requireCompatible();
      }
      if (video == null || !video.revision().equals(claim.parserRevision())) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      var compilation =
          withPermits(
              videoPermits(video.ocrEnabled()),
              () ->
                  video.compile(
                      claim.filename(),
                      claim.mimeType(),
                      claim.content(),
                      () -> authority.isIngestionClaimCurrent(claim)));
      authority.completeVideoIngestion(claim, compilation);
      return;
    }
    if (claim.mimeType().startsWith("audio/")
        || claim.parserRevision().startsWith("java-audio-compiler-v1:")) {
      if (audio == null || !audio.revision().equals(claim.parserRevision())) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      var compilation =
          withPermits(
              audioPermits(),
              () ->
                  audio.compile(
                      claim.filename(),
                      claim.mimeType(),
                      claim.content(),
                      () -> authority.isIngestionClaimCurrent(claim)));
      authority.completeAudioIngestion(claim, compilation);
      return;
    }
    if (vision != null
        && new VisualIngestionOptions(vision.revision())
            .parserRevision()
            .equals(claim.parserRevision())) {
      if (legacyText != null) {
        legacyText.requireCompatible();
      }
      String revision = vision.revision();
      ImageInput.validateEnvelope(claim.filename(), claim.mimeType(), claim.content());
      ImageInput.inspect(claim.content());
      if (!authority.isIngestionClaimCurrent(claim) || Thread.currentThread().isInterrupted()) {
        return;
      }
      var description = vision.describe(new VisualImage(claim.mimeType(), claim.content()));
      if (!revision.equals(vision.revision()) || Thread.currentThread().isInterrupted()) {
        throw new TextParser.Failure("parser_interrupted");
      }
      if (authority.isIngestionClaimCurrent(claim)) {
        authority.completeVisualIngestion(
            claim, new ImageRecall(description.recallText(), revision));
      }
      return;
    }
    if (images != null && ImageInput.isImageName(claim.filename())) {
      if (!images.parserRevision().equals(claim.parserRevision())) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      Duration imageDeadline =
          deadline.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : deadline;
      var parsed =
          withPermits(
              imagePermits(),
              () -> {
                try (var parser = new ProcessImageParser(images, imageDeadline)) {
                  return parser.parse(claim.filename(), claim.mimeType(), claim.content());
                }
              });
      authority.completeImageIngestion(claim, parsed);
      return;
    }
    if ("application/pdf".equals(claim.mimeType())
        || claim.filename().toLowerCase(Locale.ROOT).endsWith(".pdf")
        || claim.parserRevision().startsWith("java-pdf-ocr-v1:")) {
      String expectedRevision = pdfs == null ? TextParser.REVISION : pdfs.parserRevision();
      if (!"application/pdf".equals(claim.mimeType())
          || !claim.filename().toLowerCase(Locale.ROOT).endsWith(".pdf")
          || !expectedRevision.equals(claim.parserRevision())) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      Duration pdfTime = pdfDeadline == null ? deadline : pdfDeadline;
      try (var parser =
          pdfs == null
              ? parsers.apply(deadline)
              : pdfs.remote()
                  ? new ProcessTextParser(pdfTime, pdfs, remoteKey())
                  : new ProcessTextParser(pdfTime, pdfs)) {
        var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
        authority.completeIngestion(claim, parsed);
      }
      return;
    }
    try (var parser = parsers.apply(deadline)) {
      var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
      authority.completeIngestion(claim, parsed);
    }
  }

  private String remoteKey() {
    String key = pdfKey == null ? null : pdfKey.get();
    if (key == null) {
      throw new ApplicationException(
          FailureKind.UNAVAILABLE,
          "text_configuration_required",
          "PDF 识别使用模型配置里的硅基流动 Key，请先完成并启用模型配置。");
    }
    return key;
  }

  private static void logFailure(IngestionClaim claim, RuntimeException failure, String code) {
    LOG.warn(
        "ingestion_failed task_id={} failure_type={} failure_code={}",
        claim == null ? "untracked" : claim.jobId(),
        failure.getClass().getName(),
        code);
  }

  public void failUnexpected(IngestionClaim claim) {
    fail(claim, "parser_failed");
  }

  private void fail(IngestionClaim claim, String code) {
    if (claim == null) {
      return;
    }
    try {
      authority.failIngestion(claim, code);
    } catch (ApplicationException unavailable) {
      // Persisted processing state is recovered explicitly before scheduling on the next startup.
    }
  }
}
