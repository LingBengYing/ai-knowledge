package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ModelConfigurationInputException;
import com.evidence.rag.model.dto.SaveModelConfigurationCommand;
import org.junit.jupiter.api.Test;

class TextModelConfigurationTest {
  @Test
  void omittedKeysKeepEachRoleSecretWithoutMutatingTheActiveSnapshot() {
    var old = configured("old-synthetic-token");
    var command =
        new SaveModelConfigurationCommand(
            1,
            new SaveModelConfigurationCommand.EmbeddingInput("embed", 2, "pinned-v1", null),
            new SaveModelConfigurationCommand.RoleInput("rank", "new-synthetic-token"),
            new SaveModelConfigurationCommand.RoleInput("generate", null));
    var changed = command.resolve(old);
    assertEquals("old-synthetic-token", changed.embedding().apiKey());
    assertEquals("new-synthetic-token", changed.rerank().apiKey());
    assertEquals("old-synthetic-token", old.rerank().apiKey());
    assertFalse(changed.toString().contains("synthetic-token"));
    assertFalse(command.toString().contains("synthetic-token"));
  }

  @Test
  void firstSaveRequiresAllKeysAndRejectsUnpinnedRevision() {
    var command =
        new SaveModelConfigurationCommand(
            0,
            new SaveModelConfigurationCommand.EmbeddingInput("embed", 2, "pinned-v1", null),
            new SaveModelConfigurationCommand.RoleInput("rank", null),
            new SaveModelConfigurationCommand.RoleInput("generate", null));
    assertEquals(
        "embedding.api_key",
        assertThrows(ModelConfigurationInputException.class, () -> command.resolve(null)).field());
    assertThrows(
        ModelConfigurationInputException.class,
        () -> new TextModelConfiguration.Embedding("embed", "synthetic-token", 2, "latest"));
  }

  private static TextModelConfiguration configured(String key) {
    return new TextModelConfiguration(
        new TextModelConfiguration.Embedding("embed", key, 2, "pinned-v1"),
        new TextModelConfiguration.Role("rank", key),
        new TextModelConfiguration.Role("generate", key));
  }
}
