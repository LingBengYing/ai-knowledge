package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.web.converter.ReindexRequestMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReindexRequestMapperTest {
  @Test
  void exactObservedPublicationAcceptsOrdinaryJsonWhitespaceAtTheWholeBudget() {
    byte[] prefix =
        "{\"base_publication_id\":\"publication-observed\"}".getBytes(StandardCharsets.UTF_8);
    byte[] body = Arrays.copyOf(prefix, ReindexRequestMapper.MAX_BYTES);
    Arrays.fill(body, prefix.length, body.length, (byte) ' ');
    assertEquals("publication-observed", ReindexRequestMapper.basePublicationId(body));
    var tooLarge = Arrays.copyOf(body, body.length + 1);
    assertEquals(
        "request_too_large",
        assertThrows(
                ApplicationException.class, () -> ReindexRequestMapper.basePublicationId(tooLarge))
            .code());
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void malformedOrUnpinnedRequestsCannotSelectANewerPublication(byte[] body) {
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> ReindexRequestMapper.basePublicationId(body))
            .code());
  }

  private static Stream<byte[]> invalidBodies() {
    return Stream.concat(
        Stream.of(
                "",
                "{}",
                "null",
                "[]",
                "{\"base_publication_id\":null}",
                "{\"base_publication_id\":1}",
                "{\"base_publication_id\":{\"id\":\"publication-observed\"}}",
                "{\"base_publication_id\":\"\"}",
                "{\"base_publication_id\":\"publication observed\"}",
                "{\"base_publication_id\":\"../publication\"}",
                "{\"base_publication_id\":\"" + "p".repeat(101) + "\"}",
                "{\"base_publication_id\":\"publication-observed\",\"target\":\"replacement\"}",
                "{\"base_publication_id\":\"first\",\"base_publication_id\":\"second\"}",
                "{\"base_publication_id\":\"publication-observed\"}{}")
            .map(value -> value.getBytes(StandardCharsets.UTF_8)),
        Stream.of(new byte[] {(byte) 0xc3, (byte) 0x28}));
  }
}
