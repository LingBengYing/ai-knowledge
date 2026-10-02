package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.VisualImage;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Explicit -Dtest=VisionModelsLiveIT test. At most four requests, one synthetic no-text image,
 * sixty seconds each, no retry. Not a corpus/Milvus/HTTP authorization or general accuracy test.
 */
class VisionModelsLiveIT {
  private static final String QUESTION =
      "Name both shapes, their colors and their left-to-right order. Answer in English.";

  @Test
  void syntheticOriginalSupportsBothFactsAndRejectsAKnownWrongVisualClaim() throws Exception {
    assertEquals(
        "4",
        required("RAG_VISION_IT_APPROVED_CALLS"),
        "This opt-in run needs a distinct explicit four-request grant, not a text budget");
    var configuration =
        new OpenAiCompatibleVisionModels.Configuration(
            new Endpoint(
                URI.create("https://api.siliconflow.cn/v1"),
                required("RAG_VISION_IT_MODEL"),
                required("RAG_VISION_IT_API_KEY")),
            Duration.ofSeconds(60),
            1_048_576,
            false);
    var image = VisualSyntheticFixture.image("png");
    try (var adapter = new OpenAiCompatibleVisionModels(configuration)) {
      var counted = new CountedModels(adapter);
      var description = counted.describe(image);
      assertFalse(description.recallText().isBlank(), "Synthetic recall description is present");
      var assessment = new VisualAssessmentService(counted).assess(QUESTION, image);
      assertNull(
          assessment.refusalReason(), "Both synthetic facts need image-supported assessment");
      assertEquals(image.sha256(), assessment.sourceSha256());
      String facts = String.join(" ", assessment.claims()).toLowerCase(Locale.ROOT);
      assertTrue(
          facts.contains("blue")
              && facts.contains("circle")
              && facts.contains("red")
              && facts.contains("square"),
          "The bounded image fixture must cover both colors and both shapes");
      // A directly supplied false fact must not be rescued by a plausible caption.
      var wrong = counted.verify(QUESTION, image, List.of("The image contains a green triangle."));
      assertEquals(
          List.of(false), wrong.supported(), "Known wrong visual fact must be unsupported");
      assertEquals(
          4, counted.calls, "Exactly four explicit calls; no discovery, embedding or rerank");
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    assertTrue(value != null && !value.isBlank(), "Explicit " + name + " is required");
    return value;
  }

  private static final class CountedModels implements VisionModels {
    private final VisionModels delegate;
    private int calls;

    private CountedModels(VisionModels delegate) {
      this.delegate = delegate;
    }

    private <T> T call(String phase, Supplier<T> request) {
      assertTrue(calls < 4, "Stop before exceeding the distinct visual-call grant");
      int number = ++calls;
      long started = System.nanoTime();
      System.out.printf("vision_it request=%d phase=%s started%n", number, phase);
      try {
        return request.get();
      } catch (TextModels.Failure safe) {
        throw new AssertionError("Visual provider request failed: " + safe.code());
      } finally {
        System.out.printf(
            "vision_it request=%d elapsed_ms=%d%n",
            number, (System.nanoTime() - started) / 1_000_000);
      }
    }

    @Override
    public Description describe(VisualImage image) {
      return call("describe", () -> delegate.describe(image));
    }

    @Override
    public Draft draft(String question, VisualImage image) {
      return call("draft", () -> delegate.draft(question, image));
    }

    @Override
    public Verification verify(String question, VisualImage image, List<String> claims) {
      return call("verify", () -> delegate.verify(question, image, claims));
    }

    @Override
    public String revision() {
      return delegate.revision();
    }
  }
}
