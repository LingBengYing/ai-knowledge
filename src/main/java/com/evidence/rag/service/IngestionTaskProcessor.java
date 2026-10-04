package com.evidence.rag.service;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.PdfOcrOptions;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ProcessImageParser;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** One parsing use case; authority transactions finish before the isolated worker is entered. */
public final class IngestionTaskProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(IngestionTaskProcessor.class);
  private final IngestionService authority;
  private final String workspace;
  private final Duration deadline;
  private final Function<Duration, ProcessTextParser> parsers;
  private final ImageOcrOptions images;
  private final VisionModels vision;
  private final AudioCompilationService audio;
  private final VideoCompilationService video;
  private final PdfOcrOptions pdfs;
  private LegacyTextProfileGuard legacyText;

  public IngestionTaskProcessor(IngestionService authority, String workspace, Duration deadline) {
    this(authority, workspace, deadline, ProcessTextParser::new);
  }

  public IngestionTaskProcessor(
      IngestionService authority, String workspace, Duration deadline, ImageOcrOptions images) {
    this(authority, workspace, deadline, ProcessTextParser::new, images);
  }

  public IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      ImageOcrOptions images,
      VisionModels vision) {
    this(authority, workspace, deadline, ProcessTextParser::new, images, vision);
  }

  public IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio) {
    this(authority, workspace, deadline, ProcessTextParser::new, images, vision, audio);
  }

  public IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio,
      VideoCompilationService video) {
    this(authority, workspace, deadline, ProcessTextParser::new, images, vision, audio, video);
  }

  public IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio,
      VideoCompilationService video,
      PdfOcrOptions pdfs) {
    this(
        authority, workspace, deadline, ProcessTextParser::new, images, vision, audio, video, pdfs);
  }

  public IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio,
      VideoCompilationService video,
      PdfOcrOptions pdfs,
      LegacyTextProfileGuard legacyText) {
    this(authority, workspace, deadline, images, vision, audio, video, pdfs);
    this.legacyText = legacyText;
  }

  // Production and controlled real child processes are the two Adapters at this internal Seam.
  IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers) {
    this(authority, workspace, deadline, parsers, null);
  }

  private IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers,
      ImageOcrOptions images) {
    this(authority, workspace, deadline, parsers, images, null);
  }

  private IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers,
      ImageOcrOptions images,
      VisionModels vision) {
    this(authority, workspace, deadline, parsers, images, vision, null);
  }

  private IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio) {
    this(authority, workspace, deadline, parsers, images, vision, audio, null);
  }

  private IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers,
      ImageOcrOptions images,
      VisionModels vision,
      AudioCompilationService audio,
      VideoCompilationService video) {
    this(authority, workspace, deadline, parsers, images, vision, audio, video, null);
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
      PdfOcrOptions pdfs) {
    this.authority = authority;
    this.workspace = workspace;
    this.deadline = deadline;
    this.parsers = parsers;
    this.images = images;
    this.vision = vision;
    this.audio = audio;
    this.video = video;
    this.pdfs = pdfs;
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
            video.compile(
                claim.filename(),
                claim.mimeType(),
                claim.content(),
                () -> authority.isIngestionClaimCurrent(claim));
        authority.completeVideoIngestion(claim, compilation);
        return;
      }
      if (claim.mimeType().startsWith("audio/")
          || claim.parserRevision().startsWith("java-audio-compiler-v1:")) {
        if (audio == null || !audio.revision().equals(claim.parserRevision())) {
          throw new TextParser.Failure("parser_invalid_output");
        }
        var compilation =
            audio.compile(
                claim.filename(),
                claim.mimeType(),
                claim.content(),
                () -> authority.isIngestionClaimCurrent(claim));
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
        try (var parser = new ProcessImageParser(images, deadline)) {
          var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
          authority.completeImageIngestion(claim, parsed);
        }
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
        try (var parser =
            pdfs == null ? parsers.apply(deadline) : new ProcessTextParser(deadline, pdfs)) {
          var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
          authority.completeIngestion(claim, parsed);
        }
        return;
      }
      try (var parser = parsers.apply(deadline)) {
        var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
        authority.completeIngestion(claim, parsed);
      }
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
