package com.evidence.rag.model.domain;

import java.util.List;
import java.util.Set;

/** Version-bound derived navigation artifact. It is never original answer evidence. */
public record FileSynopsis(
    PublicationVersion publication,
    String inputFingerprint,
    String modelRevision,
    String policyRevision,
    List<Entry> entries,
    String unavailableReason) {
  private static final Set<String> REASONS =
      Set.of(
          "model_failure",
          "model_refused",
          "unsupported_claims",
          "incomplete_evidence",
          "configuration_changed",
          "source_changed",
          "processing_interrupted",
          "processing_timeout");

  /** Server-derived identity and digest only: no original text or image bytes are retained. */
  public record Reference(
      String id, String sha256, SynopsisEvidence.Kind kind, SynopsisEvidence.TimeRange time) {
    public Reference {
      ModelValues.identifier(id, 128);
      if (sha256 == null
          || !sha256.matches("[a-f0-9]{64}")
          || kind == null
          || SynopsisEvidence.requiresTime(kind) != (time != null)) {
        throw ModelValues.invalid();
      }
    }

    @Override
    public String toString() {
      return "FileSynopsis.Reference[redacted]";
    }
  }

  public record Entry(
      SynopsisDraft.Item item, List<Reference> evidence, SynopsisEvidence.TimeRange interval) {
    public Entry {
      if (item == null || evidence == null || evidence.stream().anyMatch(e -> e == null)) {
        throw ModelValues.invalid();
      }
      if (!item.evidenceIds().equals(evidence.stream().map(Reference::id).toList())) {
        throw ModelValues.invalid();
      }
      if (item.section() == SynopsisDraft.Section.TIMELINE) {
        if (interval == null || evidence.stream().anyMatch(e -> e.time() == null)) {
          throw ModelValues.invalid();
        }
        long start = evidence.stream().mapToLong(e -> e.time().startUs()).min().orElseThrow();
        long end = evidence.stream().mapToLong(e -> e.time().endUs()).max().orElseThrow();
        if (interval.startUs() != start || interval.endUs() != end) {
          throw ModelValues.invalid();
        }
      } else if (interval != null) {
        throw ModelValues.invalid();
      }
      evidence = List.copyOf(evidence);
    }

    @Override
    public String toString() {
      return "FileSynopsis.Entry[redacted]";
    }
  }

  public FileSynopsis {
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(policyRevision, 200);
    if (publication == null
        || inputFingerprint == null
        || !inputFingerprint.matches("[a-f0-9]{64}")
        || entries == null
        || entries.size() > 32
        || entries.stream().anyMatch(e -> e == null)) {
      throw ModelValues.invalid();
    }
    if (unavailableReason != null) {
      if (!REASONS.contains(unavailableReason) || !entries.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else if (entries.stream()
                .filter(e -> e.item().section() == SynopsisDraft.Section.OVERVIEW)
                .count()
            != 1
        || entries.stream().noneMatch(e -> e.item().section() == SynopsisDraft.Section.TOPIC)
        || entries.stream().noneMatch(e -> e.item().section() == SynopsisDraft.Section.TERM)) {
      throw ModelValues.invalid();
    }
    entries = List.copyOf(entries);
  }

  @Override
  public String toString() {
    return "FileSynopsis[redacted]";
  }
}
