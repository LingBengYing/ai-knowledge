package com.evidence.rag.model.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Hash-only trace input. Answer text must remain outside the authority persistence boundary. */
public record TraceDraft(
    String questionSha256,
    String answerSha256,
    String outcome,
    String reasonCode,
    String modelRevision,
    String promptRevision,
    String policyRevision,
    List<TraceEvidence> evidence,
    List<VisualTraceEvidence> visualEvidence,
    List<AudioTraceEvidence> audioEvidence,
    List<VideoTraceEvidence> videoEvidence,
    VideoTraceProof videoProof,
    List<TraceEvidence> videoOcrEvidence,
    List<TraceEvidence> videoSubtitleEvidence,
    QueryTrace queryTrace) {
  public TraceDraft(
      String questionSha256,
      String answerSha256,
      String outcome,
      String reasonCode,
      String modelRevision,
      String promptRevision,
      String policyRevision,
      List<TraceEvidence> evidence,
      List<VisualTraceEvidence> visualEvidence,
      List<AudioTraceEvidence> audioEvidence,
      List<VideoTraceEvidence> videoEvidence,
      VideoTraceProof videoProof,
      List<TraceEvidence> videoOcrEvidence,
      List<TraceEvidence> videoSubtitleEvidence) {
    this(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        visualEvidence,
        audioEvidence,
        videoEvidence,
        videoProof,
        videoOcrEvidence,
        videoSubtitleEvidence,
        null);
  }

  public TraceDraft withQueryTrace(QueryTrace trace) {
    return new TraceDraft(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        visualEvidence,
        audioEvidence,
        videoEvidence,
        videoProof,
        videoOcrEvidence,
        videoSubtitleEvidence,
        trace);
  }

  public TraceDraft(
      String questionSha256,
      String answerSha256,
      String outcome,
      String reasonCode,
      String modelRevision,
      String promptRevision,
      String policyRevision,
      List<TraceEvidence> evidence,
      List<VisualTraceEvidence> visualEvidence,
      List<AudioTraceEvidence> audioEvidence,
      List<VideoTraceEvidence> videoEvidence,
      VideoTraceProof videoProof,
      List<TraceEvidence> videoOcrEvidence) {
    this(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        visualEvidence,
        audioEvidence,
        videoEvidence,
        videoProof,
        videoOcrEvidence,
        List.of());
  }

  public TraceDraft(
      String questionSha256,
      String answerSha256,
      String outcome,
      String reasonCode,
      String modelRevision,
      String promptRevision,
      String policyRevision,
      List<TraceEvidence> evidence,
      List<VisualTraceEvidence> visualEvidence,
      List<AudioTraceEvidence> audioEvidence,
      List<VideoTraceEvidence> videoEvidence,
      VideoTraceProof videoProof) {
    this(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        visualEvidence,
        audioEvidence,
        videoEvidence,
        videoProof,
        List.of());
  }

  public TraceDraft(
      String questionSha256,
      String answerSha256,
      String outcome,
      String reasonCode,
      String modelRevision,
      String promptRevision,
      String policyRevision,
      List<TraceEvidence> evidence,
      List<VisualTraceEvidence> visualEvidence,
      List<AudioTraceEvidence> audioEvidence) {
    this(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        visualEvidence,
        audioEvidence,
        List.of(),
        null);
  }

  public TraceDraft(
      String questionSha256,
      String answerSha256,
      String outcome,
      String reasonCode,
      String modelRevision,
      String promptRevision,
      String policyRevision,
      List<TraceEvidence> evidence,
      List<VisualTraceEvidence> visualEvidence) {
    this(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        visualEvidence,
        List.of());
  }

  public TraceDraft(
      String questionSha256,
      String answerSha256,
      String outcome,
      String reasonCode,
      String modelRevision,
      String promptRevision,
      String policyRevision,
      List<TraceEvidence> evidence) {
    this(
        questionSha256,
        answerSha256,
        outcome,
        reasonCode,
        modelRevision,
        promptRevision,
        policyRevision,
        evidence,
        List.of(),
        List.of());
  }

  public TraceDraft {
    ModelValues.identifier(modelRevision, 160);
    ModelValues.identifier(promptRevision, 160);
    ModelValues.identifier(policyRevision, 160);
    if (queryTrace != null
        && (!queryTrace.questionSha256().equals(questionSha256)
            || ("answered".equals(outcome) && !"prepared".equals(queryTrace.status())))) {
      throw ModelValues.invalid();
    }
    if (questionSha256 == null
        || !questionSha256.matches("[a-f0-9]{64}")
        || outcome == null
        || !Set.of("answered", "abstained").contains(outcome)
        || evidence == null
        || visualEvidence == null
        || audioEvidence == null
        || videoEvidence == null
        || videoOcrEvidence == null
        || videoSubtitleEvidence == null
        || evidence.size()
                + visualEvidence.size()
                + audioEvidence.size()
                + videoEvidence.size()
                + videoOcrEvidence.size()
                + videoSubtitleEvidence.size()
            > 32
        || videoEvidence.isEmpty() != (videoProof == null)) {
      throw ModelValues.invalid();
    }
    if ("answered".equals(outcome)) {
      if (answerSha256 == null
          || !answerSha256.matches("[a-f0-9]{64}")
          || reasonCode != null
          || evidence.isEmpty()
              && visualEvidence.isEmpty()
              && audioEvidence.isEmpty()
              && videoEvidence.isEmpty()
              && videoOcrEvidence.isEmpty()
              && videoSubtitleEvidence.isEmpty()) {
        throw ModelValues.invalid();
      }
    } else if (answerSha256 != null
        || !evidence.isEmpty()
        || !visualEvidence.isEmpty()
        || !audioEvidence.isEmpty()
        || !videoEvidence.isEmpty()
        || !videoOcrEvidence.isEmpty()
        || !videoSubtitleEvidence.isEmpty()
        || reasonCode == null
        || !reasonCode.matches("[a-z][a-z0-9_]{0,63}")) {
      throw ModelValues.invalid();
    }
    boolean[] ordinals =
        new boolean
            [evidence.size()
                + visualEvidence.size()
                + audioEvidence.size()
                + videoEvidence.size()
                + videoOcrEvidence.size()
                + videoSubtitleEvidence.size()
                + 1];
    int previous = 0;
    for (var item : evidence) {
      if (item == null) {
        throw ModelValues.invalid();
      }
      previous = claimOrdinal(ordinals, previous, item.citationOrdinal());
    }
    previous = 0;
    for (var item : visualEvidence) {
      if (item == null) {
        throw ModelValues.invalid();
      }
      previous = claimOrdinal(ordinals, previous, item.citationOrdinal());
    }
    evidence = List.copyOf(evidence);
    visualEvidence = List.copyOf(visualEvidence);
    previous = 0;
    for (var item : audioEvidence) {
      if (item == null) {
        throw ModelValues.invalid();
      }
      previous = claimOrdinal(ordinals, previous, item.citationOrdinal());
    }
    audioEvidence = List.copyOf(audioEvidence);
    previous = 0;
    for (var item : videoEvidence) {
      if (item == null) {
        throw ModelValues.invalid();
      }
      previous = claimOrdinal(ordinals, previous, item.citationOrdinal());
    }
    if (videoProof != null) {
      var actual = new HashMap<String, Set<VideoTraceEvidence.Kind>>();
      for (var item : videoEvidence) {
        actual.computeIfAbsent(item.factSha256(), ignored -> new HashSet<>()).add(item.kind());
      }
      if (actual.size() != videoProof.facts().size()) {
        throw ModelValues.invalid();
      }
      for (var fact : videoProof.facts()) {
        var kinds = actual.get(fact.factSha256());
        if (kinds == null
            || kinds.contains(VideoTraceEvidence.Kind.VISUAL) != (fact.visualSupport() == 1)
            || kinds.contains(VideoTraceEvidence.Kind.TRANSCRIPT)
                != (fact.transcriptSupport() == 1)) {
          throw ModelValues.invalid();
        }
      }
    }
    videoEvidence = List.copyOf(videoEvidence);
    previous = 0;
    for (var item : videoOcrEvidence) {
      if (item == null) {
        throw ModelValues.invalid();
      }
      previous = claimOrdinal(ordinals, previous, item.citationOrdinal());
    }
    videoOcrEvidence = List.copyOf(videoOcrEvidence);
    previous = 0;
    for (var item : videoSubtitleEvidence) {
      if (item == null) {
        throw ModelValues.invalid();
      }
      previous = claimOrdinal(ordinals, previous, item.citationOrdinal());
    }
    videoSubtitleEvidence = List.copyOf(videoSubtitleEvidence);
  }

  private static int claimOrdinal(boolean[] ordinals, int previous, int ordinal) {
    if (ordinal <= previous || ordinal >= ordinals.length || ordinals[ordinal]) {
      throw ModelValues.invalid();
    }
    ordinals[ordinal] = true;
    return ordinal;
  }

  @Override
  public String toString() {
    return "TraceDraft[redacted]";
  }
}
