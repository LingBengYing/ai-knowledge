package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.web.converter.DocumentCleanupRequestMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentCleanupRequestMapperTest {
  @Test
  void batchPreservesTheCompleteSelectionAndOrder() {
    assertEquals(
        List.of("one", "missing", "two"),
        DocumentCleanupRequestMapper.documentIds(
            Map.of("document_ids", List.of("one", "missing", "two"))));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            DocumentCleanupRequestMapper.documentIds(Map.of("document_ids", List.of("one")))
                .add("two"));
  }

  @Test
  void malformedOrDuplicateBatchNeverBecomesAPartialRequest() {
    for (Map<String, Object> body :
        List.<Map<String, Object>>of(
            Map.of(),
            Map.of("document_ids", List.of()),
            Map.of("document_ids", List.of("one", "one")),
            Map.of("document_ids", List.of("one", 2)),
            Map.of("document_ids", List.of("../one")),
            Map.of("document_ids", List.of("one"), "action", "delete"),
            Map.of("document_ids", java.util.Collections.nCopies(101, "one")))) {
      assertThrows(
          ApplicationException.class, () -> DocumentCleanupRequestMapper.documentIds(body));
    }
  }

  @Test
  void authorizedPagingRejectsDuplicateFractionalAndUnboundedParameters() {
    assertArrayEquals(new int[] {1, 20}, DocumentCleanupRequestMapper.page(Map.of()));
    assertArrayEquals(
        new int[] {2, 100},
        DocumentCleanupRequestMapper.page(
            Map.of("page", new String[] {"2"}, "page_size", new String[] {"100"})));
    for (Map<String, String[]> query :
        List.of(
            Map.of("page", new String[] {"1", "2"}),
            Map.of("page", new String[] {"0"}),
            Map.of("page", new String[] {"1.5"}),
            Map.of("page_size", new String[] {"101"}),
            Map.of("page", new String[] {"999999999999"}),
            Map.of("actor", new String[] {"owner"}))) {
      assertThrows(ApplicationException.class, () -> DocumentCleanupRequestMapper.page(query));
    }
  }

  @Test
  void strictJsonRejectsDuplicateKeysTrailingObjectsAndNonUtf8BeforeAnyBatch() {
    assertEquals(
        List.of("one", "two"),
        DocumentCleanupRequestMapper.documentIds(
            "{\"document_ids\":[\"one\",\"two\"]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    for (String body :
        List.of(
            "null",
            "[]",
            "{\"document_ids\":[\"one\"],\"document_ids\":[\"two\"]}",
            "{\"document_ids\":[\"one\"]}{}")) {
      assertThrows(
          ApplicationException.class,
          () ->
              DocumentCleanupRequestMapper.documentIds(
                  body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    assertThrows(
        ApplicationException.class,
        () -> DocumentCleanupRequestMapper.documentIds(new byte[] {(byte) 0xff}));
    assertEquals(
        com.evidence.rag.exception.FailureKind.PAYLOAD_TOO_LARGE,
        assertThrows(
                ApplicationException.class,
                () -> DocumentCleanupRequestMapper.documentIds(new byte[128 * 1024 + 1]))
            .kind());
  }
}
