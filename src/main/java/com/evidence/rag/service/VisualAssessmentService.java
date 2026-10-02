package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VisualAssessment;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Evaluates claims against the original image, without creating authority or citations. */
public final class VisualAssessmentService {
  public static final String POLICY_REVISION = "java-visual-assessment-v1";

  private final VisionModels models;

  public VisualAssessmentService(VisionModels models) {
    this.models = Objects.requireNonNull(models);
  }

  public VisualAssessment assess(String question, VisualImage image) {
    if (!validText(question, 8192) || image == null) {
      throw ModelValues.invalid();
    }
    try {
      ImageInput.validateEnvelope(
          image.mediaType().equals("image/png") ? "image.png" : "image.jpg",
          image.mediaType(),
          image.content());
    } catch (TextParser.Failure invalidImage) {
      throw ModelValues.invalid();
    }
    String sourceSha256 = image.sha256();
    String revision = ModelValues.identifier(models.revision(), 200);
    try {
      String invalidated = invalidationReason(revision);
      if (invalidated != null) {
        return refused(sourceSha256, revision, invalidated);
      }
      VisionModels.Draft draft = models.draft(question, image);
      invalidated = invalidationReason(revision);
      if (invalidated != null) {
        return refused(sourceSha256, revision, invalidated);
      }
      if (draft == null
          || (draft.refused() ? !draft.claims().isEmpty() : !validClaims(draft.claims()))) {
        return refused(sourceSha256, revision, "model_failure");
      }
      if (draft.refused()) {
        return refused(sourceSha256, revision, "model_refused");
      }
      VisionModels.Verification verification = models.verify(question, image, draft.claims());
      invalidated = invalidationReason(revision);
      if (invalidated != null) {
        return refused(sourceSha256, revision, invalidated);
      }
      if (verification == null || verification.supported().size() != draft.claims().size()) {
        return refused(sourceSha256, revision, "model_failure");
      }
      if (!verification.complete()) {
        return refused(sourceSha256, revision, "incomplete_evidence");
      }
      if (verification.supported().stream().anyMatch(supported -> !supported)) {
        return refused(sourceSha256, revision, "unsupported_claims");
      }
      return new VisualAssessment(draft.claims(), sourceSha256, revision, POLICY_REVISION, null);
    } catch (TextModels.Failure failure) {
      if ("model_interrupted".equals(failure.code())) {
        Thread.currentThread().interrupt();
      }
      return refused(
          sourceSha256,
          revision,
          Thread.currentThread().isInterrupted() ? "processing_interrupted" : "model_failure");
    } catch (CancellationException cancelled) {
      return refused(sourceSha256, revision, "processing_interrupted");
    }
  }

  private String invalidationReason(String revision) {
    if (Thread.currentThread().isInterrupted()) {
      return "processing_interrupted";
    }
    return revision.equals(models.revision()) ? null : "configuration_changed";
  }

  private static boolean validClaims(List<String> claims) {
    return !claims.isEmpty()
        && claims.size() <= 8
        && new HashSet<>(claims).size() == claims.size()
        && claims.stream().allMatch(claim -> validText(claim, 1024));
  }

  private static boolean validText(String text, int maximum) {
    return text != null
        && !text.isBlank()
        && text.codePointCount(0, text.length()) <= maximum
        && text.codePoints().noneMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF);
  }

  private static VisualAssessment refused(String sourceSha256, String revision, String reason) {
    return new VisualAssessment(List.of(), sourceSha256, revision, POLICY_REVISION, reason);
  }
}
