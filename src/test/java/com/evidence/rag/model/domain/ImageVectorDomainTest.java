package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageVectorDomainTest {
  private static final IndexTarget TARGET =
      new IndexTarget("image-v1", "projection-v1", "model-v1", 2);
  private static final VisualImage IMAGE = new VisualImage("image/png", new byte[] {1, 2});
  private static final PublicationVersion BASE =
      new PublicationVersion(
          "doc", "pub", "rev", "base", IMAGE.sha256(), "parser-v1", TARGET, "a".repeat(64), 1);

  @Test
  void claimBindsTheCompleteOriginalAndUsesANewCanonicalGeneration() {
    var claim =
        new ImageVectorBuildClaim(
            new Actor("org", "owner"),
            BASE,
            "image",
            "physical",
            TARGET,
            UUID.randomUUID().toString(),
            IMAGE);
    assertEquals(IMAGE.sha256(), claim.original().sha256());
    assertTrue(claim.toString().contains("redacted"));
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBuildClaim(
                claim.actor(), BASE, "image", "physical", TARGET, "not-a-uuid", IMAGE));
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBuildClaim(
                claim.actor(),
                BASE,
                "image",
                "physical",
                TARGET,
                UUID.randomUUID().toString(),
                new VisualImage("image/png", new byte[] {3})));
  }

  @Test
  void receiptIsDefensiveAndRejectsUnusableFloatVectors() {
    var vector = new ArrayList<>(List.of(0.25, 0.75));
    var receipt =
        new ImageVectorReceipt(
            "physical",
            vector,
            "a".repeat(64),
            new VerifiedRevision("c".repeat(64), "b".repeat(64), 1));
    vector.clear();
    assertEquals(List.of(0.25, 0.75), receipt.vector());
    assertThrows(UnsupportedOperationException.class, () -> receipt.vector().clear());
    for (var bad :
        List.of(List.of(0.0, 0.0), List.of(Double.NaN, 1.0), List.of(Double.MAX_VALUE, 1.0))) {
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorReceipt("physical", bad, "a".repeat(64), receipt.verified()));
    }
    assertTrue(receipt.toString().contains("redacted"));
  }

  @Test
  void missingStateHasNoInventedReceipt() {
    var state = new ImageVectorState(BASE, TARGET, null);
    assertEquals(BASE, state.basePublication());
    assertEquals(TARGET, state.target());
    assertThrows(ApplicationException.class, () -> new ImageVectorState(null, TARGET, null));
  }

  @Test
  void receiptRejectsMalformedCardinalityMissingCoordinateAndDigest() {
    var verified = new VerifiedRevision("c".repeat(64), "b".repeat(64), 1);
    for (var vector :
        List.of(List.of(0.5), Collections.nCopies(8193, 0.5), Arrays.asList(0.25, null))) {
      assertThrows(
          ApplicationException.class,
          () -> new ImageVectorReceipt("physical", vector, "a".repeat(64), verified));
    }
    assertThrows(
        ApplicationException.class,
        () -> new ImageVectorReceipt("physical", List.of(0.25, 0.75), "A".repeat(64), verified));
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorReceipt(
                "physical",
                List.of(0.25, 0.75),
                "a".repeat(64),
                new VerifiedRevision(verified.projectionIdentity(), verified.manifestSha256(), 2)));
  }

  @Test
  void publicationRejectsCorruptedHashesAndNoncanonicalGeneration() {
    String generation = "abcdefab-1234-5678-90ab-abcdefabcdef";
    var publication = publication(BASE, TARGET, generation, "a".repeat(64), "b".repeat(64));
    assertTrue(publication.toString().contains("redacted"));
    assertThrows(
        ApplicationException.class,
        () -> publication(BASE, TARGET, generation, "z".repeat(64), "b".repeat(64)));
    assertThrows(
        ApplicationException.class,
        () -> publication(BASE, TARGET, generation, "a".repeat(64), "b".repeat(63)));
    assertThrows(
        ApplicationException.class,
        () ->
            publication(
                BASE, TARGET, generation.toUpperCase(Locale.ROOT), "a".repeat(64), "b".repeat(64)));
    assertThrows(
        ApplicationException.class,
        () ->
            new ImageVectorBuildClaim(
                new Actor("org", "owner"),
                BASE,
                "image",
                "physical",
                TARGET,
                generation.toUpperCase(Locale.ROOT),
                IMAGE));
  }

  @Test
  void availableStateCannotAttachAReceiptFromAnotherBaseOrProfile() {
    String generation = UUID.randomUUID().toString();
    var publication = publication(BASE, TARGET, generation, "a".repeat(64), "b".repeat(64));
    assertEquals(publication, new ImageVectorState(BASE, TARGET, publication).publication());
    var changedBase =
        new PublicationVersion(
            "other-doc",
            "other-pub",
            BASE.sourceRevisionId(),
            BASE.projectionGenerationId(),
            BASE.sourceSha256(),
            BASE.parserRevision(),
            TARGET,
            BASE.manifestSha256(),
            1);
    assertThrows(
        ApplicationException.class, () -> new ImageVectorState(changedBase, TARGET, publication));
    var changedTarget = new IndexTarget("image-v2", "other-projection", "model-v2", 2);
    assertThrows(
        ApplicationException.class, () -> new ImageVectorState(BASE, changedTarget, publication));
  }

  private static ImageVectorPublication publication(
      PublicationVersion base,
      IndexTarget target,
      String generation,
      String entry,
      String manifest) {
    return new ImageVectorPublication(
        "receipt",
        base,
        "image",
        "base-physical",
        generation,
        "vector-physical",
        target,
        entry,
        manifest,
        "2026-10-03T00:00:00Z");
  }
}
