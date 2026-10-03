package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.controller.TagSuggestionController;
import com.evidence.rag.exception.ApplicationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TagSuggestionControllerTest {
  private final TagSuggestionController controller = new TagSuggestionController(null);

  @Test
  void readRejectsQueriesAndBodiesBeforeReachingTheUseCase() {
    var query = new MockHttpServletRequest();
    query.setQueryString("limit=1");
    var body = new MockHttpServletRequest();
    body.setContent(new byte[] {1});
    var transfer = new MockHttpServletRequest();
    transfer.addHeader("Transfer-Encoding", "chunked");
    for (var request : List.of(query, body, transfer)) {
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
    }
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> controller.apply(query, "doc", selection()))
            .code());
  }

  @Test
  void untrustedCallerCannotReachTheSuggestionUseCase() {
    var request = new MockHttpServletRequest();
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
    assertEquals(
        "unauthenticated",
        assertThrows(
                ApplicationException.class, () -> controller.apply(request, "doc", selection()))
            .code());
  }

  private static Map<String, Object> selection() {
    return Map.of("suggestion_fingerprint", "a".repeat(64), "ordinals", List.of(1));
  }
}
