package com.evidence.rag.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.DerivedSynopsisNode;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import com.evidence.rag.model.domain.VisualImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleHierarchicalSynopsisModelsTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DRAFT =
      "{\"refused\":false,\"items\":[{\"section\":\"topic\",\"text\":\"设备需要维修。\",\"evidence_ids\":[\"text-1\"]}]}";

  @Test
  void leafReceivesActualBatchAndTrailingOriginalWithoutPretendingItIsTheWholePublication()
      throws Exception {
    try (var fixture = new Fixture()) {
      var image = image(2, 2);
      var batch =
          batch(
              List.of(
                  text("text-1", "设备需要维修。\n末尾条件：仅试运行时。😀"),
                  new SynopsisEvidence(
                      "frame-1",
                      SynopsisEvidence.Kind.VIDEO_FRAME,
                      new SynopsisEvidence.Image(image),
                      new SynopsisEvidence.TimeRange(8000000, 8200000))));
      var draft = fixture.models.draftLeaf(batch);
      assertEquals(List.of("text-1"), draft.items().getFirst().evidenceIds());
      var request = fixture.take();
      var data = data(request);
      assertEquals("leaf", data.path("operation").asString());
      assertEquals(64, data.path("batch").path("from_ordinal").asInt());
      assertEquals(66, data.path("batch").path("end_ordinal").asInt());
      assertEquals(100, data.path("batch").path("total_evidence_count").asInt());
      assertEquals(
          ((SynopsisEvidence.Text) batch.evidence().getFirst().content()).text(),
          data.path("evidence").get(0).path("text").asString());
      assertImage(request, 1, image);
      assertFalse(data.toString().contains("8200000"));
      String prompt = request.path("messages").get(0).path("content").asString();
      assertTrue(prompt.contains("untrusted data"));
      assertTrue(prompt.contains("partial batch"));
      assertFalse(prompt.contains("末尾条件"));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void reductionSendsAllThreeTypedDerivedNodesAndOnlyAllowsTheirOriginalLineage() throws Exception {
    try (var fixture = new Fixture()) {
      var nodes =
          List.of(
              node("node-1", 0, 32, "text-1"),
              node("node-2", 32, 64, "text-2"),
              node("node-3", 64, 100, "text-3"));
      var reduction =
          new SynopsisReductionInput(
              publication(), "c".repeat(64), SynopsisReductionInput.Stage.FINAL, nodes);
      fixture.content.set(DRAFT.replace("text-1", "text-3"));
      assertEquals(
          "text-3", fixture.models.reduce(reduction).items().getFirst().evidenceIds().getFirst());
      var request = fixture.take();
      var data = data(request);
      assertEquals("reduce", data.path("operation").asString());
      assertEquals("final", data.path("stage").asString());
      assertFalse(
          data.has("evidence"), "Derived proposals must never be encoded as original evidence");
      assertEquals(3, data.path("derived_nodes").size());
      for (int i = 0; i < nodes.size(); i++) {
        var row = data.path("derived_nodes").get(i);
        assertEquals(nodes.get(i).id(), row.path("node_id").asString());
        assertEquals(nodes.get(i).fromOrdinal(), row.path("from_ordinal").asInt());
        assertEquals(nodes.get(i).endOrdinal(), row.path("end_ordinal").asInt());
        assertEquals(
            nodes.get(i).items().getFirst().text(),
            row.path("items").get(0).path("text").asString());
      }
      assertEquals(1, request.path("messages").get(1).path("content").size());
      assertTrue(
          request
              .path("messages")
              .get(0)
              .path("content")
              .asString()
              .contains("not original evidence"));
      for (String id : List.of("outside-1", "node-3")) {
        fixture.content.set(DRAFT.replace("text-1", id));
        failure("model_invalid_response", () -> fixture.models.reduce(reduction));
        fixture.take();
      }
    }
  }

  @Test
  void leafAndIntermediateHaveSixteenItemLimitWhileFinalAcceptsThirtyTwo() throws Exception {
    try (var fixture = new Fixture()) {
      var batch = batch(List.of(text("text-1", "设备需要维修。")));
      var node = node("node-1", 0, 100, "text-1");
      fixture.content.set(draft(16));
      assertEquals(16, fixture.models.draftLeaf(batch).items().size());
      fixture.take();
      fixture.content.set(draft(17));
      failure("model_invalid_response", () -> fixture.models.draftLeaf(batch));
      fixture.take();
      var intermediate =
          new SynopsisReductionInput(
              publication(),
              "c".repeat(64),
              SynopsisReductionInput.Stage.INTERMEDIATE,
              List.of(node));
      failure("model_invalid_response", () -> fixture.models.reduce(intermediate));
      fixture.take();
      var terminal =
          new SynopsisReductionInput(
              publication(), "c".repeat(64), SynopsisReductionInput.Stage.FINAL, List.of(node));
      fixture.content.set(draft(32));
      assertEquals(32, fixture.models.reduce(terminal).items().size());
      fixture.take();
      fixture.content.set(draft(33));
      failure("model_invalid_response", () -> fixture.models.reduce(terminal));
      fixture.take();
    }
  }

  @Test
  void reviewReopensEveryFinalClaimAgainstOriginalTailAndOrdersAllExplicitJudgments()
      throws Exception {
    try (var fixture = new Fixture()) {
      var batch = batch(List.of(text("tail-1", "撤销前文授权。仅试运行允许。")));
      var items = List.of(item("text-1", "允许使用。"), item("text-2", "生产环境也允许。"));
      fixture.content.set(
          "{\"complete\":false,\"items\":[{\"index\":1,\"compatible\":false},{\"index\":0,\"compatible\":true}]}");
      var review = fixture.models.review(batch, items);
      assertFalse(review.complete());
      assertEquals(List.of(0, 1), review.items().stream().map(value -> value.index()).toList());
      assertEquals(
          List.of(true, false), review.items().stream().map(value -> value.compatible()).toList());
      var request = fixture.take();
      var data = data(request);
      assertEquals("review", data.path("operation").asString());
      assertEquals("撤销前文授权。仅试运行允许。", data.path("evidence").get(0).path("text").asString());
      assertEquals(2, data.path("items").size());
      for (int i = 0; i < items.size(); i++) {
        assertEquals(i, data.path("items").get(i).path("index").asInt());
        assertEquals(items.get(i).text(), data.path("items").get(i).path("text").asString());
      }
      var prompt = request.path("messages").get(0).path("content").asString();
      assertTrue(prompt.contains("contradictions"));
      assertTrue(prompt.contains("conditions"));
      assertTrue(prompt.contains("uncertain"));
      assertTrue(prompt.contains("important content"));
    }
  }

  @Test
  void reviewRejectsMissingDuplicateUnknownAndCoercedJudgmentsRatherThanSilentlyDroppingTheTail()
      throws Exception {
    try (var fixture = new Fixture()) {
      var batch = batch(List.of(text("tail-1", "条件只适用于测试。")));
      var items = List.of(item("text-1", "测试可用。"), item("text-2", "生产可用。"));
      for (String response :
          List.of(
              "{\"complete\":true,\"items\":[{\"index\":0,\"compatible\":true}]}",
              "{\"complete\":true,\"items\":[{\"index\":0,\"compatible\":true},{\"index\":0,\"compatible\":true}]}",
              "{\"complete\":true,\"items\":[{\"index\":0,\"compatible\":true},{\"index\":2,\"compatible\":true}]}",
              "{\"complete\":true,\"items\":[{\"index\":0,\"compatible\":true},{\"index\":1,\"compatible\":\"true\"}]}",
              "{\"complete\":\"true\",\"items\":[]}",
              "{\"complete\":true,\"items\":[],\"confidence\":1}")) {
        fixture.content.set(response);
        failure("model_invalid_response", () -> fixture.models.review(batch, items));
        fixture.take();
        assertTrue(fixture.requests.isEmpty());
      }
    }
  }

  @Test
  void finalVerificationRequiresEveryCitedOriginalAndDoesNotUseDerivedCandidates()
      throws Exception {
    try (var fixture = new Fixture()) {
      var evidence = List.of(text("text-1", "设备需维修。"), text("text-2", "维修后等待五秒。"));
      var item =
          new SynopsisDraft.Item(
              SynopsisDraft.Section.TOPIC, "维修设备后等待五秒。", List.of("text-1", "text-2"));
      fixture.content.set(
          "{\"supported\":true,\"contributing_evidence_ids\":[\"text-2\",\"text-1\"]}");
      assertTrue(fixture.models.verify(item, evidence));
      var data = data(fixture.take());
      assertEquals("verify", data.path("operation").asString());
      assertEquals(item.text(), data.path("statement").asString());
      assertFalse(data.has("derived_nodes"));
      assertEquals(2, data.path("evidence").size());
      fixture.content.set("{\"supported\":true,\"contributing_evidence_ids\":[\"text-1\"]}");
      failure("model_invalid_response", () -> fixture.models.verify(item, evidence));
      fixture.take();
      fixture.content.set("{\"supported\":false,\"contributing_evidence_ids\":[]}");
      assertFalse(fixture.models.verify(item, evidence));
      fixture.take();
      failure("model_invalid_input", () -> fixture.models.verify(item, evidence.subList(0, 1)));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void acceptsOneActualOriginalImageAboveEightMiBWithoutChangingOrResizingItsBytes()
      throws Exception {
    var image = image(1800, 1800);
    assertTrue(image.content().length > 8 * 1024 * 1024);
    assertTrue(image.content().length <= 10 * 1024 * 1024);
    var evidence =
        new SynopsisEvidence(
            "image-large", SynopsisEvidence.Kind.IMAGE, new SynopsisEvidence.Image(image), null);
    try (var fixture = new Fixture()) {
      fixture.content.set("{\"supported\":true,\"contributing_evidence_ids\":[\"image-large\"]}");
      assertTrue(fixture.models.verify(item("image-large", "彩色像素。"), List.of(evidence)));
      assertImage(fixture.take(), 1, image);
    }
  }

  @Test
  void rejectsInventedLocatorsUnknownIdsInvalidJsonAndTruncatedCompletionsWithoutRetry()
      throws Exception {
    try (var fixture = new Fixture()) {
      var batch = batch(List.of(text("text-1", "设备需维修。")));
      for (String response :
          List.of(
              DRAFT.replace("text-1", "unknown"),
              DRAFT.replace("\"text-1\"]", "\"text-1\",\"text-1\"]"),
              DRAFT.replace("\"section\":\"topic\"", "\"section\":\"topic\",\"page\":1"),
              DRAFT.replace("设备需要维修。", "x".repeat(1025)),
              DRAFT + "{}",
              "{\"refused\":false,\"items\":[]}")) {
        fixture.content.set(response);
        failure("model_invalid_response", () -> fixture.models.draftLeaf(batch));
        fixture.take();
      }
      fixture.content.set(DRAFT);
      for (String finish : List.of("length", "tool_calls")) {
        fixture.finish.set(finish);
        failure("model_invalid_response", () -> fixture.models.draftLeaf(batch));
        fixture.take();
      }
      fixture.finish.set("stop");
      fixture.tool.set(true);
      failure("model_invalid_response", () -> fixture.models.draftLeaf(batch));
      fixture.take();
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void nullInputsPreexistingInterruptionAndClosedAdapterDoNotSendRequests() throws Exception {
    try (var fixture = new Fixture()) {
      failure("model_invalid_input", () -> fixture.models.draftLeaf(null));
      failure("model_invalid_input", () -> fixture.models.reduce(null));
      failure(
          "model_invalid_input",
          () -> fixture.models.review(batch(List.of(text("text-1", "内容。"))), List.of()));
      failure("model_invalid_input", () -> fixture.models.verify(null, List.of()));
      var batch = batch(List.of(text("text-1", "内容。")));
      try {
        Thread.currentThread().interrupt();
        failure("model_interrupted", () -> fixture.models.draftLeaf(batch));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      fixture.models.close();
      failure("model_closed", () -> fixture.models.draftLeaf(batch));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void separateRevisionBindsEveryNewProtocolWithoutIncludingSecretsOrAlteringOldAdapter()
      throws Exception {
    try (var fixture = new Fixture();
        var old = new OpenAiCompatibleSynopsisModels(fixture.configuration);
        var rekeyed =
            new OpenAiCompatibleHierarchicalSynopsisModels(
                configuration(
                    new OpenAiCompatibleModels.Endpoint(
                        fixture.endpoint.baseUrl(),
                        fixture.endpoint.model(),
                        UUID.randomUUID().toString())));
        var other =
            new OpenAiCompatibleHierarchicalSynopsisModels(
                configuration(
                    new OpenAiCompatibleModels.Endpoint(
                        fixture.endpoint.baseUrl(), "other-model", fixture.endpoint.apiKey())))) {
      assertTrue(old.revision().startsWith("java-synopsis-models-v1-"));
      assertTrue(fixture.models.revision().matches("java-hierarchical-synopsis-v1-[a-f0-9]{64}"));
      assertEquals(fixture.models.revision(), rekeyed.revision());
      assertNotEquals(fixture.models.revision(), other.revision());
      assertNotEquals(fixture.models.revision(), old.revision());
      assertFalse(fixture.models.revision().contains(fixture.endpoint.apiKey()));
      failure(
          "model_invalid_configuration",
          () -> new OpenAiCompatibleHierarchicalSynopsisModels(null));
      assertTrue(fixture.requests.isEmpty());
    }
  }

  private static SynopsisEvidence text(String id, String text) {
    return new SynopsisEvidence(
        id, SynopsisEvidence.Kind.TEXT, new SynopsisEvidence.Text(text), null);
  }

  private static SynopsisDraft.Item item(String id, String text) {
    return new SynopsisDraft.Item(SynopsisDraft.Section.TOPIC, text, List.of(id));
  }

  private static SynopsisBatch batch(List<SynopsisEvidence> evidence) {
    return new SynopsisBatch(publication(), "c".repeat(64), 64, evidence);
  }

  private static DerivedSynopsisNode node(String id, int from, int end, String evidence) {
    return new DerivedSynopsisNode(id, from, end, List.of(item(evidence, "派生候选，不是原文。")));
  }

  private static PublicationVersion publication() {
    return new PublicationVersion(
        "doc",
        "pub",
        "revision",
        "generation",
        "a".repeat(64),
        "parser-v1",
        new IndexTarget("embedding", "projection", "model-v1", 2),
        "b".repeat(64),
        100);
  }

  private static String draft(int count) {
    return JSON.writeValueAsString(
        Map.of(
            "refused",
            false,
            "items",
            IntStream.range(0, count)
                .mapToObj(
                    i ->
                        Map.of(
                            "section",
                            "topic",
                            "text",
                            "条目" + i,
                            "evidence_ids",
                            List.of("text-1")))
                .toList()));
  }

  private static VisualImage image(int width, int height) throws IOException {
    var original = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var random = new Random(914);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        original.setRGB(x, y, random.nextInt(0x1000000));
      }
    }
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      assertTrue(ImageIO.write(original, "png", output));
    }
    return new VisualImage("image/png", bytes.toByteArray());
  }

  private static JsonNode data(JsonNode request) {
    return JSON.readTree(
        request.path("messages").get(1).path("content").get(0).path("text").asString());
  }

  private static void assertImage(JsonNode request, int index, VisualImage image) {
    var part = request.path("messages").get(1).path("content").get(index);
    assertEquals("image_url", part.path("type").asString());
    assertEquals("high", part.path("image_url").path("detail").asString());
    String prefix = "data:" + image.mediaType() + ";base64,";
    String url = part.path("image_url").path("url").asString();
    assertTrue(url.startsWith(prefix));
    assertArrayEquals(image.content(), Base64.getDecoder().decode(url.substring(prefix.length())));
  }

  private static void failure(String code, Executable operation) {
    var failure = assertThrows(TextModels.Failure.class, operation);
    assertEquals(code, failure.code());
    assertEquals("模型调用或配置未通过安全校验。", failure.getMessage());
  }

  private static OpenAiCompatibleSynopsisModels.Configuration configuration(
      OpenAiCompatibleModels.Endpoint endpoint) {
    return new OpenAiCompatibleSynopsisModels.Configuration(
        endpoint, Duration.ofSeconds(10), 65536, true);
  }

  private static final class Fixture implements AutoCloseable {
    final LinkedBlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    final AtomicReference<String> content = new AtomicReference<>(DRAFT);
    final AtomicReference<String> finish = new AtomicReference<>("stop");
    final java.util.concurrent.atomic.AtomicBoolean tool =
        new java.util.concurrent.atomic.AtomicBoolean();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final HttpServer server;
    final OpenAiCompatibleModels.Endpoint endpoint;
    final OpenAiCompatibleSynopsisModels.Configuration configuration;
    final OpenAiCompatibleHierarchicalSynopsisModels models;

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/v1/chat/completions", this::respond);
      server.start();
      endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "synthetic-hierarchy",
              UUID.randomUUID().toString());
      configuration = configuration(endpoint);
      models = new OpenAiCompatibleHierarchicalSynopsisModels(configuration);
    }

    JsonNode take() {
      var request = requests.poll();
      assertNotNull(request);
      assertEquals("synthetic-hierarchy", request.path("model").asString());
      assertEquals("json_object", request.path("response_format").path("type").asString());
      assertFalse(request.path("stream").asBoolean());
      assertEquals(1, request.path("n").asInt());
      return request;
    }

    void respond(HttpExchange exchange) throws IOException {
      requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
      var message = new java.util.LinkedHashMap<String, Object>();
      message.put("role", "assistant");
      message.put("content", content.get());
      if (tool.get()) {
        message.put("tool_calls", List.of());
      }
      byte[] response =
          JSON.writeValueAsBytes(
              Map.of(
                  "choices",
                  List.of(Map.of("index", 0, "finish_reason", finish.get(), "message", message))));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    }

    @Override
    public void close() {
      models.close();
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
