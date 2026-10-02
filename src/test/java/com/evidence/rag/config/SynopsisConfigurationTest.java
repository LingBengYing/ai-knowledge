package com.evidence.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.HierarchicalSynopsisModels;
import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisInput;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class SynopsisConfigurationTest {
  @Test
  void disabledSynopsisRequiresNoModelAuthorityOrAnswerConfiguration() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(new MockEnvironment());
      context.register(SynopsisConfiguration.class);
      context.refresh();
      assertTrue(context.getBeansOfType(SynopsisModels.class).isEmpty());
      assertTrue(context.getBeansOfType(HierarchicalSynopsisModels.class).isEmpty());
    }
  }

  @Test
  void independentModelCanBeConstructedWithoutAnswerOrVectorSettingsAndCloses() {
    var configuration = new SynopsisConfiguration();
    var models = configuration.synopsisModels(local());
    assertTrue(models.revision().startsWith("java-synopsis-models-v1-"));
    configuration.synopsisService(models, local());
    models.close();
    var input =
        new SynopsisInput(
            new PublicationVersion(
                "doc",
                "pub",
                "rev",
                "generation",
                "a".repeat(64),
                "parser-v1",
                new IndexTarget("embedding", "projection", "model-v1", 2),
                "b".repeat(64),
                1),
            List.of(
                new SynopsisEvidence(
                    "e1",
                    SynopsisEvidence.Kind.TEXT,
                    new SynopsisEvidence.Text("Synthetic original."),
                    null)));
    assertEquals(
        "model_closed", assertThrows(TextModels.Failure.class, () -> models.draft(input)).code());
  }

  @Test
  void rejectsNonLocalAndInvalidConfigurationWithoutLeakingCredentials() {
    for (String[] pair :
        new String[][] {
          {"server.address", "localhost"}, {"rag.environment", "production"},
          {"rag.synopsis.api-key", ""}, {"rag.synopsis.deadline-ms", "0"},
          {"rag.synopsis.max-response-bytes", "1"}, {"rag.synopsis.base-url", "malformed endpoint"}
        }) {
      var environment = local().withProperty(pair[0], pair[1]);
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> new SynopsisConfiguration().synopsisModels(environment));
      assertEquals("Invalid local synopsis configuration", failure.getMessage());
      assertNull(failure.getCause());
      var hierarchyFailure =
          assertThrows(
              IllegalArgumentException.class,
              () -> new SynopsisConfiguration().hierarchicalSynopsisModels(environment));
      assertEquals("Invalid local synopsis configuration", hierarchyFailure.getMessage());
      assertNull(hierarchyFailure.getCause());
    }
  }

  @Test
  void independentHierarchyUsesSynopsisEndpointWithItsOwnRevisionAndLifecycle() {
    var configuration = new SynopsisConfiguration();
    try (var shortModels = configuration.synopsisModels(local());
        var models = configuration.hierarchicalSynopsisModels(local())) {
      assertTrue(models.revision().startsWith("java-hierarchical-synopsis-v1-"));
      assertNotEquals(shortModels.revision(), models.revision());
      configuration.hierarchicalSynopsisService(models, local());
      models.close();
      var file =
          new SynopsisFileInput(
              new PublicationVersion(
                  "doc",
                  "pub",
                  "rev",
                  "generation",
                  "a".repeat(64),
                  "parser-v1",
                  new IndexTarget("embedding", "projection", "model-v1", 2),
                  "b".repeat(64),
                  1),
              List.of(
                  new SynopsisEvidence(
                      "e1",
                      SynopsisEvidence.Kind.TEXT,
                      new SynopsisEvidence.Text("Synthetic original."),
                      null)));
      assertEquals(
          "model_closed",
          assertThrows(
                  TextModels.Failure.class,
                  () -> models.draftLeaf(SynopsisBatch.partition(file).getFirst()))
              .code());
    }
  }

  private static MockEnvironment local() {
    return new MockEnvironment()
        .withProperty("server.address", "127.0.0.1")
        .withProperty("rag.environment", "test")
        .withProperty("rag.synopsis.base-url", "http://127.0.0.1:1/v1")
        .withProperty("rag.synopsis.model", "synthetic-synopsis")
        .withProperty("rag.synopsis.api-key", "synthetic-model-credential")
        .withProperty("rag.synopsis.allow-loopback-http", "true");
  }
}
