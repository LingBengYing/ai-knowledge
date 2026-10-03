package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextIndexAnchorTest {
  @Test
  void anchorHasNoCredentialsAndLegacyStateKeepsItsConstructor() {
    var original = configuration("generation-one", "rerank-one", "original-synthetic-key");
    var anchor = anchor(1, original, 2);
    assertEquals("https://api.siliconflow.cn/v1", anchor.providerBaseUrl());
    assertEquals("generation-one", anchor.generationModel());
    assertEquals("rerank-one", anchor.rerankModel());
    assertFalse(anchor.toString().contains("generation-one"));
    assertFalse(anchor.toString().contains("siliconflow"));
    assertNull(new ModelConfigurationState(1, original, 1L, original).indexAnchor());
    assertNull(ModelConfigurationState.empty().indexAnchor());
  }

  @Test
  void stateKeepsOriginalAnchorAcrossRoleAndCredentialChanges() {
    var original = configuration("generation-one", "rerank-one", "original-synthetic-key");
    var changed = configuration("generation-two", "rerank-two", "rotated-synthetic-key");
    var anchor = anchor(1, original, 2);
    var active = new ModelConfigurationState(3, changed, 3L, changed, anchor);
    assertEquals(anchor, active.indexAnchor());
    assertEquals("generation-two", active.active().generation().model());
    var incompatibleDraft =
        new TextModelConfiguration(
            new TextModelConfiguration.Embedding(
                "different-embedding", "draft-synthetic-key", 3, "v2"),
            changed.rerank(),
            changed.generation());
    assertEquals(
        anchor,
        new ModelConfigurationState(4, incompatibleDraft, 3L, changed, anchor).indexAnchor());
  }

  @Test
  void anchorCannotAttachToMissingActiveOrLaterOriginOrDifferentEmbedding() {
    var roles = configuration("generation-one", "rerank-one", "synthetic-key");
    var anchor = anchor(1, roles, 2);
    assertThrows(
        ApplicationException.class,
        () -> new ModelConfigurationState(1, roles, null, null, anchor));
    assertThrows(
        ApplicationException.class,
        () -> new ModelConfigurationState(1, roles, 1L, roles, anchor(2, roles, 2)));
    var different =
        new TextModelConfiguration(
            new TextModelConfiguration.Embedding("another-embedding", "synthetic-key", 2, "v1"),
            roles.rerank(),
            roles.generation());
    assertThrows(
        ApplicationException.class,
        () -> new ModelConfigurationState(2, different, 2L, different, anchor));
    var revised =
        new TextModelConfiguration(
            new TextModelConfiguration.Embedding("embedding-one", "synthetic-key", 2, "v2"),
            roles.rerank(),
            roles.generation());
    assertThrows(
        ApplicationException.class,
        () -> new ModelConfigurationState(2, revised, 2L, revised, anchor));
    var resized =
        new TextModelConfiguration(
            new TextModelConfiguration.Embedding("embedding-one", "synthetic-key", 3, "v1"),
            roles.rerank(),
            roles.generation());
    assertThrows(
        ApplicationException.class,
        () -> new ModelConfigurationState(2, resized, 2L, resized, anchor));
  }

  @Test
  void anchorRequiresOriginalVersionAndDimensionConsistentTarget() {
    var roles = configuration("generation-one", "rerank-one", "synthetic-key");
    assertThrows(ApplicationException.class, () -> anchor(0, roles, 2));
    assertThrows(
        ApplicationException.class,
        () -> anchor(ModelConfigurationState.MAX_VERSION + 1, roles, 2));
    assertThrows(ApplicationException.class, () -> anchor(1, roles, 3));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://secret@api.siliconflow.cn/v1",
        "https://api.siliconflow.cn/v1?api_key=private",
        "https://api.siliconflow.cn/v1#private",
        "/v1",
        "file:///private/provider",
        "https:///v1"
      })
  void providerBasisRejectsCredentialsAndNonEndpointUris(String provider) {
    assertThrows(
        ApplicationException.class,
        () ->
            new TextIndexAnchor(
                1,
                provider,
                "embedding-one",
                "v1",
                2,
                "rerank-one",
                "generation-one",
                new IndexTarget("embedding-v1", "projection-v1", "full-v1", 2)));
  }

  private static TextModelConfiguration configuration(
      String generation, String rerank, String key) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding("embedding-one", key, 2, "v1"),
        new TextModelConfiguration.Role(rerank, key),
        new TextModelConfiguration.Role(generation, key));
  }

  private static TextIndexAnchor anchor(
      long version, TextModelConfiguration roles, int targetDimensions) {
    return new TextIndexAnchor(
        version,
        "https://api.siliconflow.cn/v1",
        roles.embedding().model(),
        roles.embedding().revision(),
        roles.embedding().dimensions(),
        roles.rerank().model(),
        roles.generation().model(),
        new IndexTarget("embedding-v1", "projection-v1", "full-v1", targetDimensions));
  }
}
