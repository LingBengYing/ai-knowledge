package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.entity.ModelRebuildEntity;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.ModelRebuildRepository;
import com.evidence.rag.repository.TextRuntimeSelectionRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.ModelRebuildService;
import com.evidence.rag.support.AuthorityTestContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ModelRebuildMutationFilterTest {
  private static final String NOW = "2026-10-08T00:00:00Z";
  @TempDir Path directory;

  @Test
  void globalPendingRebuildBlocksExistingMutationRoutesThroughTheService() throws Exception {
    try (var fixture = new Fixture(directory)) {
      assertFalse(fixture.service.mutationsBlocked());
      fixture.queue();
      assertTrue(fixture.service.mutationsBlocked());
      for (String path :
          List.of(
              "/v1/documents",
              "/v1/sound-documents",
              "/v1/video-av-documents",
              "/v1/management/document-cleanups",
              "/v1/documents/doc/index",
              "/v1/documents/doc/reindex",
              "/v1/documents/doc/replacement",
              "/v1/documents/doc/replacement/index",
              "/v1/documents/doc/cleanup",
              "/v1/documents/doc/image-vector",
              "/v1/documents/doc/audio-vector",
              "/v1/documents/doc/sound-index",
              "/v1/documents/doc/video-av-index",
              "/v1/ingestions/task/retry",
              "/v1/indexings/task/retry")) {
        var response = filter(fixture.filter, "POST", path, false);
        assertEquals(409, response.getStatus(), path);
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertEquals(
            "model_rebuild_in_progress",
            JsonMapper.builder()
                .build()
                .readTree(response.getContentAsString())
                .path("error_code")
                .asString());
      }
      assertEquals(409, filter(fixture.filter, "DELETE", "/v1/documents/doc", false).getStatus());
      for (String path :
          List.of("/v1/knowledge-answers", "/v1/answers", "/v1/management/document-actions")) {
        filter(fixture.filter, "POST", path, true);
      }
      filter(fixture.filter, "GET", "/v1/documents", true);
    }
  }

  @Test
  void absentAndTerminalRebuildsKeepMutationRequestsAvailable() throws Exception {
    try (var fixture = new Fixture(directory)) {
      filter(fixture.filter, "POST", "/v1/documents", true);
      fixture.queue();
      fixture
          .authority
          .store()
          .transaction(
              () -> {
                new ModelRebuildRepository(fixture.authority.store())
                    .fail("batch", "worker_interrupted", NOW);
                return null;
              });
      assertFalse(fixture.service.mutationsBlocked());
      filter(fixture.filter, "POST", "/v1/documents", true);
    }
  }

  private static MockHttpServletResponse filter(
      ModelRebuildMutationFilter filter, String method, String path, boolean expectedPass)
      throws Exception {
    var response = new MockHttpServletResponse();
    var called = new AtomicBoolean();
    filter.doFilter(
        new MockHttpServletRequest(method, path), response, (request, result) -> called.set(true));
    assertEquals(expectedPass, called.get(), method + " " + path);
    return response;
  }

  private static final class Fixture implements AutoCloseable {
    final AuthorityTestContext authority;
    final ModelConfigurationRepository configurations;
    final ManagedTextRuntime runtime;
    final ModelRebuildService service;
    final ModelRebuildMutationFilter filter;

    Fixture(Path directory) {
      authority = new AuthorityTestContext(directory.resolve("library"));
      configurations =
          new ModelConfigurationRepository(
              directory.resolve("private/models.json"), authority.store());
      runtime =
          new ManagedTextRuntime(
              authority.store(),
              (version, config) -> {
                throw new AssertionError("Status reads must not create a model runtime");
              });
      service =
          new ModelRebuildService(
              authority.store(),
              configurations,
              new DocumentPermissionPolicy(),
              runtime,
              authority.indexing(),
              "org",
              Set.of("owner"),
              (version, config) -> {
                throw new AssertionError("Status reads must not create a model anchor");
              });
      filter =
          new ModelRebuildMutationFilter(
              service, new ProblemHandler(), JsonMapper.builder().build());
    }

    void queue() {
      authority
          .store()
          .transaction(
              () -> {
                var selection = new TextRuntimeSelectionRepository(authority.store()).read();
                new ModelRebuildRepository(authority.store())
                    .insert(
                        new ModelRebuildEntity(
                            "batch",
                            "other-org",
                            "owner",
                            selection,
                            1,
                            "a".repeat(64),
                            "b".repeat(64),
                            new IndexTarget("embedding-v1", "projection-v1", "model-v1", 2),
                            "queued",
                            0,
                            0,
                            null,
                            NOW,
                            NOW),
                        List.of());
                return null;
              });
    }

    @Override
    public void close() {
      service.close();
      runtime.close();
      configurations.close();
      authority.close();
    }
  }
}
