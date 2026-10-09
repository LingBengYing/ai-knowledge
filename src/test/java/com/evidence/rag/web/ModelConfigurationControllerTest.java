package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.evidence.rag.client.model.TextModelConnectionProbe;
import com.evidence.rag.controller.ModelConfigurationController;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.repository.ModelConfigurationRepository;
import com.evidence.rag.repository.SqliteAuthorityStore;
import com.evidence.rag.security.authorization.ModelConfigurationPermissionPolicy;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ManagedTextRuntime;
import com.evidence.rag.service.ModelConfigurationService;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP mapping with an explicitly supplied trusted actor; real authentication is tested by the
 * mainline.
 */
class ModelConfigurationControllerTest {
  @TempDir Path directory;

  @Test
  void sharedMemberControlsKeepLoginOrganizationAndExactNoQueryNoBodyRoutes() throws Exception {
    try (var store = new SqliteAuthorityStore(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"));
        var runtime =
            new ManagedTextRuntime(
                store,
                (version, config) -> {
                  throw new AssertionError("No offline build expected");
                })) {
      var service =
          new ModelConfigurationService(
              store,
              repository,
              new ModelConfigurationPermissionPolicy("org", Set.of("operator")),
              runtime,
              new TextModelConnectionProbe(
                  URI.create("https://synthetic.invalid/v1"),
                  Duration.ofSeconds(1),
                  1024,
                  false,
                  null));
      var mvc =
          MockMvcBuilders.standaloneSetup(new ModelConfigurationController(service))
              .setControllerAdvice(new ProblemHandler())
              .build();
      var reader = new Actor("org", "reader");
      var result =
          mvc.perform(
                  get("/v1/model-configuration")
                      .requestAttr(AuthenticatedActor.class.getName(), reader))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      var value = JsonMapper.builder().build().readTree(result.getContentAsString());
      assertEquals(9, value.size());
      assertTrue(value.path("can_edit").booleanValue());
      assertEquals("unconfigured", value.path("state").stringValue());
      assertEquals("private, no-store", result.getHeader("Cache-Control"));
      mvc.perform(
              get("/v1/model-configuration")
                  .requestAttr(AuthenticatedActor.class.getName(), reader)
                  .content("{}"))
          .andExpect(status().isUnprocessableEntity());
      mvc.perform(
              get("/v1/model-configuration?x=1")
                  .requestAttr(AuthenticatedActor.class.getName(), reader))
          .andExpect(status().isUnprocessableEntity());
      mvc.perform(
              post("/v1/model-configuration/test")
                  .requestAttr(AuthenticatedActor.class.getName(), reader)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0,\"role\":\"embedding\"}"))
          .andExpect(status().isConflict());
      mvc.perform(get("/v1/model-configuration")).andExpect(status().isUnauthorized());
      mvc.perform(
              get("/v1/model-configuration")
                  .requestAttr(
                      AuthenticatedActor.class.getName(), new Actor("other-org", "reader")))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/v1/model-configuration/test")
                  .requestAttr(AuthenticatedActor.class.getName(), new Actor("other-org", "reader"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0,\"role\":\"embedding\"}"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void malformedModelFieldUsesNewSafeSevenFieldProblemWithoutSubmittedValue() throws Exception {
    try (var store = new SqliteAuthorityStore(directory.resolve("authority"));
        var repository =
            new ModelConfigurationRepository(directory.resolve("private/config.json"));
        var runtime =
            new ManagedTextRuntime(
                store,
                (version, config) -> {
                  throw new AssertionError("No offline build expected");
                })) {
      var service =
          new ModelConfigurationService(
              store,
              repository,
              new ModelConfigurationPermissionPolicy("org", Set.of("operator")),
              runtime,
              new TextModelConnectionProbe(
                  URI.create("https://synthetic.invalid/v1"),
                  Duration.ofSeconds(1),
                  1024,
                  false,
                  null));
      var mvc =
          MockMvcBuilders.standaloneSetup(new ModelConfigurationController(service))
              .setControllerAdvice(new ProblemHandler())
              .build();
      String body =
          "{\"base_version\":0,\"embedding\":{\"model\":\"private forbidden value\",\"dimensions\":2,\"revision\":\"v1\"},\"rerank\":{\"model\":\"rank\"},\"generation\":{\"model\":\"generate\"}}";
      String response =
          mvc.perform(
                  put("/v1/model-configuration")
                      .requestAttr(AuthenticatedActor.class.getName(), new Actor("org", "operator"))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andExpect(status().isUnprocessableEntity())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var parsed = JsonMapper.builder().build().readTree(response);
      assertEquals(7, parsed.size());
      assertEquals("embedding.model", parsed.path("field").stringValue());
      assertEquals("invalid_model_configuration", parsed.path("error_code").stringValue());
      assertFalse(response.contains("private forbidden value"));
      assertEquals(0, repository.read().version());
    }
  }
}
