package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.evidence.rag.controller.RetrievalSettingsController;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.RetrievalSettings;
import com.evidence.rag.repository.RetrievalSettingsRepository;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.RetrievalSettingsService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mapping with a trusted actor; authentication-filter coverage remains in the mainline HTTP suite.
 */
class RetrievalSettingsControllerTest {
  @TempDir Path directory;
  private static final String BODY =
      """
      {"version":0,"search_method":"full_text","ranking_mode":"weighted","dense_weight":0.25,
      "top_k":7,"score_threshold_enabled":true,"score_threshold":1.75}
      """;

  @Test
  void readSaveRestartAndConflictUseExactSnakeCaseAndSharedMembership() throws Exception {
    Path file = directory.resolve("settings.json");
    var service = new RetrievalSettingsService(new RetrievalSettingsRepository(file, "org"), "org");
    var mvc =
        MockMvcBuilders.standaloneSetup(new RetrievalSettingsController(service))
            .setControllerAdvice(new ProblemHandler())
            .build();
    var first = new Actor("org", "reader");
    var response =
        mvc.perform(
                get("/v1/retrieval-settings")
                    .requestAttr(AuthenticatedActor.class.getName(), first))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertEquals("private, no-store", response.getHeader("Cache-Control"));
    var json = JsonMapper.builder().build();
    var defaults = json.readTree(response.getContentAsString());
    assertEquals(7, defaults.size());
    assertEquals("hybrid", defaults.path("search_method").stringValue());
    assertEquals(5, defaults.path("top_k").intValue());
    assertFalse(Files.exists(file));
    var snapshot = service.snapshot();
    var saved =
        mvc.perform(
                put("/v1/retrieval-settings")
                    .requestAttr(
                        AuthenticatedActor.class.getName(), new Actor("org", "another-member"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertEquals(1, json.readTree(saved.getContentAsString()).path("version").longValue());
    assertEquals(RetrievalSettings.defaults(), snapshot);
    assertEquals(
        new RetrievalSettings(1, "full_text", "weighted", 0.25, 7, true, 1.75),
        new RetrievalSettingsRepository(file, "org").read());
    mvc.perform(
            put("/v1/retrieval-settings")
                .requestAttr(AuthenticatedActor.class.getName(), first)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidRequestsCannotWriteSettingsAndReturnSafeErrors() throws Exception {
    Path file = directory.resolve("settings.json");
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new RetrievalSettingsController(
                    new RetrievalSettingsService(
                        new RetrievalSettingsRepository(file, "org"), "org")))
            .setControllerAdvice(new ProblemHandler())
            .build();
    var actor = new Actor("org", "reader");
    mvc.perform(get("/v1/retrieval-settings")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/v1/retrieval-settings")
                .requestAttr(AuthenticatedActor.class.getName(), new Actor("other", "reader")))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/v1/retrieval-settings")
                .requestAttr(AuthenticatedActor.class.getName(), new Actor("other", "reader"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/v1/retrieval-settings?extra=1")
                .requestAttr(AuthenticatedActor.class.getName(), actor))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/v1/retrieval-settings")
                .content("{}")
                .requestAttr(AuthenticatedActor.class.getName(), actor))
        .andExpect(status().isBadRequest());
    String malformed =
        mvc.perform(
                put("/v1/retrieval-settings")
                    .requestAttr(AuthenticatedActor.class.getName(), actor)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.replace("full_text", "private-invalid-input")))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(malformed.contains("private-invalid-input"));
    assertEquals(
        "invalid_retrieval_settings",
        JsonMapper.builder().build().readTree(malformed).path("error_code").stringValue());
    mvc.perform(
            put("/v1/retrieval-settings")
                .requestAttr(AuthenticatedActor.class.getName(), actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(16 * 1024 + 1)))
        .andExpect(status().isPayloadTooLarge());
    assertFalse(Files.exists(file));
  }
}
