package com.evidence.rag.model.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure length-prefixed provenance identities; no SQL, client or provider dependency. */
public final class VectorBindingIdentity {
  public static final String POLICY_REVISION = "java-vector-binding-v1";

  private VectorBindingIdentity() {}

  public static ImageVectorBinding directImage(ImageVectorPublication origin) {
    if (origin == null) {
      throw ModelValues.invalid();
    }
    var base = origin.basePublication();
    String physical = origin.basePhysicalSegmentId();
    return new ImageVectorBinding(
        base, origin, physical, null, imageSha256(base, origin, physical, null));
  }

  public static AudioVectorBinding directAudio(AudioVectorPublication origin) {
    if (origin == null) {
      throw ModelValues.invalid();
    }
    var base = origin.basePublication();
    var physical = origin.entries().stream().map(AudioVectorEntry::basePhysicalSegmentId).toList();
    return new AudioVectorBinding(
        base, origin, physical, null, audioSha256(base, origin, physical, null));
  }

  public static String physicalSegmentId(String generation, String evidenceId) {
    ModelValues.identifier(generation, 128);
    ModelValues.identifier(evidenceId, 128);
    var digest = digest();
    text(digest, "evidence-rag-physical-segment-v1");
    text(digest, generation);
    text(digest, evidenceId);
    return "seg-" + HexFormat.of().formatHex(digest.digest());
  }

