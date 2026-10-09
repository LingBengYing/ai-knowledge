package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.evidence.rag.RagApplication;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.service.IngestionService;
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
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real Spring/SQLite/jobs and PDF pages; declared OCR/model/vector protocol fixtures. */
class PdfOcrMainlineHttpTest {
  @TempDir Path directory;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private String base;
  private HttpClient http;

  protected Path ocrExecutable() throws Exception {
    Path executable = directory.resolve("pdf-ocr-fixture");
    String counter = directory.resolve("calls").toString();
    String header =
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";
    String root = "1\t1\t0\t0\t0\t0\t0\t0\t1224\t1584\t-1\t\n";
    String pageTwo = words("Project A's budget is 650 USD.");
    String pageThree = words("Project C's budget is 810 USD. Project B's budget is 470 USD.");
    String quotedCounter = "'" + counter.replace("'", "'\"'\"'") + "'";
    Files.writeString(
        executable,
        "#!/bin/sh\n[ \"$7\" = tsv ] || exit 2\n/bin/cat >/dev/null\nn=0\n"
            + "[ ! -f "
            + quotedCounter
            + " ] || n=$(/bin/cat "
            + quotedCounter
            + ")\n"
            + "n=$((n+1))\nprintf '%s' \"$n\" >"
            + quotedCounter
            + "\n"
            + "/bin/cat <<'TSV'\n"
            + header
            + root
            + "TSV\n"
            + "case \"$n\" in\n2) /bin/cat <<'TSV'\n"
            + pageTwo
            + "TSV\n;;\n"
            + "3) /bin/cat <<'TSV'\n"
            + pageThree
            + "TSV\n;;\nesac\n");
    assertTrue(executable.toFile().setExecutable(true));
    return executable;
  }

  private static String words(String text) {
    var words = new StringBuilder();
    int n = 0;
    for (String word : text.split(" ")) {
      words
          .append("5\t1\t1\t1\t1\t")
          .append(++n)
          .append("\t")
          .append(n * 60)
          .append("\t100\t50\t30\t99\t")
          .append(word)
          .append('\n');
    }
    return words.toString();
  }

  protected String ocrRevision() {
    return "synthetic-pdf-v1";
  }

