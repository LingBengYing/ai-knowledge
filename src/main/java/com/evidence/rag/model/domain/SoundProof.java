package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** Complete verified fact list bound by the assessment Module to the actual original PCM. */
public record SoundProof(
    SoundPublishedSpan source, List<String> facts, String factsSha256, String proofSha256) {
  public SoundProof {
    SoundProfile.hash(proofSha256);
    if (source == null || !factsSha256(facts).equals(factsSha256)) {
      throw ModelValues.invalid();
    }
    facts = List.copyOf(facts);
  }

  public static String factsJson(List<String> facts) {
    if (facts == null || facts.isEmpty() || facts.size() > 16) {
      throw ModelValues.invalid();
    }
    var unique = new HashSet<String>();
    var json = new StringBuilder("[");
    int bytes = 0;
    for (String fact : facts) {
      if (fact == null || fact.isBlank() || !unique.add(fact)) {
        throw ModelValues.invalid();
      }
      ModelValues.bounded(fact, 1024);
      bytes += fact.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 8192) {
        throw ModelValues.invalid();
      }
      if (json.length() > 1) {
        json.append(',');
      }
      json.append('"').append(fact.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return json.append(']').toString();
  }

  public static String factsSha256(List<String> facts) {
    return ModelValues.sha256(factsJson(facts).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "SoundProof[redacted]";
  }
}
