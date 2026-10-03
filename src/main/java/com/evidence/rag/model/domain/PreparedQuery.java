package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Original proof question, separate retrieval hints, and original images for auxiliary matching.
 */
public record PreparedQuery(
    String originalQuestion,
    String retrievalText,
    List<VisualImage> queryImages,
    List<QueryAttachmentManifest> attachments,
    String preparationRevision,
    List<AudioWaveform> queryAudio) {
  public PreparedQuery(
      String originalQuestion,
      String retrievalText,
      List<VisualImage> queryImages,
      List<QueryAttachmentManifest> attachments,
      String preparationRevision) {
    this(originalQuestion, retrievalText, queryImages, attachments, preparationRevision, List.of());
  }

  public PreparedQuery {
    if (originalQuestion == null
        || originalQuestion.isBlank()
        || originalQuestion.getBytes(StandardCharsets.UTF_8).length > 4096
        || originalQuestion
            .codePoints()
            .anyMatch(
                c -> (c < 32 && c != '\n' && c != '\t') || c == 127 || (c >= 0xD800 && c <= 0xDFFF))
        || retrievalText == null
        || retrievalText.isBlank()
        || retrievalText.codePointCount(0, retrievalText.length()) > 8192
        || retrievalText
            .codePoints()
            .anyMatch(
                c ->
                    (c < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\f')
                        || c == 127
                        || (c >= 0xD800 && c <= 0xDFFF))
        || queryImages == null
        || queryImages.size() > 3
        || attachments == null
        || attachments.size() > 3
        || queryAudio == null
        || queryAudio.size() > 1800
        || (attachments.isEmpty()
            && (!originalQuestion.equals(retrievalText)
                || !queryImages.isEmpty()
                || !queryAudio.isEmpty()))) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(preparationRevision, 200);
    var images = new LinkedHashSet<String>();
    for (var image : queryImages) {
      if (image == null || !images.add(image.sha256())) {
        throw ModelValues.invalid();
      }
    }
    var selected = new LinkedHashSet<String>();
    for (int ordinal = 0; ordinal < attachments.size(); ordinal++) {
      var attachment = attachments.get(ordinal);
      if (attachment == null || attachment.ordinal() != ordinal) {
        throw ModelValues.invalid();
      }
      selected.addAll(attachment.selectedImageSha256());
    }
    if (!images.equals(selected)) {
      throw ModelValues.invalid();
    }
    if (!queryAudio.isEmpty()) {
      int index = 0;
      String decoder = null;
      for (var attachment : attachments) {
        if (attachment.mediaKind() != QueryAttachment.Kind.AUDIO) {
          continue;
        }
        long end = 0;
        int first = index;
        do {
          if (index >= queryAudio.size()) {
            throw ModelValues.invalid();
          }
          var waveform = queryAudio.get(index++);
          if (waveform == null
              || !attachment.sourceSha256().equals(waveform.sourceSha256())
              || waveform.startSample() != end
              || (decoder != null && !decoder.equals(waveform.decoderRevision()))) {
            throw ModelValues.invalid();
          }
          decoder = waveform.decoderRevision();
          end = waveform.endSample();
        } while (index < queryAudio.size()
            && queryAudio.get(index) != null
            && queryAudio.get(index).startSample() != 0);
        if (index - first > 600) {
          throw ModelValues.invalid();
        }
      }
      if (index != queryAudio.size()) {
        throw ModelValues.invalid();
      }
    }
    queryImages = List.copyOf(queryImages);
    attachments = List.copyOf(attachments);
    queryAudio = List.copyOf(queryAudio);
  }

  public static PreparedQuery text(String question) {
    return new PreparedQuery(question, question, List.of(), List.of(), "java-query-text-v1");
  }

  public String manifestSha256() {
    var values = new ArrayList<String>();
    values.add("prepared-query-manifest-v1");
    values.add(preparationRevision);
    values.add(ModelValues.sha256(originalQuestion.getBytes(StandardCharsets.UTF_8)));
    values.add(ModelValues.sha256(retrievalText.getBytes(StandardCharsets.UTF_8)));
    values.add(Integer.toString(queryImages.size()));
    queryImages.forEach(image -> values.add(image.sha256()));
    values.add(Integer.toString(attachments.size()));
    for (var item : attachments) {
      values.add(Integer.toString(item.ordinal()));
      values.add(item.sourceSha256());
      values.add(item.mediaKind().name());
      values.add(item.compilerRevision());
      values.add(item.contentSha256());
      values.add(Integer.toString(item.textCodePoints()));
      values.add(Integer.toString(item.visualCount()));
      values.add(Boolean.toString(item.visualSampled()));
      values.add(Integer.toString(item.selectedImageSha256().size()));
      values.addAll(item.selectedImageSha256());
    }
    if (!queryAudio.isEmpty()) {
      values.add("prepared-query-waveforms-v1");
      values.add(Integer.toString(queryAudio.size()));
      for (var waveform : queryAudio) {
        values.add(waveform.sourceSha256());
        values.add(waveform.decoderRevision());
        values.add(Long.toString(waveform.startSample()));
        values.add(Long.toString(waveform.endSample()));
        values.add(waveform.pcmSha256());
      }
    }
    var encoded = new StringBuilder();
    for (String value : values) {
      encoded.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }
    return ModelValues.sha256(encoded.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "PreparedQuery[redacted]";
  }
}
