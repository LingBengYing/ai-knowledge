package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.config.RagSettings;
import com.evidence.rag.support.AnswerProtocolServer;
import com.evidence.rag.worker.indexing.IndexingTestServer;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Actual HTTP/jobs/SQLite with explicit OCR and model/vector protocol fixtures, not quality eval.
 */
class ImageMainlineHttpTest {
  static final String TEXT = "Project A's budget is 650 USD.";
  @TempDir static Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private String base;
  private HttpClient http;

  protected Path ocrExecutable() throws Exception {
    Path executable = directory.resolve("synthetic-ocr");
    Files.writeString(
        executable,
        "#!/bin/sh\n[ \"$7\" = tsv ] || exit 2\n/bin/cat >/dev/null\n"
            + "/bin/cat <<'SYNTHETIC_TSV'\n"
            + syntheticTsv()
            + "SYNTHETIC_TSV\n");
    assertTrue(executable.toFile().setExecutable(true));
    return executable;
  }

  protected String ocrRevision() {
    return "synthetic-mainline-v2";
  }

  private static String syntheticTsv() {
    var tsv =
        new StringBuilder(
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "1\t1\t0\t0\t0\t0\t0\t0\t1200\t180\t-1\t\n"
                + "2\t1\t1\t0\t0\t0\t30\t55\t1050\t55\t-1\t\n"
                + "3\t1\t1\t1\t0\t0\t30\t55\t1050\t55\t-1\t\n"
                + "4\t1\t1\t1\t1\t0\t30\t55\t1050\t55\t-1\t\n");
    var canvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    var graphics = canvas.createGraphics();
    try {
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 52));
      var metrics = graphics.getFontMetrics();
      int left = 30, ordinal = 0;
      for (String word : TEXT.split(" ")) {
        int width = metrics.stringWidth(word);
        tsv.append("5\t1\t1\t1\t1\t")
            .append(++ordinal)
            .append('\t')
            .append(left)
            .append("\t55\t")
            .append(width)
            .append("\t55\t96\t")
            .append(word)
            .append('\n');
        left += width + metrics.stringWidth(" ");
      }
    } finally {
      graphics.dispose();
    }
    return tsv.toString();
  }

  @Test
  void imageTravelsThroughOcrParseAndIndex() throws Exception {
    assertNotNull(directory);
    byte[] image = image();
    try (var indexing = new IndexingTestServer();
        var answers = new AnswerProtocolServer();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      http = client;
      var defaults = new LinkedHashMap<String, Object>(answers.environment());
      defaults.put("RAG_EMBEDDING_BASE_URL", indexing.endpoint().toString());
      defaults.put("RAG_EMBEDDING_MODEL", "fixture-model");
      defaults.put("RAG_EMBEDDING_API_KEY", "synthetic-model-credential");
      defaults.put("RAG_MILVUS_ENDPOINT", indexing.endpoint().toString());
      defaults.put("RAG_MILVUS_TOKEN", "synthetic-projection-credential");
      defaults.put("RAG_MILVUS_COLLECTION", indexing.settings().projection().collection());
      defaults.put("RAG_TEXT_DEADLINE_MS", "10000");
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment
          .getPropertySources()
          .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      var application = new SpringApplication(RagApplication.class);
      application.setEnvironment(environment);
      application.setDefaultProperties(defaults);
      Path data = directory.resolve("data");
      try (var context =
          application.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + data,
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.import-auto-index.enabled=false",
              "--rag.answers.enabled=true",
              "--rag.document-removal.enabled=true",
              "--rag.image-ocr.enabled=true",
              "--rag.image-ocr.executable=" + ocrExecutable(),
              "--rag.image-ocr.language=eng",
              "--rag.image-ocr.revision=" + ocrRevision(),
              "--rag.ingestion.parse-timeout-ms=15000",
              "--rag.indexing.timeout-ms=15000",
              "--rag.answers.timeout-ms=15000")) {
        assertEquals(data, context.getBean(RagSettings.class).dataDirectory());
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var upload =
            json(
                "POST",
                "/v1/documents?filename=budget.png",
                image,
                "application/octet-stream",
                202);
        String doc = upload.path("document_id").asString();
        String revision = upload.path("revision_id").asString();
        await("ingestions", upload.path("task_id").asString(), "parsed");
        var row = json("GET", "/v1/management/documents", null, null, 200).path("items").get(0);
        assertEquals("image", row.path("document_type").asString());
        assertTrue(row.path("active_revision_id").isNull());
        var index = json("POST", "/v1/documents/" + doc + "/index", null, null, 202);
        await("indexings", index.path("task_id").asString(), "indexed");
      }
    }
  }

  static byte[] image() throws Exception {
    var image = new BufferedImage(1200, 180, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 52));
      graphics.drawString(TEXT, 30, 105);
    } finally {
      graphics.dispose();
    }
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", output));
    return output.toByteArray();
  }

  private void await(String resource, String id, String state) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < until) {
      var task = json("GET", "/v1/" + resource + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(task.path("state").asString())) {
        assertEquals(state, task.path("state").asString(), task.toString());
        return;
      }
      Thread.sleep(40);
    }
    fail("Image task did not finish");
  }

  private JsonNode json(String method, String path, byte[] body, String type, int status)
      throws Exception {
    var response = request(method, path, body, type, "image-owner");
    assertEquals(
        status,
        response.statusCode(),
        new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private HttpResponse<byte[]> request(
      String method, String path, byte[] body, String type, String actor) throws Exception {
    return request(method, path, body, type, "org-main", actor);
  }

  private HttpResponse<byte[]> request(
      String method, String path, byte[] body, String type, String workspace, String actor)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("X-Workspace-Id", workspace)
            .header("X-Principal-Id", actor)
            .header("Origin", base);
    if (type != null) {
      request.header("Content-Type", type);
    }
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
