package com.evidence.rag.model.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Hash-only identity of the complete reference batch, including unsuccessful preparation. */
public record VideoAvQueryTrace(
    VideoAvMode mode,
    String questionSha256,
    String profileFingerprint,
    String embeddingRevision,
    List<VideoAvQueryManifest> attachments) {
  public static final String PREPARATION_REVISION = "java-video-av-query-preparation-v1";

  public VideoAvQueryTrace {
    VideoAvProfile.hash(questionSha256);
    VideoAvProfile.hash(profileFingerprint);
    ModelValues.identifier(embeddingRevision, 200);
    if (mode == null || attachments == null || attachments.isEmpty() || attachments.size() > 3) {
      throw ModelValues.invalid();
    }
    String compiler = null;
    String status = null;
    int windows = 0;
    for (int index = 0; index < attachments.size(); index++) {
      var attachment = attachments.get(index);
      if (attachment == null || attachment.ordinal() != index || attachment.usedMode() != mode) {
        throw ModelValues.invalid();
      }
      if (index == 0) {
        compiler = attachment.compilerRevision();
        status = attachment.status();
      } else if (!compiler.equals(attachment.compilerRevision())
          || !status.equals(attachment.status())) {
        throw ModelValues.invalid();
      }
      if (attachment.windowCount() != null) {
        windows += attachment.windowCount();
      }
    }
    if (windows > 1201) {
      throw ModelValues.invalid();
    }
    attachments = List.copyOf(attachments);
  }

  public String preparationRevision() {
    return PREPARATION_REVISION;
  }

  public String manifestSha256() {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      for (String value :
          List.of(
              PREPARATION_REVISION,
              mode.name(),
              questionSha256,
              profileFingerprint,
              embeddingRevision,
              Integer.toString(attachments.size()))) {
        write(output, value);
      }
      for (var attachment : attachments) {
        write(output, Integer.toString(attachment.ordinal()));
        write(output, attachment.sourceSha256());
        write(output, attachment.mediaKind());
        write(output, attachment.compilerRevision());
        write(output, attachment.contentSha256());
        write(
            output, attachment.windowCount() == null ? null : attachment.windowCount().toString());
        write(
            output,
            attachment.visualWindowCount() == null
                ? null
                : attachment.visualWindowCount().toString());
        write(
            output,
            attachment.audioWindowCount() == null
                ? null
                : attachment.audioWindowCount().toString());
        write(
            output,
            attachment.audioPresent() == null ? null : attachment.audioPresent().toString());
        write(output, attachment.usedMode().name());
        write(output, attachment.status());
      }
      return ModelValues.sha256(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Video query identity unavailable");
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] bytes = (value == null ? "NULL" : value).getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  @Override
  public String toString() {
    return "VideoAvQueryTrace[redacted]";
  }
}
