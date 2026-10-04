package com.evidence.rag.service;

import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.AudioTranscriptSpan;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.ImageRecall;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.AudioInput;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import com.evidence.rag.worker.parser.ImageOcr;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Compiles bounded, temporary query hints without writing library authority or creating evidence.
 */
public final class QueryPreparationService {
  private final VisionModels imageModels;
  private final ImageOcr imageOcr;
  private final AudioCompilationService audio;
  private final VideoCompilationService video;
  private final String imageModelRevision;
  private final String ocrRevision;
  private final String audioRevision;
  private final String videoRevision;
  private final String imageCompilerRevision;
  private final String preparationRevision;
  private final long budgetNanos;
  private final boolean retainAudio;

  public QueryPreparationService(
      VisionModels imageModels,
      ImageOcr imageOcr,
      AudioCompilationService audio,
      VideoCompilationService video,
      Duration budget) {
    this(imageModels, imageOcr, audio, video, budget, false);
  }

  public QueryPreparationService(
      VisionModels imageModels,
      ImageOcr imageOcr,
      AudioCompilationService audio,
      VideoCompilationService video,
      Duration budget,
      boolean retainAudio) {
    if (imageModels == null
        || imageOcr == null
        || audio == null
        || video == null
        || video.textEvidenceOnly()
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.imageModels = imageModels;
    this.imageOcr = imageOcr;
    this.audio = audio;
    this.video = video;
    this.retainAudio = retainAudio;
    imageModelRevision = ModelValues.identifier(imageModels.revision(), 200);
    ocrRevision = ModelValues.identifier(imageOcr.revision(), 200);
    audioRevision = ModelValues.identifier(audio.revision(), 200);
    videoRevision = ModelValues.identifier(video.revision(), 200);
    imageCompilerRevision =
        "java-query-image-v1:"
            + hash(
                List.of(
                    imageModelRevision, ocrRevision, "original-pixels+complete-ocr+description"));
    preparationRevision =
        (retainAudio ? "java-query-preparation-v2:" : "java-query-preparation-v1:")
            + hash(
                List.of(
                    imageCompilerRevision,
                    audioRevision,
                    videoRevision,
                    retainAudio
                        ? "complete-text-8192cp+unique-global-first-middle-last-3+all-original-pcm16k-mono-s16le"
                        : "complete-text-8192cp+unique-global-first-middle-last-3"));
    budgetNanos = budget.toNanos();
  }

  public String revision() {
    return preparationRevision;
  }

  public boolean configurationCurrent() {
    return imageModelRevision.equals(imageModels.revision())
        && ocrRevision.equals(imageOcr.revision())
        && audioRevision.equals(audio.revision())
        && audio.configurationCurrent()
        && videoRevision.equals(video.revision())
        && video.configurationCurrent();
  }

  public PreparedQuery prepare(
      String question, List<QueryAttachment> attachments, BooleanSupplier current) {
    var plain = PreparedQuery.text(question);
    if (attachments == null
        || current == null
        || attachments.stream().anyMatch(item -> item == null)) {
      throw ModelValues.invalid();
    }
    long started = System.nanoTime();
    checkCurrent(current, started);
    if (attachments.isEmpty()) {
      return plain;
    }
    if (attachments.size() > 3
        || attachments.stream().mapToLong(item -> item.content().length).sum()
            > 20L * 1024 * 1024) {
      throw new TextParser.Failure("query_attachment_limit");
    }
    var inputs = List.copyOf(attachments);
    // Admit every envelope before the first model disclosure; decoders still verify actual streams.
    for (var input : inputs) {
      switch (input.kind()) {
        case IMAGE ->
            ImageInput.validateEnvelope(input.filename(), input.mediaType(), input.content());
        case AUDIO ->
            AudioInput.validateEnvelope(input.filename(), input.mediaType(), input.content());
        case VIDEO ->
            VideoInput.validateEnvelope(input.filename(), input.mediaType(), input.content());
      }
    }
    try {
      var compiled = new ArrayList<CompiledAttachment>();
      var retrieval = new StringBuilder(question);
      var unique = new LinkedHashMap<String, VisualImage>();
      var waveforms = new ArrayList<AudioWaveform>();
      BooleanSupplier active =
          () -> {
            check(current, started);
            return true;
          };
      for (var input : inputs) {
        check(current, started);
        var result =
            switch (input.kind()) {
              case IMAGE -> image(input, current, started);
              case AUDIO -> audio(input, active);
              case VIDEO -> video(input, active);
            };
        check(current, started);
        compiled.add(result);
        if (!result.text().isEmpty()) {
          retrieval.append('\n').append(result.text());
        }
        if (retrieval.codePointCount(0, retrieval.length()) > 8192) {
          throw new TextParser.Failure("query_text_limit");
        }
        result.images().forEach(image -> unique.putIfAbsent(image.sha256(), image));
        waveforms.addAll(result.waveforms());
      }
      var images = List.copyOf(unique.values());
      var selected =
          images.size() <= 3
              ? images
              : List.of(images.getFirst(), images.get((images.size() - 1) / 2), images.getLast());
      var selectedSha = new LinkedHashSet<>(selected.stream().map(VisualImage::sha256).toList());
      var manifests = new ArrayList<QueryAttachmentManifest>();
      for (int ordinal = 0; ordinal < compiled.size(); ordinal++) {
        var input = inputs.get(ordinal);
        var result = compiled.get(ordinal);
        var available = result.images().stream().map(VisualImage::sha256).distinct().toList();
        var included = available.stream().filter(selectedSha::contains).toList();
        manifests.add(
            new QueryAttachmentManifest(
                ordinal,
                input.sha256(),
                input.kind(),
                result.compilerRevision(),
                result.contentSha256(),
                result.text().codePointCount(0, result.text().length()),
                result.images().size(),
                included,
                included.size() != available.size()));
      }
      check(current, started);
      return new PreparedQuery(
          question, retrieval.toString(), selected, manifests, preparationRevision, waveforms);
    } catch (TextParser.Failure failure) {
      throw failure;
    } catch (RuntimeException invalidOutput) {
      throw new TextParser.Failure("parser_invalid_output");
    }
  }

  private CompiledAttachment image(QueryAttachment input, BooleanSupplier current, long started) {
    var image = new VisualImage(input.mediaType(), input.content());
    var dimensions = ImageInput.inspect(image.content());
    var parsed = imageOcr.read(image);
    check(current, started);
    if (parsed == null) {
      throw new TextParser.Failure("parser_invalid_output");
    }
    String ocrText = "";
    if (parsed.isPresent()) {
      var result = parsed.orElseThrow();
      if (!dimensions.equals(result.dimensions())
          || result.text().pages().size() != 1
          || result.text().pages().getFirst().number() != 1
          || result.text().pages().getFirst().text() == null) {
        throw new TextParser.Failure("parser_invalid_output");
      }
      ocrText = result.text().pages().getFirst().text();
    }
    var description = imageModels.describe(image);
    check(current, started);
    if (description == null) {
      throw new TextParser.Failure("parser_invalid_output");
    }
    var recall = new ImageRecall(description.recallText(), imageModelRevision);
    String text = join(List.of(recall.recallText(), ocrText));
    return new CompiledAttachment(
        imageCompilerRevision,
        text,
        List.of(image),
        hash(
            List.of(
                "query-image-content-v1",
                imageCompilerRevision,
                input.sha256(),
                image.mediaType(),
                Integer.toString(dimensions.width()),
                Integer.toString(dimensions.height()),
                recall.recallText(),
                ocrText)));
  }

  private CompiledAttachment audio(QueryAttachment input, BooleanSupplier current) {
    var prepared =
        retainAudio
            ? audio.compileWithWaveforms(
                input.filename(), input.mediaType(), input.content(), current)
            : null;
    var result =
        prepared == null
            ? audio.compile(input.filename(), input.mediaType(), input.content(), current)
            : prepared.compilation();
    if (!input.sha256().equals(result.sourceSha256())
        || !audioRevision.equals(result.compilerRevision())) {
      throw new TextParser.Failure("parser_invalid_output");
    }
    var parts =
        new ArrayList<>(
            List.of(
                "query-audio-content-v1",
                input.sha256(),
                result.decoderRevision(),
                result.modelRevision(),
                result.compilerRevision(),
                Long.toString(result.durationMs())));
    spans(parts, result.spans());
    return new CompiledAttachment(
        audioRevision,
        join(result.spans().stream().map(AudioTranscriptSpan::text).toList()),
        List.of(),
        hash(parts),
        prepared == null ? List.of() : prepared.waveforms());
  }

  private CompiledAttachment video(QueryAttachment input, BooleanSupplier current) {
    var result = video.compile(input.filename(), input.mediaType(), input.content(), current);
    if (!input.sha256().equals(result.sourceSha256())
        || !videoRevision.equals(result.compilerRevision())) {
      throw new TextParser.Failure("parser_invalid_output");
    }
    var text = new ArrayList<String>();
    var images = new ArrayList<VisualImage>();
    var parts =
        new ArrayList<>(
            List.of(
                "query-video-content-v1",
                input.sha256(),
                result.decoderRevision(),
                result.compilerRevision(),
                Long.toString(result.timelineOriginUs()),
                Long.toString(result.durationUs()),
                Integer.toString(result.frames().size())));
    for (var item : result.frames()) {
      var frame = item.frame();
      images.add(frame.image());
      text.add(item.recall().recallText());
      parts.addAll(
          List.of(
              Integer.toString(frame.ordinal()),
              Long.toString(frame.presentationUs()),
              Long.toString(frame.durationUs()),
              frame.image().mediaType(),
              frame.image().sha256(),
              Integer.toString(frame.width()),
              Integer.toString(frame.height()),
              item.recall().modelRevision(),
              item.recall().recallText()));
    }
    parts.add(Boolean.toString(result.audio() != null));
    if (result.audio() != null) {
      var transcript = result.audio();
      parts.addAll(
          List.of(
              transcript.modelRevision(),
              transcript.transcriptionRevision(),
              Long.toString(transcript.sampleCount())));
      spans(parts, transcript.spans());
      transcript.spans().forEach(span -> text.add(span.text()));
    }
    parts.add(Boolean.toString(result.ocr() != null));
    if (result.ocr() != null) {
      parts.add(result.ocr().ocrRevision());
      parts.add(Integer.toString(result.ocr().frames().size()));
      for (var ocr : result.ocr().frames()) {
        parts.addAll(List.of(Integer.toString(ocr.frameOrdinal()), ocr.frameSha256(), ocr.text()));
        text.add(ocr.text());
      }
    }
    parts.add(Boolean.toString(result.subtitles() != null));
    if (result.subtitles() != null) {
      parts.add(result.subtitles().manifestSha256());
      for (var track : result.subtitles().tracks()) {
        track.cues().forEach(cue -> text.add(cue.text()));
      }
    }
    return new CompiledAttachment(videoRevision, join(text), images, hash(parts));
  }

  private static void spans(List<String> parts, List<AudioTranscriptSpan> spans) {
    parts.add(Integer.toString(spans.size()));
    for (var span : spans) {
      parts.addAll(
          List.of(
              Integer.toString(span.ordinal()),
              Long.toString(span.startMs()),
              Long.toString(span.endMs()),
              span.text()));
    }
  }

  private static String join(List<String> text) {
    return String.join("\n", text.stream().filter(value -> !value.isEmpty()).toList());
  }

  private static String hash(List<String> values) {
    var encoded = new StringBuilder();
    for (String value : values) {
      encoded.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }
    return ModelValues.sha256(encoded.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void check(BooleanSupplier current, long started) {
    checkCurrent(current, started);
    if (!configurationCurrent()) {
      throw new TextParser.Failure("query_profile_changed");
    }
    checkDeadline(started);
  }

  private void checkCurrent(BooleanSupplier current, long started) {
    checkDeadline(started);
    boolean allowed;
    try {
      allowed = current.getAsBoolean();
    } catch (RuntimeException unavailable) {
      throw new TextParser.Failure("parser_cancelled");
    }
    if (!allowed) {
      throw new TextParser.Failure("parser_cancelled");
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

  private record CompiledAttachment(
      String compilerRevision,
      String text,
      List<VisualImage> images,
      String contentSha256,
      List<AudioWaveform> waveforms) {
    private CompiledAttachment(
        String compilerRevision, String text, List<VisualImage> images, String contentSha256) {
      this(compilerRevision, text, images, contentSha256, List.of());
    }

    private CompiledAttachment {
      images = List.copyOf(images);
      waveforms = List.copyOf(waveforms);
    }

    @Override
    public String toString() {
      return "CompiledAttachment[redacted]";
    }
  }
}
