package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.ImageVectorState;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.VectorBindingIdentity;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ImageVectorResponseMapperTest {
  private final IndexTarget target =
      new IndexTarget(
          "java-image-embedding-v1:" + "b".repeat(64),
          "c".repeat(64),
          "java-image-embedding-v1:" + "b".repeat(64),
          2);
  private final PublicationVersion base =
      new PublicationVersion(
          "doc",
          "pub",
          "rev",
          "generation",
          "a".repeat(64),
          "visual-parser-v1",
          new IndexTarget("text", "text-projection", "text-v1", 2),
          "d".repeat(64),
          1);
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void missingReceiptUsesExactlyTenSafeIdentityFieldsAndNullReceiptValues() {
    var response = ImageVectorResponseMapper.response(new ImageVectorState(base, target, null));
    var node = json.valueToTree(response);
    assertEquals(
        Set.of(
            "status",
            "document_id",
            "publication_id",
            "source_revision_id",
            "source_sha256",
            "profile_fingerprint",
            "model_revision",
            "dimensions",
            "vector_generation_id",
            "manifest_sha256"),
        new HashSet<>(node.propertyNames()));
    assertEquals("missing", node.path("status").asString());
    assertEquals("doc", node.path("document_id").asString());
    assertEquals("pub", node.path("publication_id").asString());
    assertEquals("rev", node.path("source_revision_id").asString());
    assertEquals(base.sourceSha256(), node.path("source_sha256").asString());
    assertEquals(target.projectionIdentity(), node.path("profile_fingerprint").asString());
    assertEquals(target.modelRevision(), node.path("model_revision").asString());
    assertEquals(2, node.path("dimensions").asInt());
    assertTrue(node.path("vector_generation_id").isNull());
    assertTrue(node.path("manifest_sha256").isNull());
    assertFalse(response.toString().contains(base.sourceSha256()));
  }

  @Test
  void availableReceiptExposesItsIndependentGenerationAndVerifiedManifest() {
    String generation = "00000000-0000-0000-0000-000000000001";
    var receipt =
        new ImageVectorPublication(
            "receipt",
            base,
            "image-evidence",
            VectorBindingIdentity.physicalSegmentId(
                base.projectionGenerationId(), "image-evidence"),
            generation,
            VectorBindingIdentity.physicalSegmentId(generation, "image-evidence"),
            target,
            "e".repeat(64),
            "f".repeat(64),
            "2026-10-03T00:00:00Z");
    var node =
        json.valueToTree(
            ImageVectorResponseMapper.response(new ImageVectorState(base, target, receipt)));
    assertEquals("available", node.path("status").asString());
    assertEquals(receipt.vectorGenerationId(), node.path("vector_generation_id").asString());
    assertEquals(receipt.manifestSha256(), node.path("manifest_sha256").asString());
    assertEquals(base.publicationId(), node.path("publication_id").asString());
  }
}
