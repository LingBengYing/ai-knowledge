package com.evidence.rag.model.domain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;

/** Pure identities for complete original-sound publications, independent of speech receipts. */
public final class SoundProfile {
  private SoundProfile() {}

  public static String fingerprint(
      IndexTarget target, String sound, String decoder, int chunkSeconds) {
    if (target == null || chunkSeconds < 1 || chunkSeconds > 30) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(sound, 200);
    ModelValues.identifier(decoder, 200);
    return ModelValues.sha256(
        ("java-sound-profile-v1\0"
                + target.embeddingIdentity()
                + "\0"
                + target.projectionIdentity()
                + "\0"
                + target.modelRevision()
                + "\0"
                + target.dimensions()
                + "\0"
                + sound
                + "\0"
                + decoder
                + "\0"
                + chunkSeconds)
            .getBytes(StandardCharsets.UTF_8));
  }

  public static String spanId(String revision, int ordinal) {
    ModelValues.identifier(revision, 128);
    if (ordinal < 0 || ordinal > 599) {
      throw ModelValues.invalid();
    }
    return "sound-"
        + ModelValues.sha256((revision + "\0" + ordinal).getBytes(StandardCharsets.UTF_8));
  }

  public static String physicalSegmentId(String generation, String spanId) {
    return "seg-" + digest(List.of("evidence-rag-physical-segment-v1", generation, spanId), null);
  }

  public static String manifestSha256(
      String workspace, String document, String generation, List<SoundSpan> spans) {
    if (spans == null || spans.isEmpty() || spans.size() > 600) {
      throw ModelValues.invalid();
    }
    var entries = new TreeMap<String, String>();
    for (var span : spans) {
      if (span == null || entries.put(span.physicalSegmentId(), span.entrySha256()) != null) {
        throw ModelValues.invalid();
      }
    }
    return digest(
        List.of("evidence-rag-revision-manifest-v1", workspace, document, generation), entries);
  }

  private static String digest(List<String> values, TreeMap<String, String> entries) {
    try {
      var bytes = new ByteArrayOutputStream();
      var writer = new DataOutputStream(bytes);
      for (String value : values) {
        ModelValues.identifier(value, 200);
        text(writer, value);
      }
      if (entries != null) {
        writer.writeInt(entries.size());
        for (var entry : entries.entrySet()) {
          text(writer, entry.getKey());
          text(writer, entry.getValue());
        }
      }
      return ModelValues.sha256(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Sound identity encoding failed");
    }
  }

  private static void text(DataOutputStream writer, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    writer.writeInt(bytes.length);
    writer.write(bytes);
  }

  static void hash(String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }
}
