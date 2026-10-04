package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioTranscription;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoFrameRecall;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ImageOcr;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Compiles complete video recall inputs; authority and proof stay with their application services.
 */
public final class VideoCompilationService {
  private static final Logger LOG = LoggerFactory.getLogger(VideoCompilationService.class);
  private final VideoDecoder decoder;
  private final AudioTranscriptionService transcriber;
  private final VisionModels models;
  private final ImageOcr ocr;
  private final String decoderRevision;
  private final String transcriptionRevision;
  private final String modelRevision;
  private final String ocrRevision;
  private final String compilerRevision;
  private final long budgetNanos;
  private final boolean subtitlesEnabled;
  private final boolean textEvidenceOnly;

  public VideoCompilationService(
      VideoDecoder decoder,
      AudioTranscriptionService transcriber,
      VisionModels models,
      ImageOcr ocr,
      Duration budget) {
    this(decoder, transcriber, models, ocr, budget, true, false, false);
  }

  public VideoCompilationService(
      VideoDecoder decoder,
      AudioTranscriptionService transcriber,
      VisionModels models,
      Duration budget) {
    this(decoder, transcriber, models, null, budget, false, false, false);
  }

  public VideoCompilationService(
      VideoDecoder decoder,
      AudioTranscriptionService transcriber,
      VisionModels models,
      ImageOcr ocr,
      Duration budget,
      boolean subtitlesEnabled) {
    this(decoder, transcriber, models, ocr, budget, ocr != null, subtitlesEnabled, false);
  }

  /** Keeps real frames and textual evidence without requiring or invoking a vision model. */
  public static VideoCompilationService textEvidenceOnly(
      VideoDecoder decoder,
      AudioTranscriptionService transcriber,
      ImageOcr ocr,
      Duration budget,
      boolean subtitlesEnabled) {
    return new VideoCompilationService(
        decoder, transcriber, null, ocr, budget, ocr != null, subtitlesEnabled, true);
  }