  @Test
  void everyOriginalPageTravelsThroughOcrPersistenceIndexAnswerAndOriginalPdf() throws Exception {
    byte[] pdf = pdf();
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
      try (var context =
          application.run(
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--rag.environment=test",
              "--rag.workspace-id=org-main",
              "--rag.auth-mode=development_headers",
              "--rag.data-directory=" + directory.resolve("data"),
              "--rag.ingestion.enabled=true",
              "--rag.indexing.enabled=true",
              "--rag.import-auto-index.enabled=false",
              "--rag.answers.enabled=true",
              "--rag.pdf-ocr.enabled=true",
              "--rag.pdf-ocr.executable=" + ocrExecutable(),
              "--rag.pdf-ocr.language=eng",
              "--rag.pdf-ocr.revision=" + ocrRevision(),
              "--rag.ingestion.parse-timeout-ms=30000",
              "--rag.indexing.timeout-ms=15000",
              "--rag.answers.timeout-ms=15000")) {
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        var upload =
            json(
                "POST",
                "/v1/documents?filename=synthetic-scan.pdf",
                pdf,
                "application/octet-stream",
                202);
        String doc = upload.path("document_id").asString(),
            revision = upload.path("revision_id").asString();
        await("ingestions", upload.path("task_id").asString(), "parsed");
        var parsed =
            context
                .getBean(IngestionService.class)
                .parsedEvidence(new Actor("org-main", "pdf-owner"), doc);
        assertEquals(3, parsed.pages().size());
        assertTrue(parsed.pages().get(0).text().isBlank());
        assertTrue(
            parsed.pages().get(1).text().contains("650 USD"),
            "Image-only original page 2 must be recognized");
        assertTrue(
            parsed.pages().get(2).text().contains("470 USD"),
            "Mixed tail page must include its embedded scan");
        assertTrue(
            json("GET", "/v1/config", null, null, 200)
                .path("capabilities")
                .toString()
                .contains("pdf_ocr_upload"));
        var index = json("POST", "/v1/documents/" + doc + "/index", null, null, 202);
        await("indexings", index.path("task_id").asString(), "indexed");
        assertTrue(
            parsed.pages().get(2).text().contains("810 USD"),
            "Mixed page must also retain its visible text layer");
        for (String project : List.of("A", "B", "C")) {
          var answer =
              json(
                  "POST",
                  "/v1/answers",
                  JSON.writeValueAsBytes(
                      Map.of(
                          "question",
                          "What is Project " + project + "'s budget?",
                          "document_ids",
                          List.of(doc))),
                  "application/json",
                  200);
          assertEquals("answered", answer.path("status").asString(), answer.toString());
          assertTrue(
              answer
                  .path("answer")
                  .asString()
                  .contains(Map.of("A", "650", "B", "470", "C", "810").get(project)),
              answer.toString());
          var citation = answer.path("citations").get(0);
          assertEquals(doc, citation.path("document_id").asString());
          assertEquals(revision, citation.path("revision_id").asString());
          assertEquals(ModelValues.sha256(pdf), citation.path("source_sha256").asString());
          assertTrue(
              citation.path("parser_revision").asString().matches("java-pdf-ocr-v1:[0-9a-f]{64}"));
          assertEquals(project.equals("A") ? 2 : 3, citation.path("page").asInt());
          var source = json("GET", citation.path("source_url").asString(), null, null, 200);
          assertEquals(citation, source.path("citation"));
          assertTrue(source.path("image").isMissingNode());
          var sharedSource =
              request("GET", citation.path("source_url").asString(), null, null, "other-owner");
          assertEquals(200, sharedSource.statusCode());
          assertEquals(source, JSON.readTree(sharedSource.body()));
        }
        var original = json("GET", "/v1/documents/" + doc + "/original", null, null, 200);
        assertEquals(revision, original.path("revision_id").asString());
        assertEquals(ModelValues.sha256(pdf), original.path("source_sha256").asString());
        var binary =
            request("GET", original.path("content_url").asString(), null, null, "pdf-owner");
        assertEquals(200, binary.statusCode());
        assertArrayEquals(pdf, binary.body());
        assertEquals("application/pdf", binary.headers().firstValue("Content-Type").orElseThrow());
        String contentUrl = original.path("content_url").asString();
        var sharedOriginal = request("GET", contentUrl, null, null, "other-owner");
        assertEquals(200, sharedOriginal.statusCode());
        assertArrayEquals(pdf, sharedOriginal.body());
        assertEquals(422, request("GET", contentUrl, null, null, null).statusCode());
        assertEquals(
            401, request("GET", contentUrl, null, null, "other-org", "pdf-owner").statusCode());
      }
    }
  }

  static byte[] pdf() throws Exception {
    try (var document = new PDDocument();
        var output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      for (String text :
          List.of("Project A's budget is 650 USD.", "Project B's budget is 470 USD.")) {
        var page = new PDPage();
        document.addPage(page);
        var image = new BufferedImage(1200, 180, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1200, 180);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 52));
        g.drawString(text, 30, 105);
        g.dispose();
        try (var content = new PDPageContentStream(document, page)) {
          content.drawImage(LosslessFactory.createFromImage(document, image), 20, 600, 570, 86);
          if (document.getNumberOfPages() == 3) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 22);
            content.newLineAtOffset(30, 730);
            content.showText("Project C's budget is 810 USD.");
            content.endText();
          }
        }
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private void await(String resource, String id, String state) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(35).toNanos();
    while (System.nanoTime() < end) {
      var task = json("GET", "/v1/" + resource + "/" + id, null, null, 200);
      if (!List.of("queued", "processing").contains(task.path("state").asString())) {
        assertEquals(state, task.path("state").asString(), task.toString());
        return;
      }
      Thread.sleep(40);
    }
    fail("PDF task did not finish");
  }

  private JsonNode json(String method, String path, byte[] body, String type, int status)
      throws Exception {
    var response = request(method, path, body, type, "pdf-owner");
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
            .timeout(Duration.ofSeconds(35))
            .header("Origin", base);
    if (actor != null) {
      request.header("X-Workspace-Id", workspace).header("X-Principal-Id", actor);
    }
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
