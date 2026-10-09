package com.evidence.rag.support;

import com.evidence.rag.RagApplication;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Scanner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

/** Explicit isolated Java test runtime for the genuine separately running DB-GPT service. */
public final class DbGptLocalIntegrationServer {
  private DbGptLocalIntegrationServer() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException("Pass NEW absolute data directory, Java port, Agent port");
    }
    Path directory = Path.of(args[0]);
    int port = Integer.parseInt(args[1]);
    int agentPort = Integer.parseInt(args[2]);
    String token = System.getenv("AGENT_SERVICE_TOKEN");
    if (!directory.isAbsolute()
        || Files.exists(directory)
        || token == null
        || token.length() < 24) {
      throw new IllegalArgumentException("Requires new data directory and private service token");
    }
    Files.createDirectories(directory);
    try (var models = new DbGptProtocolServer();
        var projection = new IndexingTestServer(2, 4 * 1024 * 1024, models.endpoint(), true);
        var http = HttpClient.newHttpClient();
        var input = new Scanner(System.in)) {
      ConfigurableApplicationContext context =
          start(directory, models, projection, port, agentPort, token);
      try {
        WikiLocalIntegrationServer.activate(http, "http://127.0.0.1:" + port);
        System.out.println("DBGPT_INTEGRATION_READY http://127.0.0.1:" + port);
        System.out.println("REAL_DBGPT_WITH_SYNTHETIC_LOCAL_MODELS; type stats, restart or stop");
        while (input.hasNextLine()) {
          String command = input.nextLine().strip();
          if (command.equals("stop")) break;
          if (command.equals("stats")) {
            System.out.println("AGENT_MODEL_CALLS=" + models.agentCalls.get());
            System.out.println("SEARCH_ACTIONS=" + models.searchActions.get());
            System.out.println("READ_ACTIONS=" + models.readActions.get());
          }
          if (command.equals("restart")) {
            context.close();
            context = start(directory, models, projection, port, agentPort, token);
            System.out.println("DBGPT_INTEGRATION_RESTARTED same isolated data");
          }
        }
      } finally {
        context.close();
      }
    }
  }

  private static ConfigurableApplicationContext start(
      Path directory,
      DbGptProtocolServer models,
      IndexingTestServer projection,
      int port,
      int agentPort,
      String token) {
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
    defaults.put("rag.knowledge-agent.service-token", token);
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
        "--rag.model-configuration.deadline-ms=10000",
        "--rag.ingestion.parse-timeout-ms=15000",
        "--rag.indexing.timeout-ms=15000",
        "--rag.answers.timeout-ms=15000",
        "--rag.knowledge-agent.enabled=true",
        "--rag.knowledge-agent.base-url=http://127.0.0.1:" + agentPort,
        "--rag.knowledge-agent.timeout-ms=120000");
  }
}