  private VideoCompilationService(
      VideoDecoder decoder,
      AudioTranscriptionService transcriber,
      VisionModels models,
      ImageOcr ocr,
      Duration budget,
      boolean ocrEnabled,
      boolean subtitlesEnabled,
      boolean textEvidenceOnly) {
    if (decoder == null
        || transcriber == null
        || (textEvidenceOnly ? models != null : models == null)
        || (ocrEnabled && ocr == null)
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.decoder = decoder;
    this.transcriber = transcriber;
    this.models = models;
    this.ocr = ocr;
    this.subtitlesEnabled = subtitlesEnabled;
    this.textEvidenceOnly = textEvidenceOnly;
    decoderRevision = ModelValues.identifier(decoder.revision(), 200);
    transcriptionRevision = ModelValues.identifier(transcriber.revision(), 200);
    modelRevision = textEvidenceOnly ? null : ModelValues.identifier(models.revision(), 200);
    ocrRevision = ocr == null ? null : ModelValues.identifier(ocr.revision(), 200);
    budgetNanos = budget.toNanos();
    if (textEvidenceOnly) {
      compilerRevision =
          VideoCompilation.TEXT_EVIDENCE_COMPILER_PREFIX
              + ModelValues.sha256(
                  (decoderRevision
                          + "\0"
                          + transcriptionRevision
                          + "\0actual-frame-pts-png+aligned-pcm16k+text-evidence-only"
                          + "\0vision-description=absent\0ocr-enabled="
                          + (ocr != null)
                          + (ocr == null
                              ? ""
                              : "\0" + ocrRevision + "\0complete-frame-local-ocr-cp-boxes")
                          + "\0subtitles-enabled="
                          + subtitlesEnabled
                          + (subtitlesEnabled
                              ? "\0"
                                  + VideoSubtitleCompilation.TEXT_FORMAT
                                  + "\0all-packets-rational-video-epoch"
                              : ""))
                      .getBytes(StandardCharsets.UTF_8));
    } else {
      compilerRevision =
          (subtitlesEnabled
                  ? "java-video-compiler-v3:"
                  : ocr == null ? "java-video-compiler-v1:" : "java-video-compiler-v2:")
              + ModelValues.sha256(
                  (decoderRevision
                          + "\0"
                          + transcriptionRevision
                          + "\0"
                          + modelRevision
                          + "\0actual-frame-pts-png+aligned-pcm16k+recall-only"
                          + (ocr == null
                              ? ""
                              : "\0" + ocrRevision + "\0complete-frame-local-ocr-cp-boxes")
                          + (subtitlesEnabled
                              ? "\0subtitles-required\0"
                                  + VideoSubtitleCompilation.TEXT_FORMAT
                                  + "\0all-packets-rational-video-epoch\0ocr-enabled="
                                  + (ocr != null)
                              : ""))
                      .getBytes(StandardCharsets.UTF_8));
    }
  }

  public String revision() {
    return compilerRevision;
  }

  /** Configuration contract, not inferred later from a potentially incomplete processing result. */
  public boolean ocrEnabled() {
    return ocr != null;
  }

  public boolean textEvidenceOnly() {
    return textEvidenceOnly;
  }

  public boolean subtitlesEnabled() {
    return subtitlesEnabled;
  }

  public VideoCompilation compile(
      String filename, String mime, byte[] source, BooleanSupplier current) {
    String phase = "preflight";
    try {
      long started = System.nanoTime();
      if (current == null) {
        throw ModelValues.invalid();
      }
      check(current, started);
      if (source == null || source.length == 0 || source.length > 20 * 1024 * 1024) {
        throw new TextParser.Failure("unsupported_document");
      }
      byte[] original = source.clone();
      String sourceSha = ModelValues.sha256(original);
      phase = "native_decode";
      var decoded = decoder.decode(filename, mime, original);
      phase = "decoded_validation";
      check(current, started);
      if (decoded == null
          || !sourceSha.equals(decoded.sourceSha256())
          || !decoderRevision.equals(decoded.decoderRevision())
          || subtitlesEnabled != (decoded.subtitles() != null)) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      // Validate the entire local product before disclosing any frame or PCM to a model.
      for (var frame : decoded.frames()) {
        validateImage(frame);
      }
      phase = "asr_transcription";
      AudioTranscription audio =
          decoded.audio() == null
              ? null
              : transcriber.transcribe(
                  decoded.audio(),
                  () -> {
                    check(current, started);
                    return true;
                  });
      check(current, started);
      var frames = new ArrayList<VideoFrameRecall>();
      phase = textEvidenceOnly ? "original_frame_preservation" : "vision_description";
      for (var frame : decoded.frames()) {
        check(current, started);
        if (textEvidenceOnly) {
          frames.add(new VideoFrameRecall(frame, null));
          continue;
        }
        var description = models.describe(frame.image());
        check(current, started);
        if (description == null) {
          throw new TextParser.Failure("parser_invalid_output");
        }
        try {
          frames.add(
              new VideoFrameRecall(frame, new ImageRecall(description.recallText(), modelRevision)));
        } catch (ApplicationException invalidRecall) {
          throw new TextParser.Failure("parser_invalid_output");
        }
      }
      check(current, started);
      VideoOcrCompilation compiledOcr = null;
      if (ocr != null) {
        phase = "frame_ocr";
        var ocrFrames = new ArrayList<VideoFrameOcr>();
        for (var frame : decoded.frames()) {
          check(current, started);
          var parsed = ocr.read(frame.image());
          check(current, started);
          if (parsed == null) {
            throw new TextParser.Failure("parser_invalid_output");
          }
          var dimensions = new ImageDimensions(frame.width(), frame.height());
          try {
            if (parsed.isEmpty()) {
              ocrFrames.add(
                  new VideoFrameOcr(
                      frame.ordinal(), frame.image().sha256(), dimensions, "", List.of(), List.of()));
            } else {
              var image = parsed.orElseThrow();
              if (!dimensions.equals(image.dimensions())
                  || image.text().pages().size() != 1
                  || image.text().pages().getFirst().number() != 1
                  || image.text().pages().getFirst().text() == null
                  || image.text().pages().getFirst().text().isEmpty()
                  || image.text().segments().stream().anyMatch(segment -> segment.page() != 1)) {
                throw new TextParser.Failure("parser_invalid_output");
              }
              ocrFrames.add(
                  new VideoFrameOcr(
                      frame.ordinal(),
                      frame.image().sha256(),
                      dimensions,
                      image.text().pages().getFirst().text(),
                      image.text().segments().stream()
                          .map(
                              segment ->
                                  new VideoOcrSegment(
                                      segment.ordinal(),
                                      segment.start(),
                                      segment.end(),
                                      segment.text()))
                          .toList(),
                      image.regions()));
            }
          } catch (ApplicationException invalidOcr) {
            throw new TextParser.Failure("parser_invalid_output");
          }
        }
        try {
          compiledOcr = new VideoOcrCompilation(ocrRevision, ocrFrames);
        } catch (ApplicationException invalidOcr) {
          throw new TextParser.Failure("parser_invalid_output");
        }
      }
      phase = "compilation_assembly";
      check(current, started);
      return new VideoCompilation(
          sourceSha,
          decoderRevision,
          compilerRevision,
          decoded.timelineOriginUs(),
          decoded.durationUs(),
          frames,
          audio,
          compiledOcr,
          decoded.subtitles());
    } catch (RuntimeException failure) {
      String taskId = MDC.get("ingestion_task_id");
      LOG.warn(
          "video_compilation_failed task_id={} phase={} failure_type={} failure_code={}",
          taskId == null ? "untracked" : taskId,
          phase,
          failure.getClass().getName(),
          diagnosticCode(failure));
      throw failure;
    }
  }

  private static String diagnosticCode(RuntimeException failure) {
    String code =
        failure instanceof TextParser.Failure parser
            ? parser.code()
            : failure instanceof TextModels.Failure model
                ? model.code()
                : failure instanceof ApplicationException application ? application.code() : null;
    return switch (code == null ? "" : code) {
      case "unsupported_document",
          "parser_timeout",
          "parser_interrupted",
          "parser_cancelled",
          "parser_closed",
          "parser_invalid_output",
          "parser_failed",
          "parser_busy",
          "parser_cleanup_failed",
          "video_profile_changed",
          "audio_profile_changed",
          "model_timeout",
          "model_interrupted",
          "model_http_failed",
          "model_transport_failed",
          "model_invalid_response",
          "model_response_too_large",
          "model_invalid_input",
          "model_invalid_configuration",
          "model_closed",
          "invalid_request" -> code;
      default -> "unexpected_failure";
    };
  }

  private static void validateImage(VideoFrame frame) {
    try {
      var input = frame.image();
      ImageInput.validateEnvelope(
          "image/png".equals(input.mediaType()) ? "frame.png" : "frame.jpg",
          input.mediaType(),
          input.content());
      var dimensions = ImageInput.inspect(input.content());
      if (dimensions.width() != frame.width() || dimensions.height() != frame.height()) {
        throw new TextParser.Failure("parser_invalid_output");
      }
    } catch (TextParser.Failure invalidImage) {
      throw new TextParser.Failure("parser_invalid_output");
    }
  }

  public boolean configurationCurrent() {
    return decoderRevision.equals(decoder.revision())
        && transcriptionRevision.equals(transcriber.revision())
        && (textEvidenceOnly || modelRevision.equals(models.revision()))
        && (ocr == null || ocrRevision.equals(ocr.revision()));
  }

  private void check(BooleanSupplier current, long started) {
    checkDeadline(started);
    boolean allowed;
    try {
      allowed = current.getAsBoolean();
    } catch (TextParser.Failure failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!allowed) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!configurationCurrent()) {
      throw new TextParser.Failure("video_profile_changed");
    }
    checkDeadline(started);
  }

  private void checkDeadline(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new TextParser.Failure("parser_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new TextParser.Failure("parser_timeout");
    }
  }
}
