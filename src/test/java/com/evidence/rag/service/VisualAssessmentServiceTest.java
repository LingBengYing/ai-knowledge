package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VisualImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class VisualAssessmentServiceTest {
  private static final String QUESTION = "What color is the circle, and where is the square?";
  private static final List<String> CLAIMS =
      List.of("The circle is red.", "The square is to the right of the circle.");

  @Test
  void passesFullQuestionOriginalImageAndEveryClaimToIndependentVerification() throws Exception {
    var image = image();
    var models =
        new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));

    var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

    assertNull(assessment.refusalReason());
    assertEquals(CLAIMS, assessment.claims());
    assertEquals(image.sha256(), assessment.sourceSha256());
    assertEquals("synthetic-vision-v1", assessment.modelRevision());
    assertEquals("java-visual-assessment-v1", assessment.policyRevision());
    assertEquals(1, models.draftCalls);
    assertEquals(1, models.verifyCalls);
  }

  @Test
  void incompleteOrUnsupportedVerificationNeverReturnsPartialFacts() throws Exception {
    var image = image();
    for (boolean complete : List.of(false, true)) {
      var models =
          new ControlledModels(
              image,
              new VisionModels.Verification(complete, List.of(true, complete ? false : true)));

      var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

      assertEquals(
          complete ? "unsupported_claims" : "incomplete_evidence", assessment.refusalReason());
      assertTrue(assessment.claims().isEmpty());
      assertEquals(1, models.verifyCalls);
    }
  }

  @Test
  void revisionChangeDuringVerificationDiscardsEveryClaim() throws Exception {
    var image = image();
    var models =
        new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));
    models.changeRevision = true;

    var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

    assertEquals("configuration_changed", assessment.refusalReason());
    assertTrue(assessment.claims().isEmpty());
    assertEquals("synthetic-vision-v1", assessment.modelRevision());
    assertEquals(1, models.verifyCalls);
  }

  @Test
  void refusalStopsBeforeVerificationAndDoesNotReturnFacts() throws Exception {
    var image = image();
    var models =
        new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));
    models.draft = new VisionModels.Draft(true, List.of());

    var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

    assertEquals("model_refused", assessment.refusalReason());
    assertTrue(assessment.claims().isEmpty());
    assertEquals(0, models.verifyCalls);
  }

  @Test
  void missingVerificationEntryCannotPassEvenWhenEveryReturnedEntryIsSupported() throws Exception {
    var image = image();
    var models = new ControlledModels(image, new VisionModels.Verification(true, List.of(true)));

    var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

    assertEquals("model_failure", assessment.refusalReason());
    assertTrue(assessment.claims().isEmpty());
  }

  @Test
  void failedOrCancelledVerificationDoesNotExposeDraftFacts() throws Exception {
    var image = image();
    for (RuntimeException failure :
        List.of(
            new TextModels.Failure("model_timeout"), new CancellationException("private data"))) {
      var models =
          new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));
      models.verifyFailure = failure;

      var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

      assertEquals(
          failure instanceof CancellationException ? "processing_interrupted" : "model_failure",
          assessment.refusalReason());
      assertTrue(assessment.claims().isEmpty());
      assertEquals("VisualAssessment[redacted]", assessment.toString());
    }
  }

  @Test
  void interruptedCallerMakesNoModelRequestAndKeepsInterruptFlag() throws Exception {
    var image = image();
    var models =
        new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));
    try {
      Thread.currentThread().interrupt();

      var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

      assertEquals("processing_interrupted", assessment.refusalReason());
      assertTrue(assessment.claims().isEmpty());
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, models.draftCalls);
      assertEquals(0, models.verifyCalls);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void revisionChangeAfterDraftStopsBeforeVerification() throws Exception {
    var image = image();
    var models =
        new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));
    models.changeRevisionAfterDraft = true;

    var assessment = new VisualAssessmentService(models).assess(QUESTION, image);

    assertEquals("configuration_changed", assessment.refusalReason());
    assertTrue(assessment.claims().isEmpty());
    assertEquals(0, models.verifyCalls);
  }

  @Test
  void invalidImageMetadataOrQuestionIsRejectedBeforeAnyModelRequest() throws Exception {
    var image = image();
    var models =
        new ControlledModels(image, new VisionModels.Verification(true, List.of(true, true)));
    var service = new VisualAssessmentService(models);

    assertThrows(ApplicationException.class, () -> service.assess(" ", image));
    assertThrows(ApplicationException.class, () -> service.assess("x".repeat(8193), image));
    assertThrows(ApplicationException.class, () -> service.assess("broken\uD800", image));
    assertThrows(
        ApplicationException.class,
        () -> service.assess(QUESTION, new VisualImage("image/jpeg", image.content())));
    assertEquals(0, models.draftCalls);
    assertEquals(0, models.verifyCalls);
  }

  private static VisualImage image() throws Exception {
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", output));
    return new VisualImage("image/png", output.toByteArray());
  }

  private static final class ControlledModels implements VisionModels {
    private final VisualImage image;
    private final Verification verification;
    private Draft draft = new Draft(false, CLAIMS);
    private String revision = "synthetic-vision-v1";
    private boolean changeRevision;
    private boolean changeRevisionAfterDraft;
    private RuntimeException verifyFailure;
    private int draftCalls;
    private int verifyCalls;

    private ControlledModels(VisualImage image, Verification verification) {
      this.image = image;
      this.verification = verification;
    }

    @Override
    public Description describe(VisualImage ignored) {
      throw new AssertionError("Recall description must not be used to assess image facts.");
    }

    @Override
    public Draft draft(String question, VisualImage original) {
      assertEquals(QUESTION, question);
      assertSame(image, original);
      draftCalls++;
      if (changeRevisionAfterDraft) {
        revision = "synthetic-vision-v2";
      }
      return draft;
    }

    @Override
    public Verification verify(String question, VisualImage original, List<String> claims) {
      assertEquals(QUESTION, question);
      assertSame(image, original);
      assertEquals(CLAIMS, claims);
      verifyCalls++;
      if (verifyFailure != null) {
        throw verifyFailure;
      }
      if (changeRevision) {
        revision = "synthetic-vision-v2";
      }
      return verification;
    }

    @Override
    public String revision() {
      return revision;
    }
  }
}
