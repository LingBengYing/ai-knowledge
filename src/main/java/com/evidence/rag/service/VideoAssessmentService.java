package com.evidence.rag.service;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.GroundedQuote;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VideoEvidenceGroup;
import com.evidence.rag.model.domain.VideoFactProof;
import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VideoProofInput;
import com.evidence.rag.tool.answer.QuestionPlanning;
import com.evidence.rag.tool.answer.TextGrounding;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Group-local proof Module; callers still own full authorization, retrieval and durable trace. */
public final class VideoAssessmentService {
  public static final String POLICY_REVISION =
      "java-video-assessment-v1:"
          + ModelValues.sha256(
              (QuestionPlanning.VERSION
                      + "\n"
                      + FactTextModels.PROMPT_REVISION
                      + "\n"
                      + FactVisionModels.PROMPT_REVISION
                      + "\n"
                      + TextGrounding.VERSION)
                  .getBytes(StandardCharsets.UTF_8));
  private final FactTextModels text;
  private final FactVisionModels vision;
  private final long budgetNanos;

  public VideoAssessmentService(FactTextModels text, FactVisionModels vision, Duration budget) {
    if (text == null
        || vision == null
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.text = text;
    this.vision = vision;
    budgetNanos = budget.toNanos();
  }

  public VideoAssessment assess(
      String question, VideoProofInput input, VideoAssessment.Mode mode, BooleanSupplier current) {
    return assess(question, input, mode, current, System.nanoTime());
  }

  public VideoAssessment assess(
      String question,
      String revisionId,
      VideoCompilation compilation,
      String groupId,
      VideoAssessment.Mode mode,
      BooleanSupplier current) {
    long started = System.nanoTime();
    if (question == null || current == null || mode == null) {
      throw ModelValues.invalid();
    }
    var material = VideoEvidence.fromCompilation(revisionId, compilation);
    var group =
        material.groups().stream()
            .filter(g -> g.id().equals(groupId))
            .findFirst()
            .orElseThrow(ModelValues::invalid);
    var frame =
        material.frames().stream()
            .filter(f -> f.id().equals(group.frameId()))
            .map(f -> f.material().frame())
            .findFirst()
            .orElse(null);
    var input =
        new VideoProofInput(
            compilation.sourceSha256(),
            material.manifestSha256(),
            group,
            frame,
            transcript(material, group));
    return assess(question, input, mode, current, started);
  }

  private VideoAssessment assess(
      String question,
      VideoProofInput input,
      VideoAssessment.Mode mode,
      BooleanSupplier current,
      long started) {
    if (question == null || input == null || current == null || mode == null) {
      throw ModelValues.invalid();
    }
    var group = input.group();
    var frame = input.frame();
    var transcript = input.transcript();
    String questionSha = ModelValues.sha256(question.getBytes(StandardCharsets.UTF_8));
    String textRevision = ModelValues.identifier(text.revision(), 200);
    String visionRevision = ModelValues.identifier(vision.revision(), 200);
    var plan = QuestionPlanning.plan(question);
    var factIds =
        plan.map(p -> p.facts().stream().map(QuestionFact::id).toList()).orElse(List.of());
    var proofs = new ArrayList<VideoFactProof>();
    String refusal = null;
    try {
      check(current, started, textRevision, visionRevision);
      if (plan.isEmpty()) {
        throw new Stopped("unsupported_question");
      }
      if ((mode != VideoAssessment.Mode.TRANSCRIPT && frame == null)
          || (mode != VideoAssessment.Mode.VISUAL && transcript == null)) {
        throw new Stopped("incomplete_evidence");
      }
      if (frame != null && mode != VideoAssessment.Mode.TRANSCRIPT) {
        validateFrame(frame);
      }
      for (var fact : plan.orElseThrow().facts()) {
        List<String> claims =
            mode == VideoAssessment.Mode.TRANSCRIPT
                ? List.of()
                : visual(question, fact, frame, current, started, textRevision, visionRevision);
        // Counterevidence is independent of whether the extraction model chose to quote it.
        // This can only veto a result; it never adds transcript support to the proof.
        if (!claims.isEmpty()
            && transcript != null
            && QuestionPlanning.conflictsWithTranscript(question, fact, claims, transcript)) {
          throw new Stopped("conflicting_evidence");
        }
        List<GroundedQuote> quotes =
            mode == VideoAssessment.Mode.VISUAL
                ? List.of()
                : transcript(
                    question, fact, transcript, current, started, textRevision, visionRevision);
        check(current, started, textRevision, visionRevision);
        if (claims.isEmpty() && quotes.isEmpty()) {
          throw new Stopped("incomplete_evidence");
        }
        if (!claims.isEmpty()
            && !quotes.isEmpty()
            && !QuestionPlanning.agree(
                question, fact, claims, quotes.stream().map(GroundedQuote::quote).toList())) {
          throw new Stopped("conflicting_evidence");
        }
        proofs.add(new VideoFactProof(fact.id(), claims, quotes));
      }
      if (mode == VideoAssessment.Mode.JOINT
          && (proofs.stream().noneMatch(p -> p.visualSupport() == 1)
              || proofs.stream().noneMatch(p -> p.transcriptSupport() == 1))) {
        throw new Stopped("incomplete_evidence");
      }
      check(current, started, textRevision, visionRevision);
    } catch (Stopped stopped) {
      refusal = stopped.reason;
    } catch (TextModels.Failure failed) {
      if ("model_interrupted".equals(failed.code())) {
        Thread.currentThread().interrupt();
      }
      refusal = Thread.currentThread().isInterrupted() ? "processing_interrupted" : "model_failure";
    } catch (CancellationException cancelled) {
      refusal = "processing_interrupted";
    }
    return new VideoAssessment(
        input.sourceSha256(),
        input.manifestSha256(),
        group,
        questionSha,
        mode,
        factIds,
        refusal == null ? proofs : List.of(),
        textRevision,
        visionRevision,
        POLICY_REVISION,
        refusal);
  }

  private List<String> visual(
      String question,
      QuestionFact fact,
      VideoFrame frame,
      BooleanSupplier current,
      long started,
      String textRevision,
      String visionRevision) {
    check(current, started, textRevision, visionRevision);
    var draft = vision.draftFact(question, fact, frame.image());
    check(current, started, textRevision, visionRevision);
    if (draft == null
        || (draft.refused() ? !draft.claims().isEmpty() : !validClaims(draft.claims()))) {
      throw new Stopped("model_failure");
    }
    if (draft.refused()) {
      return List.of();
    }
    check(current, started, textRevision, visionRevision);
    var verified = vision.verifyFact(question, fact, frame.image(), draft.claims());
    check(current, started, textRevision, visionRevision);
    if (verified == null || verified.supported().size() != draft.claims().size()) {
      throw new Stopped("model_failure");
    }
    return verified.complete() && verified.supported().stream().allMatch(Boolean.TRUE::equals)
        ? draft.claims()
        : List.of();
  }

  private List<GroundedQuote> transcript(
      String question,
      QuestionFact fact,
      GroundingText candidate,
      BooleanSupplier current,
      long started,
      String textRevision,
      String visionRevision) {
    check(current, started, textRevision, visionRevision);
    var extraction =
        text.extractFact(
            question,
            fact,
            List.of(new TextModels.Evidence(candidate.physicalId(), candidate.snippet())));
    check(current, started, textRevision, visionRevision);
    if (extraction == null || extraction.refused() != extraction.quotes().isEmpty()) {
      throw new Stopped("model_failure");
    }
    if (extraction.refused()) {
      return List.of();
    }
    var input =
        extraction.quotes().stream()
            .map(q -> new GroundingQuote(q.evidenceId(), q.quote()))
            .toList();
    var grounding = new TextGrounding().verifyFact(question, fact, List.of(candidate), input);
    check(current, started, textRevision, visionRevision);
    if (grounding.supported()) {
      return grounding.quotes();
    }
    if ("incomplete_evidence".equals(grounding.reason())) {
      return List.of();
    }
    throw new Stopped(grounding.reason());
  }

  private static GroundingText transcript(VideoEvidence material, VideoEvidenceGroup group) {
    var context = new StringBuilder();
    int start = -1;
    int end = -1;
    int points = 0;
    for (var item : material.spans()) {
      if (!context.isEmpty()) {
        context.append('\n');
        points++;
      }
      int length = item.span().text().codePointCount(0, item.span().text().length());
      if (item.id().equals(group.transcriptSpanId()) && !item.span().text().isBlank()) {
        start = points;
        end = points + length;
      }
      context.append(item.span().text());
      points += length;
    }
    if (start < 0) {
      return null;
    }
    String fullText = context.toString();
    return new GroundingText(
        group.transcriptSpanId(),
        "video-transcript:" + group.revisionId(),
        fullText,
        ModelValues.sha256(fullText.getBytes(StandardCharsets.UTF_8)),
        start,
        end);
  }

  private void check(
      BooleanSupplier current, long started, String textRevision, String visionRevision) {
    deadline(started);
    boolean allowed;
    try {
      allowed = current.getAsBoolean();
    } catch (CancellationException cancelled) {
      throw cancelled;
    } catch (RuntimeException unavailable) {
      throw new Stopped("scope_changed");
    }
    if (!allowed) {
      throw new Stopped("scope_changed");
    }
    if (!textRevision.equals(text.revision()) || !visionRevision.equals(vision.revision())) {
      throw new Stopped("configuration_changed");
    }
    deadline(started);
  }

  private void deadline(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new Stopped("processing_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new Stopped("processing_timeout");
    }
  }

  private static void validateFrame(VideoFrame frame) {
    try {
      ImageInput.validateEnvelope(
          frame.image().mediaType().equals("image/png") ? "frame.png" : "frame.jpg",
          frame.image().mediaType(),
          frame.image().content());
      var size = ImageInput.inspect(frame.image().content());
      if (size.width() != frame.width() || size.height() != frame.height()) {
        throw ModelValues.invalid();
      }
    } catch (TextParser.Failure invalidImage) {
      throw ModelValues.invalid();
    }
  }

  private static boolean validClaims(List<String> claims) {
    return claims != null
        && !claims.isEmpty()
        && claims.size() <= 8
        && new HashSet<>(claims).size() == claims.size()
        && claims.stream()
            .allMatch(
                c ->
                    c != null
                        && !c.isBlank()
                        && c.codePointCount(0, c.length()) <= 1024
                        && c.codePoints().noneMatch(p -> p == 0 || (p >= 0xD800 && p <= 0xDFFF)));
  }

  private static final class Stopped extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private Stopped(String reason) {
      super(null, null, false, false);
      this.reason = reason;
    }
  }
}
