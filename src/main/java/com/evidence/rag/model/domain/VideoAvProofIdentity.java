package com.evidence.rag.model.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Pure identity shared by original-media assessment and immutable authority revalidation. */
public final class VideoAvProofIdentity {
  public static final String POLICY_REVISION = "java-video-av-answer-v1";

  private VideoAvProofIdentity() {}

  public static String stableFactId(
      String questionSha, int ordinal, String text, VideoAvRequirement requirement) {
    hash(questionSha);
    if (ordinal < 0 || ordinal >= 16 || requirement == null || text == null || text.isBlank()) {
      throw ModelValues.invalid();
    }
    ModelValues.bounded(text, 1024);
    return fields(
        List.of(
            "video-av-fact-v1", questionSha, Integer.toString(ordinal), text, requirement.name()));
  }

  public static String canonicalFactsJson(List<VideoAvFact> facts) {
    if (facts == null || facts.isEmpty() || facts.size() > 16) {
      throw ModelValues.invalid();
    }
    var ids = new HashSet<String>();
    var texts = new HashSet<String>();
    var json = new StringBuilder("[");
    int bytes = 0;
    for (var fact : facts) {
      if (fact == null
          || !ids.add(fact.id())
          || fact.text() == null
          || fact.text().isBlank()
          || !texts.add(fact.text())
          || fact.requirement() == null) {
        throw ModelValues.invalid();
      }
      hash(fact.id());
      ModelValues.bounded(fact.text(), 1024);
      bytes += fact.text().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 8192) {
        throw ModelValues.invalid();
      }
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"id\":\"")
          .append(fact.id())
          .append("\",\"text\":\"")
          .append(escape(fact.text()))
          .append("\",\"requirement\":\"")
          .append(fact.requirement().name())
          .append("\",\"visual_contribution\":")
          .append(fact.visualContribution())
          .append(",\"audio_contribution\":")
          .append(fact.audioContribution())
          .append('}');
    }
    return json.append(']').toString();
  }

  public static String factsSha256(List<VideoAvFact> facts) {
    return sha(canonicalFactsJson(facts));
  }

  public static boolean contributionsMatch(VideoAvMode mode, List<VideoAvFact> facts) {
    if (mode == null || facts == null || facts.isEmpty()) {
      return false;
    }
    boolean visual = false;
    boolean audio = false;
    for (var fact : facts) {
      if (fact == null || fact.requirement() == null) {
        return false;
      }
      boolean needsVisual = fact.requirement() != VideoAvRequirement.AUDIO;
      boolean needsAudio = fact.requirement() != VideoAvRequirement.VISUAL;
      if (fact.visualContribution() != needsVisual
          || fact.audioContribution() != needsAudio
          || (mode == VideoAvMode.VISUAL && needsAudio)
          || (mode == VideoAvMode.AUDIO && needsVisual)) {
        return false;
      }
      visual |= fact.visualContribution();
      audio |= fact.audioContribution();
    }
    return mode != VideoAvMode.JOINT || (visual && audio);
  }

  public static boolean matches(
      VideoAvProof proof, String questionSha, String modelRevision, String policyRevision) {
    if (proof == null || !contributionsMatch(proof.mode(), proof.facts())) {
      return false;
    }
    for (int ordinal = 0; ordinal < proof.facts().size(); ordinal++) {
      var fact = proof.facts().get(ordinal);
      if (!fact.id().equals(stableFactId(questionSha, ordinal, fact.text(), fact.requirement()))) {
        return false;
      }
    }
    return proof.factsSha256().equals(factsSha256(proof.facts()))
        && proof
            .proofSha256()
            .equals(
                digest(
                    questionSha,
                    proof.evidence(),
                    proof.mode(),
                    proof.factsSha256(),
                    modelRevision,
                    policyRevision));
  }

  public static String digest(
      String questionSha,
      VideoAvEvidence evidence,
      VideoAvMode mode,
      String factsSha,
      String modelRevision,
      String policyRevision) {
    hash(questionSha);
    hash(factsSha);
    if (evidence == null
        || mode == null
        || !POLICY_REVISION.equals(policyRevision)
        || !evidence.publication().analysisModelRevision().equals(modelRevision)
        || !evidence.publication().windows().contains(evidence.window())
        || (mode != VideoAvMode.AUDIO && evidence.window().video() == null)
        || (mode != VideoAvMode.VISUAL && evidence.window().audio() == null)) {
      throw ModelValues.invalid();
    }
    var publication = evidence.publication();
    var window = evidence.window();
    return fields(
        List.of(
            "video-av-proof-v1",
            questionSha,
            mode.name(),
            publication.workspaceId(),
            publication.documentId(),
            publication.id(),
            publication.sourceRevisionId(),
            publication.sourceSha256(),
            publication.profileFingerprint(),
            publication.decoderRevision(),
            publication.manifestSha256(),
            window.id(),
            Integer.toString(window.ordinal()),
            Long.toString(window.startTick()),
            Long.toString(window.endTick()),
            factsSha,
            modelRevision,
            policyRevision));
  }

  public static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static void hash(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw ModelValues.invalid();
    }
  }

  private static String fields(List<String> values) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
