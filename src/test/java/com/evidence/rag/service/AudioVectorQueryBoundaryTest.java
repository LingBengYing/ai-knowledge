package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.AudioEmbeddingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AudioVectorQueryBoundaryTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "pre-dispatch {0} drift")
  @ValueSource(strings = {"revision", "decoder", "dimensions", "projection", "unavailable"})
  void currentProfileMustMatchBeforeTheFirstEmbedding(String changed) {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new Metadata();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      switch (changed) {
        case "revision" -> models.revision = "changed-profile";
        case "decoder" -> models.decoder = "changed-decoder";
        case "dimensions" -> models.dimensions = 3;
        case "projection" -> projection.identity = "f".repeat(64);
        case "unavailable" -> models.unavailable = true;
        default -> throw new AssertionError(changed);
      }
      assertEquals(
          "configuration_changed",
          assertThrows(
                  ApplicationException.class,
                  () -> service.searchAudio(query, authorized(), () -> {}, ids -> ids))
              .code());
      assertEquals(0, models.calls);
      assertEquals(0, projection.preparations);
      assertTrue(projection.queries.isEmpty());
      assertTrue(context.models.calls.isEmpty());
      assertTrue(
          service.configurationCurrent(),
          "An unused original-audio profile does not gate old text/image operations");
    }
  }

  @ParameterizedTest(name = "complete provider candidate batch: {0}")
  @ValueSource(strings = {"missing-batch", "overfull-batch", "duplicate-tail"})
  void malformedCandidateTailStopsEveryLaterWaveformAndAuthorityMapping(String defect) {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new Metadata();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      projection.response =
          ignored -> {
            if (defect.equals("missing-batch")) {
              return null;
            }
            var result =
                new ArrayList<>(
                    IntStream.range(0, defect.equals("overfull-batch") ? 65 : 64)
                        .mapToObj(i -> new RetrievalProjection.Candidate("vector-" + i, 1.0))
                        .toList());
            if (defect.equals("duplicate-tail")) {
              result.set(63, result.getFirst());
            }
            return result;
          };
      var mappings = new AtomicInteger();
      assertThrows(
          TextModels.Failure.class,
          () ->
              service.searchAudio(
                  query,
                  authorized(),
                  () -> {},
                  ids -> {
                    mappings.incrementAndGet();
                    return ids;
                  }));
      assertEquals(1, models.calls);
      assertEquals(1, projection.queries.size());
      assertEquals(0, mappings.get());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @ParameterizedTest(name = "authority mapping tail: {0}")
  @ValueSource(strings = {"missing-mapping", "null-tail", "blank-tail"})
  void aValidFirstCandidateCannotHideAnInvalidAuthorityTail(String defect) {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new Metadata();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      var mapped = new AtomicInteger();
      assertThrows(
          TextModels.Failure.class,
          () ->
              service.searchAudio(
                  query,
                  authorized(),
                  () -> {},
                  ids -> {
                    assertEquals(64, ids.size());
                    mapped.incrementAndGet();
                    if (defect.equals("missing-mapping")) {
                      return null;
                    }
                    var bases = new ArrayList<>(ids);
                    bases.set(63, defect.equals("null-tail") ? null : " ");
                    return bases;
                  }));
      assertEquals(1, mapped.get());
      assertEquals(1, models.calls);
      assertEquals(1, projection.queries.size());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void aPreparedWaveformFromAnotherDecoderIsNeverSentToTheEmbeddingModel() {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new Metadata();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      var foreign =
          new PreparedQuery(
              query.originalQuestion(),
              query.retrievalText(),
              query.queryImages(),
              query.attachments(),
              query.preparationRevision(),
              query.queryAudio().stream()
                  .map(
                      w ->
                          new AudioWaveform(
                              w.sourceSha256(),
                              "other-decoder",
                              w.startSample(),
                              w.endSample(),
                              w.pcm()))
                  .toList());
      assertEquals(
          "configuration_changed",
          assertThrows(
                  ApplicationException.class,
                  () -> service.searchAudio(foreign, authorized(), () -> {}, ids -> ids))
              .code());
      assertEquals(0, models.calls);
      assertTrue(projection.queries.isEmpty());
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, AudioVectorQueryFixture.BUDGET, 1);
  }

  private static RetrievalProjection.AuthorizedScope authorized() {
    return new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
  }

  private static final class Metadata implements AudioEmbeddingModels {
    String revision = AudioVectorQueryFixture.TARGET.modelRevision();
    String decoder = AudioVectorQueryFixture.DECODER;
    int dimensions = 2;
    int calls;
    boolean unavailable;

    public String revision() {
      if (unavailable) {
        throw new TextModels.Failure("model_unavailable");
      }
      return revision;
    }

    public int dimensions() {
      return dimensions;
    }

    public String decoderRevision() {
      return decoder;
    }

    public List<Double> embed(byte[] canonicalWav) {
      calls++;
      return List.of(1.0, 0.0);
    }
  }
}
