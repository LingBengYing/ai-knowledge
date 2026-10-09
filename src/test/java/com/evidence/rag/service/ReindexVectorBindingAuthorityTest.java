package com.evidence.rag.service;

import static com.evidence.rag.support.PublishedCorpusFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.domain.ImageVectorBinding;
import com.evidence.rag.model.domain.ImageVectorPublication;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.ReindexVectorPlan;
import com.evidence.rag.model.domain.VectorBindingIdentity;
import com.evidence.rag.model.entity.IndexPublicationEntity;
import com.evidence.rag.repository.AudioVectorRepository;
import com.evidence.rag.repository.ImageVectorRepository;
import com.evidence.rag.repository.IndexingRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.support.AnswerTestContext;
import com.evidence.rag.support.PublishedCorpusFixture;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

/**
 * Real temporary SQL rows and complete saved sources; all receipts are explicit synthetic fixtures.
 */
class ReindexVectorBindingAuthorityTest {
  @TempDir Path directory;

  static ReindexVectorPlan queue(SqliteAuthorityStore store, String workspace, String document) {
    return store.transaction(
        () -> {
          var repository = new IndexingRepository(store);
          String base = repository.activePublication(document).orElseThrow().id();
          String job = UUID.randomUUID().toString();
          var plan = repository.vectorPlanForBase(job, workspace, base);
          repository.insertRebuildJob(
              job,
              document,
              repository.parsedRevision(document).orElseThrow(),
              plan.basePublication().target(),
              "owner",
              base,
              repository.nextRebuildSequence(document),
              Instant.now().toString(),
              plan.setSha256());
          String generation = UUID.randomUUID().toString();
          repository.insertAttempt(job, 1, generation, Instant.now().toString());
          repository.markProcessing(job, "9".repeat(64), generation, Instant.now().toString());
          assertTrue(repository.sourceCurrent(repository.findInternalTask(job).orElseThrow()));
          assertEquals(plan, repository.freezeVectorPlan(job));
          return plan;
        });
  }

  static PublicationVersion stage(SqliteAuthorityStore store, ReindexVectorPlan plan) {
    var repository = new IndexingRepository(store);
    var job = repository.findInternalTask(plan.jobId()).orElseThrow();
    var items = repository.projectionItems(job.revisionId());
    var entries = new LinkedHashMap<String, String>();
    for (var item : items) {
      entries.put(
          RetrievalProjection.physicalSegmentId(job.projectionGenerationId(), item.evidenceId()),
          "a".repeat(64));
    }
    String manifest =
        new RetrievalProjection.RevisionManifest(
                job.workspaceId(), job.documentId(), job.projectionGenerationId(), entries)
            .sha256();
    String id = UUID.randomUUID().toString();
    repository.insertPublication(
        new IndexPublicationEntity(
            id,
            job.id(),
            job.documentId(),
            job.revisionId(),
            job.attempt(),
            job.projectionGenerationId(),
            job.sourceSha256(),
            job.parserRevision(),
            job.target(),
            manifest,
            items.size(),
            Instant.now().toString()));
    for (var item : items) {
      String physical =
          RetrievalProjection.physicalSegmentId(job.projectionGenerationId(), item.evidenceId());
      repository.insertPublicationEntry(id, item.evidenceId(), physical, entries.get(physical));
    }
    return new PublicationVersion(
        job.documentId(),
        id,
        job.revisionId(),
        job.projectionGenerationId(),
        job.sourceSha256(),
        job.parserRevision(),
        job.target(),
        manifest,
        items.size());
  }

  static PublicationVersion complete(SqliteAuthorityStore store, ReindexVectorPlan plan) {
    return store.transaction(
        () -> {
          var repository = new IndexingRepository(store);
          var publication = stage(store, plan);
          repository.insertInheritedBindings(publication, plan);
          repository.activatePublication(
              publication.documentId(),
              publication.publicationId(),
              publication.sourceRevisionId());
          repository.markIndexed(plan.jobId(), Instant.now().toString());
          return publication;
        });
  }

