package com.evidence.rag.support;

import com.evidence.rag.RagApplication;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit local integration launcher. Real application, SQLite, parser and worker; deterministic
 * loopback model/Milvus protocol substitutes. Never loads private environment or contacts
 * providers. Not packaged in the production application. Type restart to verify durable state with
 * live fixtures.
 */
public final class WikiLocalIntegrationServer {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private WikiLocalIntegrationServer() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Pass one NEW absolute integration data directory");
    }
    Path directory = Path.of(args[0]);
    if (!directory.isAbsolute() || Files.exists(directory)) {
      throw new IllegalArgumentException("Integration requires a new absolute directory");
    }
    Files.createDirectories(directory);
    try (var models = new AnswerProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint(), true);
        var http = HttpClient.newHttpClient();
        var input = new Scanner(System.in)) {
      ConfigurableApplicationContext context = start(directory, models, projection, 18091);
      try {
        activate(http, "http://127.0.0.1:18091");
        System.out.println("WIKI_INTEGRATION_READY http://127.0.0.1:18091");
        System.out.println("LOCAL_SYNTHETIC_PROTOCOL_ONLY; type restart or stop");
        while (input.hasNextLine()) {
          String command = input.nextLine().strip();
          if (command.equals("stop")) break;
          if (command.equals("restart")) {
            context.close();
            context = start(directory, models, projection, 18091);
            System.out.println(
                "WIKI_INTEGRATION_RESTARTED same SQLite and same loopback providers");
          }
        }
      } finally {
        context.close();
      }
    }
  }

  static ConfigurableApplicationContext start(
      Path directory, AnswerProtocolServer models, IndexingTestServer projection, int port) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var defaults = new LinkedHashMap<String, Object>();
    defaults.put("RAG_MILVUS_ENDPOINT", projection.endpoint().toString());
    defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
    defaults.put("RAG_MILVUS_COLLECTION", "java_index_process_fixture");
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    application.setDefaultProperties(defaults);
    return application.run(
        "--server.port=" + port,
        "--server.address=127.0.0.1",
        "--rag.environment=test",
        "--rag.workspace-id=org-main",
        "--rag.auth-mode=development_headers",
        "--rag.data-directory=" + directory,
        "--rag.ingestion.enabled=true",
        "--rag.indexing.enabled=true",
        "--rag.answers.enabled=true",
        "--rag.model-configuration.enabled=true",
        "--rag.model-configuration.administrators=owner",
        "--rag.model-configuration.provider-base-url=" + models.endpoint(),
        "--rag.model-configuration.allow-loopback-http=true",
        "--rag.model-configuration.deadline-ms=5000",
        "--rag.ingestion.parse-timeout-ms=15000",
        "--rag.indexing.timeout-ms=15000",
        "--rag.answers.timeout-ms=15000");
  }

  static void activate(HttpClient http, String base) throws Exception {
    var roles = new LinkedHashMap<String, Object>();
    roles.put("base_version", 0);
    roles.put(
        "embedding",
        Map.of(
            "model",
            "fixture-model",
            "dimensions",
            2,
            "revision",
            "fixture-v1",
            "api_key",
            "synthetic-managed-key"));
    roles.put("rerank", Map.of("model", "fixture-model", "api_key", "synthetic-managed-key"));
    roles.put("generation", Map.of("model", "fixture-model", "api_key", "synthetic-managed-key"));
    request(http, base, "PUT", "/v1/model-configuration", roles);
    for (String role : List.of("embedding", "rerank", "generation", "projection")) {
      var result =
          request(
              http,
              base,
              "POST",
              "/v1/model-configuration/test",
              Map.of("version", 1, "role", role));
      if (!JSON.readTree(result).path("status").asString().equals("passed")) {
        throw new IllegalStateException("Local protocol configuration failed: " + role);
      }
    }
    request(http, base, "POST", "/v1/model-configuration/activate", Map.of("version", 1));
  }

  private static String request(
      HttpClient http, String base, String method, String path, Object body) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Origin", base)
                .header("X-Principal-Id", "owner")
                .header("X-Workspace-Id", "org-main")
                .header("Content-Type", "application/json")
                .method(
                    method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Local setup failed: " + path + " HTTP " + response.statusCode());
    }
    return response.body();
  }
}
