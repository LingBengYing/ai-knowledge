package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.job.SynopsisJob;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.ManagementService;
import com.evidence.rag.service.SynopsisLibraryService;
import com.evidence.rag.support.SynopsisCorpusFixture;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Current saved summaries become reviewed metadata; generating a new summary is not this flow. */
class TagSuggestionsMainlineHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Actor OWNER = new Actor("org-main", "owner");
  @TempDir Path directory;

  @Test
  void readReviewMergeFilterAndRestartPreserveOriginalEvidenceWithoutModelCalls() throws Exception {
    try (var app = new Application(directory)) {
      var publication =
          new SynopsisCorpusFixture(
                  app.context.getBean(SqliteAuthorityStore.class),
                  OWNER,
                  new IndexTarget("embedding", "b".repeat(64), "model-v1", 2))
              .text("设备维护使用太阳能。先关闭开关再维护。");
      String document = publication.documentId();
      var library = app.context.getBean(SynopsisLibraryService.class);
      var task = library.create(OWNER, document);
      var claim = library.claim(OWNER.workspaceId()).orElseThrow();
      var source = claim.input().evidence().getFirst();
      var reference =
          new FileSynopsis.Reference(source.id(), source.sha256(), source.kind(), source.time());
      var entries = new ArrayList<FileSynopsis.Entry>();
      for (var item :
          List.of(
              new SynopsisDraft.Item(
                  SynopsisDraft.Section.OVERVIEW, "设备维护使用太阳能。", List.of(source.id())),
              new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, "设备维护", List.of(source.id())),
              new SynopsisDraft.Item(SynopsisDraft.Section.TERM, "太阳能", List.of(source.id())))) {
        entries.add(new FileSynopsis.Entry(item, List.of(reference), null));
      }
      assertTrue(
          library.complete(
              claim,
              new FileSynopsis(
                  publication,
                  claim.input().fingerprint(),
                  claim.modelRevision(),
                  claim.policyRevision(),
                  entries,
                  null)));
      assertEquals(
          200,
          app.request(
                  "PATCH", "/v1/management/documents/" + document, Map.of("tags", List.of("手工标签")))
              .statusCode());
      String path = "/v1/documents/" + document + "/tag-suggestions";
      int before = app.context.getBean(ManagementService.class).auditEvents(OWNER).size();
      var response = app.request("GET", path, null);
      assertEquals(200, response.statusCode(), response.body());
      var suggestions = JSON.readTree(response.body());
      assertEquals(task.taskId(), suggestions.path("synopsis_id").asString());
      assertEquals(publication.sourceRevisionId(), suggestions.path("revision_id").asString());
      assertEquals(publication.sourceSha256(), suggestions.path("source_sha256").asString());
      assertEquals("太阳能", suggestions.path("candidates").get(0).path("tag").asString());
      assertEquals("设备维护", suggestions.path("candidates").get(1).path("tag").asString());
      assertEquals(before, app.context.getBean(ManagementService.class).auditEvents(OWNER).size());
      // A different editor's ordinary metadata write happens after suggestions were read.
      assertEquals(
          200,
          app.request(
                  "PATCH",
                  "/v1/management/documents/" + document,
                  Map.of("tags", List.of("手工标签", "期间新增")))
              .statusCode());
      var saved =
          app.request(
              "POST",
              path + "/apply",
              Map.of(
                  "suggestion_fingerprint",
                  suggestions.path("suggestion_fingerprint").asString(),
                  "ordinals",
                  List.of(1)));
      assertEquals(200, saved.statusCode(), saved.body());
      var row = JSON.readTree(saved.body());
      assertEquals(List.of("手工标签", "期间新增", "太阳能"), strings(row.path("tags")));
      assertEquals(publication.sourceSha256(), row.path("media_info").path("sha256").asString());
      assertEquals(publication.sourceRevisionId(), row.path("active_revision_id").asString());
      assertEquals(publication.publicationId(), row.path("index_publication_id").asString());
      String filter =
          "/v1/management/documents?tag=" + URLEncoder.encode("太阳能", StandardCharsets.UTF_8);
      assertEquals(1, JSON.readTree(app.request("GET", filter, null).body()).path("total").asInt());
      assertEquals(0, app.modelCalls.get());
      app.restart();
      assertEquals(1, JSON.readTree(app.request("GET", filter, null).body()).path("total").asInt());
      var reopened = JSON.readTree(app.request("GET", path, null).body());
      assertEquals(
          suggestions.path("suggestion_fingerprint"), reopened.path("suggestion_fingerprint"));
      assertEquals(List.of("手工标签", "期间新增", "太阳能"), strings(reopened.path("existing_tags")));
      assertEquals(0, app.modelCalls.get());
    }
  }

  private static List<String> strings(JsonNode items) {
    var values = new ArrayList<String>();
    items.forEach(value -> values.add(value.asString()));
    return values;
  }

  private static final class Application implements AutoCloseable {
    private final Path directory;
    private final HttpServer server;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final HttpClient client = HttpClient.newHttpClient();
    private ConfigurableApplicationContext context;
    private String base;

    Application(Path directory) throws Exception {
      this.directory = directory;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            modelCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
          });
      server.start();
      start();
    }

    private void start() {
      var app = new SpringApplication(RagApplication.class);
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      app.setEnvironment(environment);
      context =
          app.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + directory,
              "--rag.ingestion.enabled=false",
              "--rag.indexing.enabled=false",
              "--rag.answers.enabled=false",
              "--rag.synopsis.enabled=true",
              "--rag.synopsis.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "--rag.synopsis.model=synthetic-summary",
              "--rag.synopsis.api-key=synthetic-summary-credential",
              "--rag.synopsis.allow-loopback-http=true");
      context.getBean(SynopsisJob.class).close();
      base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    void restart() {
      context.close();
      start();
    }

    HttpResponse<String> request(String method, String path, Map<String, ?> body) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(15))
              .header("Origin", base)
              .header("Content-Type", "application/json")
              .header("X-Workspace-Id", OWNER.workspaceId())
              .header("X-Principal-Id", OWNER.principalId())
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
      if (context != null) {
        context.close();
      }
      server.stop(0);
      client.close();
    }
  }
}
