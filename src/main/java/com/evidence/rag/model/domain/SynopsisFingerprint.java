package com.evidence.rag.model.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** The original v1 raw-content encoding, shared without changing any short-file fingerprint. */
final class SynopsisFingerprint {
  private SynopsisFingerprint() {}

  static String of(PublicationVersion publication, List<SynopsisEvidence> evidence) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      field(digest, "java-file-synopsis-input-v1");
      field(digest, publication.documentId());
      field(digest, publication.publicationId());
      field(digest, publication.sourceRevisionId());
      field(digest, publication.projectionGenerationId());
      field(digest, publication.sourceSha256());
      field(digest, publication.parserRevision());
      field(digest, publication.target().embeddingIdentity());
      field(digest, publication.target().projectionIdentity());
      field(digest, publication.target().modelRevision());
      field(digest, Integer.toString(publication.target().dimensions()));
      field(digest, publication.manifestSha256());
      field(digest, Integer.toString(publication.segmentCount()));
      field(digest, Integer.toString(evidence.size()));
      for (var item : evidence) {
        field(digest, item.id());
        field(digest, item.kind().name());
        switch (item.content()) {
          case SynopsisEvidence.Text text -> {
            field(digest, "text");
            field(digest, text.text());
          }
          case SynopsisEvidence.Image image -> {
            field(digest, "image");
            field(digest, image.image().mediaType());
            field(digest, image.image().content());
          }
        }
        if (item.time() == null) {
          field(digest, "no-time");
        } else {
          field(digest, "time");
          field(digest, Long.toString(item.time().startUs()));
          field(digest, Long.toString(item.time().endUs()));
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void field(MessageDigest digest, String value) {
    field(digest, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void field(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }
}
