package com.evidence.rag.model.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Pure immutable proof identity shared by assessment and authority revalidation. */
public final class SoundProofIdentity {
  public static final String POLICY_REVISION = "java-sound-answer-v1";

  private SoundProofIdentity() {}

  public static boolean matches(
      SoundProof proof, String questionSha, String modelRevision, String policyRevision) {
    return proof.factsSha256().equals(SoundProof.factsSha256(proof.facts()))
        && proof
            .proofSha256()
            .equals(
                digest(
                    questionSha,
                    proof.source(),
                    proof.factsSha256(),
                    modelRevision,
                    policyRevision));
  }

  public static String digest(
      String questionSha,
      SoundPublishedSpan source,
      String factsSha,
      String modelRevision,
      String policyRevision) {
    if (questionSha == null
        || !questionSha.matches("[a-f0-9]{64}")
        || !POLICY_REVISION.equals(policyRevision)
        || !source.publication().soundModelRevision().equals(modelRevision)) {
      throw ModelValues.invalid();
    }
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var publication = source.publication();
      var span = source.span();
      for (String value :
          List.of(
              "evidence-rag-sound-proof-v1",
              questionSha,
              publication.workspaceId(),
              publication.documentId(),
              publication.id(),
              publication.sourceRevisionId(),
              publication.sourceSha256(),
              publication.profileFingerprint(),
              publication.decoderRevision(),
              span.id(),
              span.pcmSha256(),
              Long.toString(span.startSample()),
              Long.toString(span.endSample()),
              factsSha,
              modelRevision,
              policyRevision)) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public static String sha(String value) {
    return ModelValues.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
