package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.client.model.GeminiSoundEmbeddingModels;
import com.evidence.rag.client.model.GeminiSoundModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.vector.MilvusRestProjection;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.repository.SoundRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.SoundCompilationService;
import com.evidence.rag.service.SoundLibraryService;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.AudioDecoder;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class SoundUploadServletTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final byte[] WAV = AudioPcm.wav(new byte[6], 0, 6);
  @TempDir Path directory;

  @Test
  void encodedUnicodePlusAndPercentNamesAreDecodedExactlyOnceAndStoredWithoutModelCalls()
      throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler()) {
      var library = library(store, compiler);
      for (String[] names :
          new String[][] {
            {"%E5%A3%B0%E9%9F%B3.wav", "声音.wav"},
            {"bell+tone.wav", "bell+tone.wav"},
            {"percent%2520.wav", "percent%20.wav"}
          }) {
        var response = upload(library, request(names[0]));
        assertEquals(201, response.getStatus(), response.getContentAsString());
        var body = JSON.readTree(response.getContentAsString());
        assertEquals(
            Set.of("document_id", "source_revision_id", "source_sha256", "size_bytes"),
            new HashSet<>(body.propertyNames()));
        assertEquals(WAV.length, body.path("size_bytes").intValue());
        var state = library.get(OWNER, body.path("document_id").asString());
        assertEquals(names[1], state.original().filename());
        assertEquals("audio/wav", state.original().mediaType());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertFalse(response.getContentAsString().contains("filename"));
      }
    }
  }

  @Test
  void malformedPercentUtf8DuplicateNamesAndQueriesAreRejectedBeforeStorage() throws Exception {
    try (var store = new SqliteAuthorityStore(directory);
        var compiler = compiler()) {
      var library = library(store, compiler);
      for (String name : List.of("bad%.wav", "bad%GG.wav", "%FF.wav", "%E5%A3.wav")) {
        var response = upload(library, request(name));
        assertEquals(422, response.getStatus(), name);
        assertEquals(
            "invalid_request",
            JSON.readTree(response.getContentAsString()).path("error_code").asString());
      }
      // Valid percent encoding reaches AudioInput, which rejects path names as unsupported media.
      var unsupportedName = upload(library, request("a%2Fb.wav"));
      assertEquals(415, unsupportedName.getStatus());
      assertEquals(
          "unsupported_document",
          JSON.readTree(unsupportedName.getContentAsString()).path("error_code").asString());
      var duplicate = request("bell.wav");
      duplicate.addHeader("X-Filename", "other.wav");
      assertEquals(422, upload(library, duplicate).getStatus());
      var query = request("bell.wav");
      query.setQueryString("");
      assertEquals(422, upload(library, query).getStatus());
      long documents = count("SELECT COUNT(*) FROM documents");
      long originals = count("SELECT COUNT(*) FROM sound_originals");
      assertEquals(0L, documents);
      assertEquals(0L, originals);
    }
  }

  @Test
  void methodAuthenticationMimeAndSizeStayAtTheUploadBoundary() throws Exception {
    var request = request("bell.wav");
    request.setMethod("GET");
    assertEquals(405, upload(null, request).getStatus());
    request = request("bell.wav");
    request.removeAttribute(AuthenticatedActor.class.getName());
    assertEquals(401, upload(null, request).getStatus());
    request = request("bell.wav");
    request.setContentType("audio/wav");
    assertEquals(415, upload(null, request).getStatus());
    request = request("bell.wav");
    request.setContent(new byte[20 * 1024 * 1024 + 1]);
    assertEquals(413, upload(null, request).getStatus());
  }

  private long count(String sql) throws SQLException {
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("java-library.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static SoundCompilationService compiler() {
    return new SoundCompilationService(
        new AudioDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public DecodedAudio decode(String filename, String mime, byte[] source) {
            throw new AssertionError("Upload and metadata reads must not decode");
          }

          public void close() {}
        },
        15,
        Duration.ofSeconds(5));
  }

  private static SoundLibraryService library(
      SqliteAuthorityStore store, SoundCompilationService compiler) {
    var endpoint = new Endpoint(URI.create("http://127.0.0.1:1"), "sound-fixture", "fixture-key");
    var models =
        new GeminiSoundModels.Configuration(
            endpoint, "sound-v1", Duration.ofSeconds(5), 65536, true);
    var embedding =
        new GeminiSoundEmbeddingModels.Configuration(
            endpoint,
            "embedding-v1",
            2,
            compiler.decoderRevision(),
            Duration.ofSeconds(5),
            65536,
            true);
    var projection =
        new MilvusRestProjection.Settings(
            endpoint.baseUrl(),
            "",
            "default",
            "java_sound_fixture",
            "org",
            embedding.revision(),
            2,
            Duration.ofSeconds(5),
            65536,
            true);
    var target =
        new IndexTarget(embedding.revision(), projection.identity(), embedding.revision(), 2);
    return new SoundLibraryService(
        store,
        new SoundRepository(store),
        new ManagementRepository(store),
        new DocumentPermissionPolicy(),
        compiler,
        target,
        models,
        embedding,
        projection,
        Duration.ofSeconds(5),
        2);
  }

  private static MockHttpServletResponse upload(
      SoundLibraryService library, MockHttpServletRequest request) throws IOException {
    var response = new MockHttpServletResponse();
    new SoundUploadServlet(library, JSON, new ProblemHandler()).service(request, response);
    return response;
  }

  private static MockHttpServletRequest request(String encodedFilename) {
    var input =
        new ServletInputStream() {
          private final ByteArrayInputStream bytes = new ByteArrayInputStream(WAV);

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
        new MockHttpServletRequest("POST", "/v1/sound-documents") {
          @Override
          public ServletInputStream getInputStream() {
            return input;
          }
        };
    request.setAsyncSupported(true);
    request.setContent(WAV);
    request.setContentType("application/octet-stream");
    request.addHeader("X-Filename", encodedFilename);
    request.setAttribute(AuthenticatedActor.class.getName(), OWNER);
    return request;
  }
}
