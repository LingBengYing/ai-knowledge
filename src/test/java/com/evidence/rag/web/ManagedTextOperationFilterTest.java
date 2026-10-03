package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.service.LegacyTextProfileGuard;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.VisualAnswerService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Actual admission protects media bodies without blocking independent OCR or configuration. */
class ManagedTextOperationFilterTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/visual-answers",
        "/v1/visual-sources/saved/1",
        "/v1/video-answers",
        "/v1/video-sources/saved/1",
        "/v1/attachment-answers",
        "/v1/documents/local/image-vector",
        "/v1/documents/local/audio-vector"
      })
  void dependentMediaCannotReachItsHandlerBeforeTextConfiguration(String path) throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var runtime = unconfigured(store)) {
      var filter = filter(store, runtime);
      var called = new AtomicBoolean();
      var response = new MockHttpServletResponse();
      filter.doFilter(request("POST", path), response, (req, result) -> called.set(true));
      assertFalse(called.get());
      assertEquals(503, response.getStatus());
      assertEquals("private, no-store", response.getHeader("Cache-Control"));
      assertEquals(
          "text_configuration_required",
          JsonMapper.builder()
              .build()
              .readTree(response.getContentAsString())
              .path("error_code")
              .asString());
      assertTrue(store.operationGate().isIdle(), "Denied media must release its own actual lease");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "CLIP.MP4,application/octet-stream",
    "clip.webm,image/png",
    "clip.mov,application/octet-stream",
    "clip.mkv,application/octet-stream",
    "notes.txt,video/mp4",
    ",Video/WebM"
  })
  void videoUploadChecksFilenameAndMimeBeforeDecoderOrProvider(String filename, String mime)
      throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var runtime = unconfigured(store)) {
      var request = request("POST", "/v1/documents");
      if (filename != null) {
        request.addParameter("filename", filename);
      }
      request.setContentType(mime);
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      filter(store, runtime).doFilter(request, response, (req, result) -> called.set(true));
      assertFalse(called.get(), "No upload handler, decoder, or model may execute");
      assertEquals(503, response.getStatus());
      assertTrue(response.getContentAsString().contains("text_configuration_required"));
      assertTrue(store.operationGate().isIdle());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "notes.txt,text/plain",
    "scan.png,image/png",
    "photo.jpg,application/octet-stream",
    "photo.jpeg,",
    "scan.webp,image/webp",
    ",text/plain",
    ","
  })
  void textAndOcrOnlyUploadAdmissionRemainsIndependentOfTextModels(String filename, String mime)
      throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var runtime = unconfigured(store)) {
      var request = request("POST", "/v1/documents");
      if (filename != null) {
        request.addParameter("filename", filename);
      }
      if (mime != null) {
        request.setContentType(mime);
      }
      var called = new AtomicBoolean();
      filter(store, runtime)
          .doFilter(
              request,
              new MockHttpServletResponse(),
              (req, result) -> {
                called.set(true);
                assertFalse(store.operationGate().isIdle());
              });
      assertTrue(called.get(), "The actual upload handler still owns format validation");
      assertTrue(store.operationGate().isIdle());
    }
  }

  @Test
  void listReadDoesNotBecomeAnUploadWhenItsQueryContainsAVideoName() throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var runtime = unconfigured(store)) {
      var request = request("GET", "/v1/documents");
      request.addParameter("filename", "clip.mp4");
      request.setContentType("video/mp4");
      var called = new AtomicBoolean();
      filter(store, runtime)
          .doFilter(request, new MockHttpServletResponse(), (req, result) -> called.set(true));
      assertTrue(called.get());
      assertTrue(store.operationGate().isIdle());
    }
  }

  @Test
  void activationCanAcquireMaintenanceWithoutCountingItsOwnHttpBody() throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var runtime = unconfigured(store)) {
      var called = new AtomicBoolean();
      filter(store, runtime)
          .doFilter(
              request("POST", "/v1/model-configuration/activate"),
              new MockHttpServletResponse(),
              (req, result) -> {
                called.set(true);
                try (var maintenance = store.operationGate().tryMaintenance().orElseThrow()) {
                  assertTrue(maintenance.isHeld());
                }
              });
      assertTrue(called.get());
      assertTrue(store.operationGate().isIdle());
    }
  }

  private static ManagedTextRuntime unconfigured(SqliteAuthorityStore store) {
    return new ManagedTextRuntime(
        store,
        (version, configuration) -> {
          throw new AssertionError("Admission must not build or invoke a model client");
        });
  }

  private static LibraryOperationFilter filter(
      SqliteAuthorityStore store, ManagedTextRuntime runtime) {
    var guard =
        new LegacyTextProfileGuard(
            runtime, new IndexTarget("embedding", "projection", "fixture-v1", 2));
    var beans = new StaticListableBeanFactory(Map.of("legacy", guard));
    return new LibraryOperationFilter(
        store.operationGate(),
        new ProblemHandler(),
        JsonMapper.builder().build(),
        beans.getBeanProvider(LegacyTextProfileGuard.class),
        beans.getBeanProvider(VisualAnswerService.class));
  }

  private static MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, "/kb" + path);
    request.setContextPath("/kb");
    return request;
  }
}
