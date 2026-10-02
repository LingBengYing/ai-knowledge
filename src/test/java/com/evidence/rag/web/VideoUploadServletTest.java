package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.IngestionService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class VideoUploadServletTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String AUDIO = "java-audio-compiler-v1:" + "a".repeat(64);
  private static final String VIDEO = "java-video-compiler-v1:" + "b".repeat(64);
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "film.mp4,video/mp4",
    "film.webm,video/webm",
    "film.mov,video/quicktime",
    "film.mkv,video/x-matroska"
  })
  void explicitSupportedVideoTypeReturnsOnlyTheExistingQueuedTaskShape(String filename, String mime)
      throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, VIDEO);
      var response = upload(service, request(filename, mime));
      assertEquals(202, response.getStatus(), response.getContentAsString());
      var body = JsonMapper.builder().build().readTree(response.getContentAsString());
      assertEquals("queued", body.path("state").asString());
      assertEquals(
          new HashSet<>(
              Set.of(
                  "task_id",
                  "document_id",
                  "revision_id",
                  "filename",
                  "state",
                  "status",
                  "attempt",
                  "error_code",
                  "created_at",
                  "updated_at",
                  "can_retry",
                  "can_cancel")),
          new HashSet<>(body.propertyNames()));
      var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
      assertEquals(body.path("task_id").asString(), claim.jobId());
      assertEquals(mime, claim.mimeType());
      assertEquals(VIDEO, claim.parserRevision());
    }
  }

  @Test
  void oldOctetStreamHttpUploadStillQueuesAudioOnlyContainerContract() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var service = service(store, VIDEO);
      for (String name : List.of("voice.mp4", "voice.webm")) {
        var response = upload(service, request(name, "application/octet-stream"));
        assertEquals(202, response.getStatus(), response.getContentAsString());
        var claim = service.claimIngestion(OWNER.workspaceId()).orElseThrow();
        assertEquals(AUDIO, claim.parserRevision());
        assertEquals(name.endsWith(".mp4") ? "audio/mp4" : "audio/webm", claim.mimeType());
      }
    }
  }

  @Test
  void disabledMismatchedAndNonUniqueVideoRequestsNeverQueueWork() throws Exception {
    try (var store = new SqliteAuthorityStore(directory)) {
      var disabled = service(store, null);
      assertEquals(422, upload(disabled, request("film.mp4", "video/mp4")).getStatus());
      assertTrue(disabled.claimIngestion(OWNER.workspaceId()).isEmpty());
      var enabled = service(store, VIDEO);
      assertEquals(422, upload(enabled, request("film.webm", "video/mp4")).getStatus());
      for (String unsupported : List.of("video/avi", "video/mp4; charset=utf-8", "audio/mp4")) {
        assertEquals(415, upload(enabled, request("film.mp4", unsupported)).getStatus());
      }
      var duplicate = request("film.mp4", "video/mp4", "video/webm");
      assertEquals(2, Collections.list(duplicate.getHeaders("Content-Type")).size());
      assertEquals(415, upload(enabled, duplicate).getStatus());
      var extraParameter = request("film.mp4", "video/mp4");
      extraParameter.addParameter("type", "video");
      assertEquals(422, upload(enabled, extraParameter).getStatus());
      assertTrue(enabled.claimIngestion(OWNER.workspaceId()).isEmpty());
    }
  }

  private static IngestionService service(SqliteAuthorityStore store, String video) {
    return new IngestionService(
        store,
        new IngestionRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        null,
        null,
        AUDIO,
        video);
  }

  private static MockHttpServletResponse upload(
      IngestionService authority, MockHttpServletRequest request) throws IOException {
    var response = new MockHttpServletResponse();
    new UploadServlet(authority, 5000, JsonMapper.builder().build(), new ProblemHandler())
        .service(request, response);
    return response;
  }

  private static MockHttpServletRequest request(String filename, String... contentTypes) {
    byte[] content =
        (filename.endsWith(".webm") || filename.endsWith(".mkv")
                ? "\u001aE\u00df\u00a3\u0000\u0000\u0000\u0000"
                : "\u0000\u0000\u0000\u0010ftypisom\u0000\u0000\u0000\u0000")
            .getBytes(StandardCharsets.ISO_8859_1);
    var input =
        new ServletInputStream() {
          private final ByteArrayInputStream bytes = new ByteArrayInputStream(content);

          public boolean isFinished() {
            return bytes.available() == 0;
          }

          public boolean isReady() {
            return true;
          }

          public int read() {
            return bytes.read();
          }

          public void setReadListener(ReadListener listener) {
            try {
              listener.onDataAvailable();
              listener.onAllDataRead();
            } catch (IOException failure) {
              listener.onError(failure);
            }
          }
        };
    var request =
        new MockHttpServletRequest("POST", "/v1/documents") {
          @Override
          public ServletInputStream getInputStream() {
            return input;
          }

          @Override
          public Enumeration<String> getHeaders(String name) {
            // Spring's mock treats Content-Type as a single-valued setter; preserve actual header
            // multiplicity here.
            return "Content-Type".equalsIgnoreCase(name)
                ? Collections.enumeration(List.of(contentTypes))
                : super.getHeaders(name);
          }
        };
    request.setAsyncSupported(true);
    request.setContent(content);
    request.addParameter("filename", filename);
    request.addHeader("Content-Type", contentTypes[0]);
    request.setAttribute(AuthenticatedActor.class.getName(), OWNER);
    return request;
  }
}
