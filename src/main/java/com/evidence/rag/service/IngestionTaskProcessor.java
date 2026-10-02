package com.evidence.rag.service;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.model.domain.VisualIngestionOptions;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ProcessImageParser;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/** One parsing use case; authority transactions finish before the isolated worker is entered. */
public final class IngestionTaskProcessor {
  private final IngestionService authority;
  private final String workspace;
  private final Duration deadline;
  private final Function<Duration, ProcessTextParser> parsers;
  private final ImageOcrOptions images;
  private final VisionModels vision;
  private final AudioCompilationService audio;
  private final VideoCompilationService video;

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
    this.authority = authority;
    this.workspace = workspace;
    this.deadline = deadline;
    this.parsers = parsers;
    this.images = images;
    this.vision = vision;
    this.audio = audio;
    this.video = video;
  }

  public Optional<IngestionClaim> claim() {
    return authority.claimIngestion(workspace);
  }

  public boolean isCurrent(IngestionClaim claim) {
    return authority.isIngestionClaimCurrent(claim);
  }

  public void process(IngestionClaim claim) {
    try {
      if (!authority.isIngestionClaimCurrent(claim)) {
        return;
      }
      if (claim.mimeType().startsWith("video/")
          || claim.parserRevision().startsWith("java-video-compiler-v1:")
          || claim.parserRevision().startsWith("java-video-compiler-v2:")
          || claim.parserRevision().startsWith("java-video-compiler-v3:")) {
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
      try (var parser = parsers.apply(deadline)) {
        var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
        authority.completeIngestion(claim, parsed);
      }
    } catch (TextParser.Failure failure) {
      fail(
          claim,
          switch (failure.code()) {
            case "unsupported_document" -> "unsupported_document";
            case "parser_timeout" -> "parser_timeout";
            case "parser_interrupted", "parser_cancelled", "parser_closed" -> "worker_interrupted";
            case "parser_invalid_output" -> "parser_output_invalid";
            default -> "parser_failed";
          });
    } catch (RuntimeException failure) {
      failUnexpected(claim);
    }
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
