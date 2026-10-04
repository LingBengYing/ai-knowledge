package com.evidence.rag.web;

import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.base;
import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.publication;
import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.request;
import static com.evidence.rag.web.ReindexVectorContinuationHttpFixture.vectorPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.IndexingTaskProcessor;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Additional full continuation flows; the separately retained first three HTTP RED cases are
 * unchanged.
 */
class ReindexVectorContinuationFlowHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"image", "audio"})
  void fullVerificationPublishesCurrentBindingsAndRepeatedRebuildAndRestartKeepTheOriginalAssets(
      String route) throws Exception {
    var fixture = new ReindexVectorContinuationHttpFixture(directory);
    try (var remote = new ReindexVectorContinuationHttpFixture.Remote();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      String document;
      String originalRows;
      String originalGeneration;
      String active;
      try (var app = fixture.start(remote)) {
        var seed = fixture.seed(app, http, route);
        document = seed.document();
        originalGeneration = seed.vectorGeneration();
        Path database = app.getBean(SqliteAuthorityStore.class).libraryPath();
        originalRows = rows(database, route + "_vector_publications", document);
        int before = remote.requests.size();
        var row = row(http, base(app), document);
        assertTrue(row.path("can_reindex").asBoolean(), row.toString());
        assertEquals(
            seed.publication().publicationId(), row.path("index_publication_id").asString());
        assertEquals(
            before,
            remote.requests.size(),
            "Management uses its current transaction without provider IO");
        for (int iteration = 0; iteration < 2; iteration++) {
          String previous = publication(app, document).publicationId();
          String task = queue(http, base(app), document, previous);
          assertFalse(row(http, base(app), document).path("can_reindex").asBoolean());
          assertEquals(previous, publication(app, document).publicationId());
          before = remote.requests.size();
          process(app, task);
          var done = request(http, base(app), "GET", "/v1/indexings/" + task, null, null, 200);
          assertEquals("indexed", done.path("state").asString(), done.toString());
          active = publication(app, document).publicationId();
          assertNotEquals(previous, active);
          assertTrue(row(http, base(app), document).path("can_reindex").asBoolean());
          assertEquals(originalRows, rows(database, route + "_vector_publications", document));
          var receipt =
              request(http, base(app), "GET", vectorPath(document, route), null, null, 200);
          assertEquals("available", receipt.path("status").asString());
          assertEquals(active, receipt.path("publication_id").asString());
          assertEquals(originalGeneration, receipt.path("vector_generation_id").asString());
          var newCalls = List.copyOf(remote.requests.subList(before, remote.requests.size()));
          var mediaCalls =
              newCalls.stream()
                  .filter(
                      call ->
                          call.body()
                              .path("collectionName")
                              .asString()
                              .equals("java_" + route + "_continuation_fixture"))
                  .toList();
          assertTrue(mediaCalls.stream().anyMatch(call -> call.path().endsWith("entities/query")));
          assertTrue(
              mediaCalls.stream()
                  .allMatch(
                      call ->
                          call.path().endsWith("collections/has")
                              || call.path().endsWith("collections/describe")
                              || call.path().endsWith("indexes/describe")
                              || call.path().endsWith("entities/query")),
              "Inherited asset checks never initialize/load/write");
          assertEquals(
              1, newCalls.stream().filter(call -> call.path().equals("/embeddings")).count());
          assertTrue(
              newCalls.stream()
                  .noneMatch(
                      call ->
                          call.path().endsWith(":embedContent")
                              || call.path().endsWith("transcriptions")
                              || call.path().equals("/chat/completions")));
        }
        active = publication(app, document).publicationId();
        assertFalse(Files.exists(directory.resolve("native-called")));
      }
      int beforeRestart = remote.requests.size();
      try (var app = fixture.start(remote)) {
        assertEquals(active, publication(app, document).publicationId());
        assertEquals(
            originalRows,
            rows(
                app.getBean(SqliteAuthorityStore.class).libraryPath(),
                route + "_vector_publications",
                document));
        var receipt = request(http, base(app), "GET", vectorPath(document, route), null, null, 200);
        assertEquals("available", receipt.path("status").asString());
        assertEquals(active, receipt.path("publication_id").asString());
        assertEquals(originalGeneration, receipt.path("vector_generation_id").asString());
        request(http, base(app), "GET", "/v1/documents/" + document + "/original", null, null, 200);
        assertTrue(row(http, base(app), document).path("can_reindex").asBoolean());
        assertEquals(
            beforeRestart,
            remote.requests.size(),
            "Restart/state/original reads never reverify remotely");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"image", "audio"})
  void changedRemoteOriginalDigestFailsTheTaskAndKeepsTheOldActiveReceipt(String route)
      throws Exception {
    var fixture = new ReindexVectorContinuationHttpFixture(directory);
    try (var remote = new ReindexVectorContinuationHttpFixture.Remote();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var app = fixture.start(remote)) {
      var seed = fixture.seed(app, http, route);
      String task = queue(http, base(app), seed.document(), seed.publication().publicationId());
      remote.corruptCollection = "java_" + route + "_continuation_fixture";
      process(app, task);
      var failed = request(http, base(app), "GET", "/v1/indexings/" + task, null, null, 200);
      assertEquals("failed", failed.path("state").asString(), failed.toString());
      assertEquals("indexing_output_invalid", failed.path("error_code").asString());
      assertEquals(seed.publication(), publication(app, seed.document()));
      int before = remote.requests.size();
      var state =
          request(http, base(app), "GET", vectorPath(seed.document(), route), null, null, 200);
      assertEquals("available", state.path("status").asString());
      assertEquals(seed.vectorGeneration(), state.path("vector_generation_id").asString());
      assertEquals(before, remote.requests.size());
      assertFalse(Files.exists(directory.resolve("native-called")));
    }
  }

  private static String queue(HttpClient http, String base, String document, String publication)
      throws Exception {
    return request(
            http,
            base,
            "POST",
            "/v1/documents/" + document + "/reindex",
            JSON.writeValueAsBytes(Map.of("base_publication_id", publication)),
            "application/json",
            202)
        .path("task_id")
        .asString();
  }

  private static void process(ConfigurableApplicationContext app, String task) {
    try (var operation = app.getBean(SqliteAuthorityStore.class).operationGate().enter()) {
      var processor = app.getBean(IndexingTaskProcessor.class);
      var claim = processor.claim().orElseThrow();
      assertEquals(task, claim.jobId());
      processor.process(claim);
    }
  }

  private static JsonNode row(HttpClient http, String base, String document) throws Exception {
    var page = request(http, base, "GET", "/v1/management/documents", null, null, 200);
    assertEquals(1, page.path("total").asInt());
    var row = page.path("items").get(0);
    assertEquals(document, row.path("document_id").asString());
    return row;
  }

  private static String rows(Path database, String table, String document) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var query =
            connection.prepareStatement(
                "SELECT * FROM " + table + " WHERE document_id=? ORDER BY id")) {
      query.setString(1, document);
      try (var result = query.executeQuery()) {
        var rows = new ArrayList<List<String>>();
        while (result.next()) {
          var row = new ArrayList<String>();
          for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
            row.add(result.getString(index));
          }
          rows.add(row);
        }
        assertFalse(rows.isEmpty());
        return JSON.writeValueAsString(rows);
      }
    }
  }
}
