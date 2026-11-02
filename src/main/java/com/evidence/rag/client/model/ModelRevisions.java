package com.evidence.rag.client.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Pinned model profile revisions: SHA-256 over NUL-terminated UTF-8 values. */
final class ModelRevisions {
  private ModelRevisions() {}

  static String fingerprint(String prefix, List<String> values) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return prefix + HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new TextModels.Failure("model_invalid_configuration");
    }
  }
}
