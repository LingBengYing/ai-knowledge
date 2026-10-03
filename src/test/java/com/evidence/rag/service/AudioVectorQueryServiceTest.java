package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioVectorQueryServiceTest {
  @TempDir Path directory;

  @Test
  void everyActualWaveformUsesDenseScopeAndAllRouteCandidatesMapBeforeTruncation() {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      var queries = fixture.queries(context, models, projection);
      var prepared =
          queries.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      var scope =
          new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "audio-generation"));
      var batches = new ArrayList<List<String>>();
      projection.response =
          ignored ->
              IntStream.range(0, 64)
                  .mapToObj(
                      index ->
                          new RetrievalProjection.Candidate(
                              index == 0
                                  ? "shared"
                                  : "route-" + projection.queries.size() + "-" + index,
                              1.0 - index / 100.0))
                  .toList();
      var found =
          queries.searchAudio(
              prepared,
              scope,
              () -> {},
              ids -> {
                batches.add(ids);
                return ids.stream().map(id -> "base-" + id).toList();
              });
      assertEquals(3, batches.size());
      assertTrue(batches.stream().allMatch(ids -> ids.size() == 64));
      assertTrue(batches.getLast().contains("route-3-63"));
      assertEquals(64, found.size());
      assertEquals("base-shared", found.getFirst().segmentId());
      assertEquals(3.0 / 61, found.getFirst().score(), 1e-15);
      assertEquals(3, models.received.size());
      for (int i = 0; i < 3; i++) {
        assertArrayEquals(prepared.queryAudio().get(i).wav(), models.received.get(i));
        assertEquals(scope, projection.queries.get(i).scope());
        assertEquals(RetrievalProjection.SearchMode.DENSE_ONLY, projection.queries.get(i).mode());
        assertEquals(prepared.queryAudio().get(i).pcmSha256(), projection.queries.get(i).text());
      }
      assertTrue(context.models.calls.isEmpty(), "Original PCM recall never calls text embedding");
    }
  }

  @Test
  void emptyScopeDoesNoModelWorkAndInvalidTailMappingNeverReleasesAPrefix() {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      assertTrue(
          service
              .searchAudio(
                  query,
                  new RetrievalProjection.AuthorizedScope("org-main", Map.of()),
                  () -> {},
                  ids -> ids)
              .isEmpty());
      assertTrue(models.received.isEmpty());
      assertEquals(0, projection.preparations);
      var scope = new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
      assertThrows(
          TextModels.Failure.class,
          () -> service.searchAudio(query, scope, () -> {}, ids -> ids.subList(0, 63)));
      assertEquals(1, models.received.size());
      assertThrows(
          TextModels.Failure.class,
          () ->
              service.searchAudio(
                  query, scope, () -> {}, ids -> Collections.nCopies(64, "same-base")));
      assertEquals(2, models.received.size());
      var mapped = new AtomicInteger();
      projection.response =
          ignored -> {
            var result = new ArrayList<RetrievalProjection.Candidate>();
            result.add(new RetrievalProjection.Candidate("ok", 1.0));
            result.add(null);
            return result;
          };
      assertThrows(
          TextModels.Failure.class,
          () ->
              service.searchAudio(
                  query,
                  scope,
                  () -> {},
                  ids -> {
                    mapped.incrementAndGet();
                    return ids;
                  }));
      assertEquals(0, mapped.get());
    }
  }

  @Test
  void changedDecoderProfileAfterEmbeddingStopsProjectionAndDoesNotGateOldTextRoute() {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      models.after = () -> models.decoder = "changed-decoder";
      assertEquals(
          "configuration_changed",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      service.searchAudio(
                          query,
                          new RetrievalProjection.AuthorizedScope(
                              "org-main", Map.of("doc", "generation")),
                          () -> {},
                          ids -> ids))
              .code());
      assertEquals(1, models.received.size());
      assertTrue(projection.queries.isEmpty());
      assertTrue(service.configurationCurrent());
    }
  }

  @Test
  void malformedProviderVectorsCannotReachProjectionOrAuthorityMapping() {
    try (var context = context()) {
      var fixture = new AudioVectorQueryFixture();
      var models = new AudioVectorQueryFixture.Embeddings();
      var projection = new AudioVectorQueryFixture.Projection();
      var service = fixture.queries(context, models, projection);
      var query =
          service.prepare(
              AudioVectorQueryFixture.QUESTION, List.of(fixture.attachment()), () -> {});
      var scope = new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
      models.result = null;
      assertThrows(
          TextModels.Failure.class, () -> service.searchAudio(query, scope, () -> {}, ids -> ids));
      models.result = List.of(1.0);
      assertThrows(
          TextModels.Failure.class, () -> service.searchAudio(query, scope, () -> {}, ids -> ids));
      models.result = List.of(Double.NaN, 0.0);
      assertThrows(
          ProjectionException.class, () -> service.searchAudio(query, scope, () -> {}, ids -> ids));
      assertTrue(projection.queries.isEmpty());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, AudioVectorQueryFixture.BUDGET, 1);
  }
}
