package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class TagSuggestionRequestMapperTest {
  private static final String FINGERPRINT = "a".repeat(64);

  @Test
  void mapsOnlyTheExactFingerprintAndOrderedIntegerSelection() {
    var command =
        TagSuggestionRequestMapper.command(
            Map.of("suggestion_fingerprint", FINGERPRINT, "ordinals", List.of(3, 1, 8)));
    assertEquals(FINGERPRINT, command.suggestionFingerprint());
    assertEquals(List.of(3, 1, 8), command.ordinals());
  }

  @Test
  void rejectsMissingUnknownFieldsAndClientProvidedTagText() {
    assertInvalid(null);
    assertInvalid(Map.of());
    assertInvalid(Map.of("suggestion_fingerprint", FINGERPRINT));
    assertInvalid(Map.of("ordinals", List.of(1)));
    for (String extra : List.of("tags", "document_id", "synopsis_id", "publication_id")) {
      assertInvalid(
          Map.of("suggestion_fingerprint", FINGERPRINT, "ordinals", List.of(1), extra, "x"));
    }
    for (Object fingerprint : Arrays.asList(null, "", "a".repeat(63), "A".repeat(64), 1)) {
      var body = new LinkedHashMap<String, Object>();
      body.put("suggestion_fingerprint", fingerprint);
      body.put("ordinals", List.of(1));
      assertInvalid(body);
    }
  }

  @Test
  void rejectsCoercionsEmptyDuplicateOrOutOfRangeOrdinals() {
    for (Object ordinals :
        Arrays.asList(
            null,
            "1",
            1,
            List.of(),
            List.of(1, 1),
            List.of(0),
            List.of(9),
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 1),
            List.of("1"),
            List.of(true),
            List.of(1.0),
            List.of(new BigDecimal("1.0")),
            Arrays.asList(1, null))) {
      var body = new LinkedHashMap<String, Object>();
      body.put("suggestion_fingerprint", FINGERPRINT);
      body.put("ordinals", ordinals);
      assertInvalid(body);
    }
  }

  @Test
  void actualJsonIntegerTokenIsRequiredWithoutDecimalOrExponentCoercion() {
    var json = JsonMapper.builder().build();
    for (String token : List.of("1.0", "1e0", "2147483648", "true", "\"1\"")) {
      Map<String, Object> body =
          json.readValue(
              "{\"suggestion_fingerprint\":\"" + FINGERPRINT + "\",\"ordinals\":[" + token + "]}",
              new TypeReference<>() {});
      assertInvalid(body);
    }
    Map<String, Object> body =
        json.readValue(
            "{\"suggestion_fingerprint\":\"" + FINGERPRINT + "\",\"ordinals\":[1,8]}",
            new TypeReference<>() {});
    assertEquals(List.of(1, 8), TagSuggestionRequestMapper.command(body).ordinals());
  }

  private static void assertInvalid(Map<String, Object> body) {
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> TagSuggestionRequestMapper.command(body))
            .code());
  }
}
