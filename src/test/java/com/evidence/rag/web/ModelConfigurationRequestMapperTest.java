package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.ModelConfigurationInputException;
import com.evidence.rag.model.domain.TextModelRole;
import com.evidence.rag.web.converter.ModelConfigurationRequestMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelConfigurationRequestMapperTest {
  private static final String VALID =
      """
      {"base_version":0,"embedding":{"model":"embed","dimensions":2,"revision":"pinned-v1","api_key":"REPLACE_ME"},"rerank":{"model":"rank","api_key":"REPLACE_ME"},"generation":{"model":"generate","api_key":"REPLACE_ME"}}
      """;

  @Test
  void threeRolesAndExplicitIntegerVersionAreParsedWithoutInventedSecrets() {
    var command = ModelConfigurationRequestMapper.save(bytes(VALID));
    assertEquals(0, command.baseVersion());
    assertEquals(2, command.embedding().dimensions());
    assertEquals("REPLACE_ME", command.resolve(null).generation().apiKey());
    var keep =
        ModelConfigurationRequestMapper.save(
            bytes(VALID.replace(",\"api_key\":\"REPLACE_ME\"", "")));
    assertNull(keep.embedding().apiKey());
    assertEquals(
        TextModelRole.RERANK,
        ModelConfigurationRequestMapper.test(bytes("{\"version\":1,\"role\":\"rerank\"}")).role());
    assertEquals(1, ModelConfigurationRequestMapper.activate(bytes("{\"version\":1}")));
  }

  @Test
  void unknownDuplicateTrailingAndMalformedUtf8AreRejectedAsRequestWithoutEcho() {
    for (String invalid :
        List.of(
            VALID.replace("\"base_version\":0", "\"base_version\":0,\"base_version\":0"),
            VALID.replace("\"base_version\":0", "\"private-user-input\":0,\"base_version\":0"),
            VALID + "{}",
            "null",
            "[]")) {
      assertEquals(
          "request",
          assertThrows(
                  ModelConfigurationInputException.class,
                  () -> ModelConfigurationRequestMapper.save(bytes(invalid)))
              .field());
    }
    assertEquals(
        "request",
        assertThrows(
                ModelConfigurationInputException.class,
                () -> ModelConfigurationRequestMapper.save(new byte[] {(byte) 0xff}))
            .field());
    assertEquals(
        "model_configuration_too_large",
        assertThrows(
                ApplicationException.class,
                () -> ModelConfigurationRequestMapper.save(new byte[131073]))
            .code());
  }

  @Test
  void fractionalVersionAndExplicitNullKeyReportOnlyTheirFixedField() {
    assertEquals(
        "base_version",
        assertThrows(
                ModelConfigurationInputException.class,
                () ->
                    ModelConfigurationRequestMapper.save(
                        bytes(VALID.replace("\"base_version\":0", "\"base_version\":0.0"))))
            .field());
    assertEquals(
        "embedding.api_key",
        assertThrows(
                ModelConfigurationInputException.class,
                () ->
                    ModelConfigurationRequestMapper.save(
                        bytes(
                            VALID.replaceFirst(
                                "\"api_key\":\"REPLACE_ME\"", "\"api_key\":null"))))
            .field());
    assertEquals(
        "embedding.dimensions",
        assertThrows(
                ModelConfigurationInputException.class,
                () ->
                    ModelConfigurationRequestMapper.save(
                        bytes(VALID.replace("\"dimensions\":2", "\"dimensions\":8193"))))
            .field());
    assertEquals(
        "role",
        assertThrows(
                ModelConfigurationInputException.class,
                () ->
                    ModelConfigurationRequestMapper.test(
                        bytes("{\"version\":1,\"role\":\"private-value\"}")))
            .field());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