  public static String manifestSha256(
      String workspace, String document, String generation, Map<String, String> entries) {
    var digest = digest();
    text(digest, "evidence-rag-revision-manifest-v1");
    text(digest, workspace);
    text(digest, document);
    text(digest, generation);
    integer(digest, entries.size());
    for (var entry : new TreeMap<>(entries).entrySet()) {
      text(digest, entry.getKey());
      text(digest, entry.getValue());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String targetSha256(IndexTarget value) {
    var digest = digest();
    target(digest, value);
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String imageSha256(
      PublicationVersion base, ImageVectorPublication origin, String current, String from) {
    var digest = start("image", base, from);
    image(digest, origin);
    text(digest, current);
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String audioSha256(
      PublicationVersion base, AudioVectorPublication origin, List<String> current, String from) {
    var digest = start("audio", base, from);
    audio(digest, origin);
    integer(digest, current.size());
    for (String value : current) {
      text(digest, value);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static String imageSha256(
      PublicationVersion base,
      ImageVectorPublication origin,
      String current,
      String from,
      String modelRebuildId) {
    String ordinary = imageSha256(base, origin, current, from);
    return modelRebuildId == null
        ? ordinary
        : ModelValues.sha256(
            (ordinary + "\0java-model-rebuild-vector-binding-v1\0" + modelRebuildId)
                .getBytes(StandardCharsets.UTF_8));
  }

  public static String audioSha256(
      PublicationVersion base,
      AudioVectorPublication origin,
      List<String> current,
      String from,
      String modelRebuildId) {
    String ordinary = audioSha256(base, origin, current, from);
    return modelRebuildId == null
        ? ordinary
        : ModelValues.sha256(
            (ordinary + "\0java-model-rebuild-vector-binding-v1\0" + modelRebuildId)
                .getBytes(StandardCharsets.UTF_8));
  }

  static void requireSameMaterials(PublicationVersion current, PublicationVersion origin) {
    if (!current.documentId().equals(origin.documentId())
        || !current.sourceRevisionId().equals(origin.sourceRevisionId())
        || !current.sourceSha256().equals(origin.sourceSha256())
        || !current.parserRevision().equals(origin.parserRevision())
        || current.segmentCount() != origin.segmentCount()) {
      throw ModelValues.invalid();
    }
  }

  public static String setSha256(
      String workspace,
      PublicationVersion base,
      List<ImageVectorBinding> images,
      List<AudioVectorBinding> audios) {
    var digest = digest();
    text(digest, POLICY_REVISION + ":set");
    text(digest, workspace);
    publication(digest, base);
    var ordered = new ArrayList<List<String>>();
    for (var binding : images) {
      ordered.add(List.of("image", binding.origin().id(), binding.bindingSha256()));
    }
    for (var binding : audios) {
      ordered.add(List.of("audio", binding.origin().id(), binding.bindingSha256()));
    }
    ordered.sort(Comparator.comparing((List<String> v) -> v.get(0)).thenComparing(v -> v.get(1)));
    integer(digest, ordered.size());
    for (var entry : ordered) {
      for (String field : entry) {
        text(digest, field);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static void requireSameSource(PublicationVersion current, PublicationVersion origin) {
    if (!current.documentId().equals(origin.documentId())
        || !current.sourceRevisionId().equals(origin.sourceRevisionId())
        || !current.sourceSha256().equals(origin.sourceSha256())
        || !current.parserRevision().equals(origin.parserRevision())
        || !current.target().equals(origin.target())
        || current.segmentCount() != origin.segmentCount()) {
      throw ModelValues.invalid();
    }
  }

  static void requireProvenance(
      PublicationVersion current, PublicationVersion origin, String from) {
    if (from == null) {
      if (!current.equals(origin)) {
        throw ModelValues.invalid();
      }
    } else {
      ModelValues.identifier(from, 128);
      if (from.equals(current.publicationId())) {
        throw ModelValues.invalid();
      }
    }
  }

  private static MessageDigest start(String route, PublicationVersion base, String from) {
    var digest = digest();
    text(digest, POLICY_REVISION);
    text(digest, route);
    publication(digest, base);
    text(digest, from == null ? "direct" : "inherited");
    if (from != null) {
      text(digest, from);
    }
    return digest;
  }

  private static void image(MessageDigest digest, ImageVectorPublication origin) {
    text(digest, origin.id());
    publication(digest, origin.basePublication());
    text(digest, origin.imageEvidenceId());
    text(digest, origin.basePhysicalSegmentId());
    text(digest, origin.vectorGenerationId());
    text(digest, origin.vectorPhysicalSegmentId());
    target(digest, origin.target());
    text(digest, origin.entrySha256());
    text(digest, origin.manifestSha256());
    text(digest, origin.createdAt());
  }

  private static void audio(MessageDigest digest, AudioVectorPublication origin) {
    text(digest, origin.id());
    publication(digest, origin.basePublication());
    target(digest, origin.target());
    text(digest, origin.vectorGenerationId());
    text(digest, origin.decoderRevision());
    text(digest, origin.manifestSha256());
    text(digest, origin.createdAt());
    integer(digest, origin.entries().size());
    for (var entry : origin.entries()) {
      text(digest, entry.audioEvidenceId());
      text(digest, entry.basePhysicalSegmentId());
      text(digest, entry.vectorPhysicalSegmentId());
      integer(digest, entry.ordinal());
      number(digest, entry.startSample());
      number(digest, entry.endSample());
      text(digest, entry.pcmSha256());
      text(digest, entry.entrySha256());
    }
  }

  private static void publication(MessageDigest digest, PublicationVersion value) {
    text(digest, value.documentId());
    text(digest, value.publicationId());
    text(digest, value.sourceRevisionId());
    text(digest, value.projectionGenerationId());
    text(digest, value.sourceSha256());
    text(digest, value.parserRevision());
    target(digest, value.target());
    text(digest, value.manifestSha256());
    integer(digest, value.segmentCount());
  }

  private static void target(MessageDigest digest, IndexTarget value) {
    text(digest, value.embeddingIdentity());
    text(digest, value.projectionIdentity());
    text(digest, value.modelRevision());
    integer(digest, value.dimensions());
  }

  private static void text(MessageDigest digest, String value) {
    if (value == null) {
      throw ModelValues.invalid();
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    integer(digest, bytes.length);
    digest.update(bytes);
  }

  private static void integer(MessageDigest digest, int value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
  }

  private static void number(MessageDigest digest, long value) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
