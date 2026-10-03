package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.ImageEmbeddingModels;
import com.evidence.rag.client.model.QueryRankingModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.QueryRankCandidate;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageVectorQueryServiceTest {
  @TempDir Path directory;

  @Test
  void originalBytesUseDenseProjectionAndEveryVectorIdIsMappedBeforeFusion() {
    try (var context = context()) {
      var images = new Images();
      var projection = new Projection();
      var service = queries(context, images, projection);
      var query = QueryAttachmentAnswerFixture.prepared("Which shape?");
      var authorized =
          new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "vector-generation"));
      var mapped = new ArrayList<String>();
      var found =
          service.searchImages(
              query,
              authorized,
              () -> {},
              ids -> {
                mapped.addAll(ids);
                return ids.stream().map(id -> "base-" + id).toList();
              });
      assertEquals(query.queryImages(), images.received);
      assertEquals(authorized, projection.query.scope());
      assertEquals(RetrievalProjection.SearchMode.DENSE_ONLY, projection.query.mode());
      assertEquals(query.queryImages().getFirst().sha256(), projection.query.text());
      assertEquals(64, mapped.size());
      assertEquals(64, found.size());
      assertEquals("base-vector-0", found.getFirst().segmentId());
      assertEquals(1.0 / 61, found.getFirst().score());
      assertEquals(1.0 / 124, found.getLast().score());
      assertTrue(
          context.models.calls.isEmpty(), "No caption/text embedding in original-image recall");
    }
  }

  @Test
  void invalidTailMappingAndChangedProfileNeverReleasePartialCandidates() {
    try (var context = context()) {
      var images = new Images();
      var projection = new Projection();
      var service = queries(context, images, projection);
      var query = QueryAttachmentAnswerFixture.prepared("Which shape?");
      var authorized =
          new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
      assertThrows(
          TextModels.Failure.class,
          () -> service.searchImages(query, authorized, () -> {}, ids -> ids.subList(0, 63)));
      images.revision = "image-model-v2";
      assertThrows(
          ApplicationException.class,
          () -> service.searchImages(query, authorized, () -> {}, ids -> ids));
      assertEquals(1, images.received.size());
      assertTrue(
          service.configurationCurrent(), "Unused image profile does not change old text route");
    }
  }

  @Test
  void noAuthorizedVectorGenerationPerformsNoEmbeddingOrProjectionRequests() {
    try (var context = context()) {
      var images = new Images();
      var projection = new Projection();
      var found =
          queries(context, images, projection)
              .searchImages(
                  QueryAttachmentAnswerFixture.prepared("Which shape?"),
                  new RetrievalProjection.AuthorizedScope("org-main", Map.of()),
                  () -> {},
                  ids -> ids);
      assertTrue(found.isEmpty());
      assertTrue(images.received.isEmpty());
      assertEquals(0, projection.preparations);
    }
  }

  @Test
  void allRoutesAreHydratedBeforeUnionTruncationAndSharedRanksAreFused() {
    try (var context = context()) {
      var images = new Images();
      var projection = new Projection();
      var original = QueryAttachmentAnswerFixture.prepared("Which shape?");
      projection.distinctSecondRoute = true;
      var second = QueryAttachmentAnswerFixture.image("png");
      var firstManifest = original.attachments().getFirst();
      var query =
          new PreparedQuery(
              original.originalQuestion(),
              original.retrievalText(),
              List.of(original.queryImages().getFirst(), second),
              List.of(
                  firstManifest,
                  new QueryAttachmentManifest(
                      1,
                      second.sha256(),
                      QueryAttachment.Kind.IMAGE,
                      firstManifest.compilerRevision(),
                      firstManifest.contentSha256(),
                      firstManifest.textCodePoints(),
                      1,
                      List.of(second.sha256()),
                      false)),
              original.preparationRevision());
      var batches = new ArrayList<List<String>>();
      var found =
          queries(context, images, projection)
              .searchImages(
                  query,
                  new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation")),
                  () -> {},
                  ids -> {
                    batches.add(ids);
                    return ids.stream().map(id -> "base-" + id).toList();
                  });
      assertEquals(2, images.received.size());
      assertEquals(2, batches.size());
      assertTrue(batches.stream().allMatch(ids -> ids.size() == 64));
      assertTrue(batches.getLast().contains("second-63"));
      assertEquals(64, found.size());
      assertTrue(
          found.stream().noneMatch(candidate -> candidate.segmentId().equals("base-second-63")));
      assertEquals("base-vector-0", found.getFirst().segmentId());
      assertEquals(2.0 / 61, found.getFirst().score());
    }
  }

  @Test
  void incompleteOrNonfiniteImageEmbeddingNeverDispatchesVectorSearch() {
    try (var context = context()) {
      var images = new Images();
      var projection = new Projection();
      var service = queries(context, images, projection);
      var query = QueryAttachmentAnswerFixture.prepared("Which shape?");
      var authorized =
          new RetrievalProjection.AuthorizedScope("org-main", Map.of("doc", "generation"));
      images.result = null;
      assertThrows(
          TextModels.Failure.class,
          () -> service.searchImages(query, authorized, () -> {}, ids -> ids));
      images.result = List.of(1.0);
      assertThrows(
          TextModels.Failure.class,
          () -> service.searchImages(query, authorized, () -> {}, ids -> ids));
      images.result = List.of(Double.NaN, 1.0);
      assertThrows(
          ProjectionException.class,
          () -> service.searchImages(query, authorized, () -> {}, ids -> ids));
      assertEquals(3, images.received.size());
      assertEquals(0, projection.searches);
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void incompleteOversizedOrDuplicateProviderHitsNeverReachAuthorityMapping() {
    try (var context = context()) {
      var images = new Images();
      var projection = new Projection();
      var service = queries(context, images, projection);
      var complete =
          IntStream.range(0, 64)
              .mapToObj(index -> new RetrievalProjection.Candidate("vector-" + index, 64 - index))
              .toList();
      var missingTail = new ArrayList<>(complete);
      missingTail.set(63, null);
      var duplicateTail = new ArrayList<>(complete);
      duplicateTail.set(63, complete.getFirst());
      List<Supplier<List<RetrievalProjection.Candidate>>> failures =
          List.of(
              () -> null, () -> Collections.nCopies(65, complete.getFirst()),
              () -> missingTail, () -> duplicateTail);
      var mappings = new AtomicInteger();
      for (var failure : failures) {
        projection.response = ignored -> failure.get();
        assertThrows(
            TextModels.Failure.class,
            () ->
                service.searchImages(
                    QueryAttachmentAnswerFixture.prepared("Which shape?"),
                    new RetrievalProjection.AuthorizedScope(
                        "org-main", Map.of("doc", "generation")),
                    () -> {},
                    ids -> {
                      mappings.incrementAndGet();
                      return ids;
                    }));
      }
      assertEquals(4, projection.searches);
      assertEquals(0, mappings.get(), "A valid first hit cannot hide an invalid tail");
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void incompleteOrCollidingAuthorityMappingsNeverProduceFusedCandidates() {
    try (var context = context()) {
      var service = queries(context, new Images(), new Projection());
      List<Function<List<String>, List<String>>> failures =
          List.of(
              ids -> null,
              ids -> {
                var changed = new ArrayList<>(ids);
                changed.set(63, null);
                return changed;
              },
              ids -> {
                var changed = new ArrayList<>(ids);
                changed.set(63, " ");
                return changed;
              },
              ids -> {
                var changed = new ArrayList<>(ids);
                changed.set(63, ids.getFirst());
                return changed;
              });
      var mapped = new AtomicInteger();
      for (var failure : failures) {
        assertThrows(
            TextModels.Failure.class,
            () ->
                service.searchImages(
                    QueryAttachmentAnswerFixture.prepared("Which shape?"),
                    new RetrievalProjection.AuthorizedScope(
                        "org-main", Map.of("doc", "generation")),
                    () -> {},
                    ids -> {
                      assertEquals(64, ids.size());
                      mapped.incrementAndGet();
                      return failure.apply(ids);
                    }));
      }
      assertEquals(4, mapped.get());
      assertTrue(context.models.calls.isEmpty());
    }
  }

  @Test
  void dimensionProjectionOrUnavailableProfileAfterEmbeddingStopsBeforeSearch() {
    try (var context = context()) {
      for (int change = 0; change < 3; change++) {
        var images = new Images();
        var projection = new Projection();
        var service = queries(context, images, projection);
        images.afterEmbed =
            switch (change) {
              case 0 -> () -> images.dimensions = 3;
              case 1 -> () -> projection.identity = "f".repeat(64);
              default -> () -> images.profileUnavailable = true;
            };
        assertEquals(
            "configuration_changed",
            assertThrows(
                    ApplicationException.class,
                    () ->
                        service.searchImages(
                            QueryAttachmentAnswerFixture.prepared("Which shape?"),
                            new RetrievalProjection.AuthorizedScope(
                                "org-main", Map.of("doc", "generation")),
                            () -> {},
                            ids -> ids))
                .code());
        assertEquals(1, images.received.size());
        assertEquals(0, projection.searches);
        assertTrue(service.configurationCurrent(), "Old text profile remains independent");
      }
    }
  }

  private AnswerTestContext context() {
    return new AnswerTestContext(directory, QueryAttachmentAnswerFixture.BUDGET, 1);
  }

  private static QueryAttachmentService queries(
      AnswerTestContext context, Images images, Projection projection) {
    VisionModels vision =
        new VisionModels() {
          public String revision() {
            return "vision-v1";
          }

          public Description describe(VisualImage image) {
            throw new AssertionError("No preparation");
          }

          public Draft draft(String question, VisualImage image) {
            throw new AssertionError("No proof");
          }

          public Verification verify(String question, VisualImage image, List<String> claims) {
            throw new AssertionError("No proof");
          }
        };
    QueryRankingModels ranking =
        new QueryRankingModels() {
          public String revision() {
            return "ranking-v1";
          }

          public List<TextModels.Ranked> rank(
              PreparedQuery query, List<QueryRankCandidate> candidates) {
            throw new AssertionError("No ranking");
          }
        };
    return new QueryAttachmentService(
        QueryAttachmentAnswerFixture.preparation(vision),
        ranking,
        context.models,
        context.projection,
        context.target,
        images,
        projection,
        new IndexTarget(
            images.revision(), projection.identity(), images.revision(), images.dimensions()));
  }

  private static final class Images implements ImageEmbeddingModels {
    private String revision = "image-profile-v1";
    private final List<VisualImage> received = new ArrayList<>();
    private List<Double> result = List.of(1.0, 0.0);
    private int dimensions = 2;
    private boolean profileUnavailable;
    private Runnable afterEmbed = () -> {};

    public String revision() {
      if (profileUnavailable) {
        throw new TextModels.Failure("model_unavailable");
      }
      return revision;
    }

    public int dimensions() {
      return dimensions;
    }

    public List<Double> embed(VisualImage image) {
      received.add(image);
      afterEmbed.run();
      return result;
    }
  }

  private static final class Projection implements RetrievalProjection {
    private Query query;
    private int preparations;
    private int searches;
    private boolean distinctSecondRoute;
    private String identity = "e".repeat(64);
    private Function<Query, List<Candidate>> response;

    public String identity() {
      return identity;
    }

    public void initialize() {
      throw new AssertionError("Read-only query");
    }

    public void prepareSearch() {
      preparations++;
    }

    public void upsert(List<Entry> entries) {
      throw new AssertionError("Read-only query");
    }

    public VerifiedRevision verify(RevisionManifest manifest) {
      throw new AssertionError("Read-only query");
    }

    public List<Candidate> search(Query value) {
      query = value;
      searches++;
      if (response != null) {
        return response.apply(value);
      }
      return IntStream.range(0, 64)
          .mapToObj(
              i ->
                  new Candidate(
                      distinctSecondRoute && searches == 2 && i != 0
                          ? "second-" + i
                          : "vector-" + i,
                      64 - i))
          .toList();
    }
  }
}