  static ImageVectorPublication image(PublishedCorpusFixture fixture, String doc) {
    var service =
        ImageVectorIndexingServiceTest.service(
            fixture,
            Duration.ofSeconds(2),
            2,
            () -> ImageVectorIndexingServiceTest.IMAGE_TARGET,
            (claim, budget) -> ImageVectorIndexingServiceTest.receipt(claim));
    return service.build(ImageVectorIndexingServiceTest.OWNER, doc).publication();
  }

  @Test
  void completeImageInheritanceFlattensTwiceAndSurvivesRestartWithoutChangingTheOrigin() {
    String document;
    ImageVectorPublication origin;
    PublicationVersion last;
    try (var fixture = new PublishedCorpusFixture(directory)) {
      document = ImageVectorIndexingServiceTest.publish(fixture);
      origin = image(fixture, document);
      var store = fixture.authority.store();
      assertFalse(store.transaction(() -> new IndexingRepository(store).canReindex(document)));
      assertTrue(
          store.transaction(() -> new IndexingRepository(store).canReindexWithVectors(document)));
      var first = complete(store, queue(store, "org", document));
      var secondPlan = queue(store, "org", document);
      assertEquals(origin, secondPlan.images().getFirst().origin());
      assertEquals(
          origin.basePublication().publicationId(),
          secondPlan.images().getFirst().inheritedFromPublicationId());
      last = complete(store, secondPlan);
      var values =
          store.transaction(() -> new ImageVectorRepository(store).allBindings("org", last));
      assertEquals(1, values.size());
      assertEquals(origin, values.getFirst().origin());
      assertEquals(first.publicationId(), values.getFirst().inheritedFromPublicationId());
      assertNotEquals(
          origin.basePhysicalSegmentId(), values.getFirst().currentBasePhysicalSegmentId());
      assertEquals(
          VectorBindingIdentity.physicalSegmentId(
              last.projectionGenerationId(), origin.imageEvidenceId()),
          values.getFirst().currentBasePhysicalSegmentId());
      assertEquals(
          1,
          store.transaction(() -> count(store, "SELECT COUNT(*) FROM image_vector_publications")));
      assertEquals(
          2, store.transaction(() -> count(store, "SELECT COUNT(*) FROM image_vector_bindings")));
    }
    try (var store = new SqliteAuthorityStore(directory)) {
      var values =
          store.transaction(
              () ->
                  new ImageVectorRepository(store)
                      .findBindings("org", List.of(last), origin.target()));
      assertEquals(origin, values.getFirst().origin());
      assertEquals(last, values.getFirst().basePublication());
      assertTrue(
          store.transaction(
              () ->
                  new ImageVectorRepository(store)
                      .findBindings("org", List.of(last), TARGET)
                      .isEmpty()));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(() -> new ImageVectorRepository(store).allBindings("other", last)));
    }
  }

  @Test
  void audioInheritancePreservesCompleteOrdinalGapsSampleBoundsAndAllRemoteEntryIdentities() {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(5), 1)) {
      var published = AudioTestFixture.publish(fixture, List.of("头部预算为650元。", "", "尾部编号为73921。"));
      var base =
          fixture.evidence.snapshot(
              fixture.owner,
              DocumentSelection.selected(List.of(published.documentId())),
              fixture.target);
      var origin = AudioVectorQueryFixture.publishVectors(fixture, base, published);
      var store = fixture.authority.store();
      var plan = queue(store, fixture.owner.workspaceId(), published.documentId());
      assertEquals(
          List.of(0, 2),
          plan.audios().getFirst().origin().entries().stream()
              .map(entry -> entry.ordinal())
              .toList());
      var next = complete(store, plan);
      var result =
          store
              .transaction(
                  () ->
                      new AudioVectorRepository(store)
                          .allBindings(fixture.owner.workspaceId(), next))
              .getFirst();
      assertEquals(origin, result.origin());
      assertEquals(
          origin.entries().stream()
              .map(
                  entry ->
                      VectorBindingIdentity.physicalSegmentId(
                          next.projectionGenerationId(), entry.audioEvidenceId()))
              .toList(),
          result.currentBasePhysicalSegmentIds());
      assertNotEquals(
          origin.entries().getLast().basePhysicalSegmentId(),
          result.currentBasePhysicalSegmentIds().getLast());
      assertEquals(origin.entries(), result.origin().entries());
      assertEquals(
          1,
          store.transaction(() -> count(store, "SELECT COUNT(*) FROM audio_vector_publications")));
      assertEquals(
          2, store.transaction(() -> count(store, "SELECT COUNT(*) FROM audio_vector_entries")));
    }
  }

  @Test
  void anOmittedBindingCannotMoveTheActivePointerAndTheWholeStageRollsBack() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = ImageVectorIndexingServiceTest.publish(fixture);
      var origin = image(fixture, doc);
      var store = fixture.authority.store();
      var plan = queue(store, "org", doc);
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    var staged = stage(store, plan);
                    new IndexingRepository(store)
                        .activatePublication(
                            doc, staged.publicationId(), staged.sourceRevisionId());
                    return null;
                  }));
      assertEquals(
          origin.basePublication().publicationId(),
          store.transaction(
              () -> new IndexingRepository(store).activePublication(doc).orElseThrow().id()));
      assertEquals(
          1, store.transaction(() -> count(store, "SELECT COUNT(*) FROM index_publications")));
      assertEquals(
          0, store.transaction(() -> count(store, "SELECT COUNT(*) FROM image_vector_bindings")));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"update", "delete", "replace", "initial-snapshot"})
  void ordinaryWritersCannotRewriteTheSealedAssociationOrTaskSnapshot(String operation) {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = ImageVectorIndexingServiceTest.publish(fixture);
      image(fixture, doc);
      var store = fixture.authority.store();
      var plan = queue(store, "org", doc);
      complete(store, plan);
      var denial =
          assertThrows(
              SQLException.class,
              () -> {
                switch (operation) {
                  case "update" ->
                      executeSql(
                          "UPDATE image_vector_bindings SET binding_sha256=?", "e".repeat(64));
                  case "delete" -> executeSql("DELETE FROM image_vector_bindings");
                  case "replace" ->
                      executeSql(
                          "INSERT OR REPLACE INTO image_vector_bindings SELECT * FROM image_vector_bindings");
                  case "initial-snapshot" ->
                      executeSql(
                          "UPDATE indexing_jobs SET base_vector_set_sha256=? WHERE rebuild_sequence=0",
                          plan.setSha256());
                  default -> throw new AssertionError(operation);
                }
              });
      assertEquals(
          19,
          denial.getErrorCode() & 255,
          "The real temporary SQLite guard must reject the write: " + denial.getMessage());
      assertEquals(
          1, store.transaction(() -> count(store, "SELECT COUNT(*) FROM image_vector_bindings")));
      assertEquals(
          plan.setSha256(),
          store.transaction(
              () -> new IndexingRepository(store).baseVectorSetSha256(plan.jobId()).orElseThrow()));
    }
  }

  @Test
  void aLateAdditionalProfileChangesTheWholeSetAndFencesThePreviouslyClaimedTask() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = ImageVectorIndexingServiceTest.publish(fixture);
      var origin = image(fixture, doc);
      var store = fixture.authority.store();
      var plan = queue(store, "org", doc);
      var other = new IndexTarget("another-image-v1", "f".repeat(64), "another-model-v1", 2);
      String generation = UUID.randomUUID().toString();
      String physical = RetrievalProjection.physicalSegmentId(generation, origin.imageEvidenceId());
      String manifest =
          new RetrievalProjection.RevisionManifest(
                  "org", doc, generation, Map.of(physical, origin.entrySha256()))
              .sha256();
      var additional =
          new ImageVectorPublication(
              "additional",
              origin.basePublication(),
              origin.imageEvidenceId(),
              origin.basePhysicalSegmentId(),
              generation,
              physical,
              other,
              origin.entrySha256(),
              manifest,
              origin.createdAt());
      store.transaction(
          () -> {
            new ImageVectorRepository(store).insert(additional);
            return null;
          });
      assertFalse(
          store.transaction(
              () -> {
                var repository = new IndexingRepository(store);
                return repository.sourceCurrent(
                    repository.findInternalTask(plan.jobId()).orElseThrow());
              }));
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> new IndexingRepository(store).freezeVectorPlan(plan.jobId())));
      assertEquals(
          2,
          store.transaction(
              () ->
                  new ImageVectorRepository(store)
                      .allBindings("org", origin.basePublication())
                      .size()));
      assertEquals(
          origin.basePublication().publicationId(),
          store.transaction(
              () -> new IndexingRepository(store).activePublication(doc).orElseThrow().id()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"hash", "provenance"})
  void corruptedBindingMetadataIsRejectedInsteadOfReturningAnApparentlyAvailableAsset(
      String corruption) throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = ImageVectorIndexingServiceTest.publish(fixture);
      var origin = image(fixture, doc);
      var store = fixture.authority.store();
      var next = complete(store, queue(store, "org", doc));
      String physical =
          VectorBindingIdentity.physicalSegmentId(
              next.projectionGenerationId(), origin.imageEvidenceId());
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
          var statement = connection.createStatement()) {
        String guard;
        try (var row =
            statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE name='image_vector_bindings_no_update'")) {
          assertTrue(row.next());
          guard = row.getString(1);
        }
        statement.execute("DROP TRIGGER image_vector_bindings_no_update");
        try (var update =
            connection.prepareStatement(
                "UPDATE image_vector_bindings SET inherited_from_publication_id=?,binding_sha256=? WHERE publication_id=?")) {
          String from =
              corruption.equals("provenance")
                  ? "unknown"
                  : origin.basePublication().publicationId();
          update.setString(1, from);
          update.setString(
              2,
              corruption.equals("hash")
                  ? "f".repeat(64)
                  : VectorBindingIdentity.imageSha256(next, origin, physical, from));
          update.setString(3, next.publicationId());
          assertEquals(1, update.executeUpdate());
        } finally {
          statement.execute(guard);
        }
      }
      assertThrows(
          ApplicationException.class,
          () -> store.transaction(() -> new ImageVectorRepository(store).allBindings("org", next)));
      assertFalse(
          store.transaction(() -> new IndexingRepository(store).canReindexWithVectors(doc)));
    }
    assertThrows(IllegalStateException.class, () -> new SqliteAuthorityStore(directory));
  }

  @Test
  void aBindingMustHaveRealMatchingNewTextEvidenceAndExactDirectPreviousBase() {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      String doc = ImageVectorIndexingServiceTest.publish(fixture);
      var origin = image(fixture, doc);
      var store = fixture.authority.store();
      var plan = queue(store, "org", doc);
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    var next = stage(store, plan);
                    String physical =
                        VectorBindingIdentity.physicalSegmentId(
                            next.projectionGenerationId(), origin.imageEvidenceId());
                    var binding =
                        new ImageVectorBinding(
                            next,
                            origin,
                            physical,
                            "unknown",
                            VectorBindingIdentity.imageSha256(next, origin, physical, "unknown"));
                    new ImageVectorRepository(store)
                        .insertBinding(binding, Instant.now().toString());
                    return null;
                  }));
      var next = complete(store, plan);
      assertThrows(
          ApplicationException.class,
          () ->
              store.transaction(
                  () -> {
                    var binding =
                        new ImageVectorBinding(
                            next,
                            origin,
                            VectorBindingIdentity.physicalSegmentId(
                                next.projectionGenerationId(), origin.imageEvidenceId()),
                            origin.basePublication().publicationId(),
                            VectorBindingIdentity.imageSha256(
                                next,
                                origin,
                                VectorBindingIdentity.physicalSegmentId(
                                    next.projectionGenerationId(), origin.imageEvidenceId()),
                                origin.basePublication().publicationId()));
                    new ImageVectorRepository(store)
                        .insertBinding(binding, Instant.now().toString());
                    return null;
                  }));
      assertEquals(
          next.publicationId(),
          store.transaction(
              () -> new IndexingRepository(store).activePublication(doc).orElseThrow().id()));
    }
  }

  private static long count(SqliteAuthorityStore store, String sql) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.libraryPath());
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    } catch (SQLException failure) {
      throw new AssertionError("Cannot read the temporary fixture database", failure);
    }
  }

  private void executeSql(String sql, Object... args) throws SQLException {
    try (var connection =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"))) {
      Function.create(
          connection,
          "java_model_rebuild_authorized",
          new Function() {
            @Override
            protected void xFunc() throws SQLException {
              result(0);
            }
          });
      try (var statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < args.length; i++) {
          statement.setObject(i + 1, args[i]);
        }
        statement.executeUpdate();
      }
    }
  }
}
