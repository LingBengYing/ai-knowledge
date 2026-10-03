package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.controller.VideoAvController;
import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class VideoAvControllerTest {
  private final VideoAvController controller = new VideoAvController(null, null);

  @Test
  void metadataAndSourcesRejectAnyQueryBodyOrTransferBeforeUseCase() {
    var query = new MockHttpServletRequest();
    query.setQueryString("");
    var body = new MockHttpServletRequest();
    body.setContent(new byte[] {1});
    var transfer = new MockHttpServletRequest();
    transfer.addHeader("Transfer-Encoding", "chunked");
    for (var request : List.of(query, body, transfer)) {
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.build(request, "doc")).code());
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.source(request, "trace", "1"))
              .code());
      assertEquals(
          "invalid_request",
          assertThrows(ApplicationException.class, () -> controller.content(request, "trace", "1"))
              .code());
    }
  }

  @Test
  void allReadsAndActionsRequireTheCurrentAuthenticatedActor() {
    var request = new MockHttpServletRequest();
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.build(request, "doc")).code());
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.source(request, "trace", "1"))
            .code());
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.content(request, "trace", "1"))
            .code());
    assertEquals(
        "unauthenticated",
        assertThrows(
                ApplicationException.class,
                () ->
                    controller.answer(
                        request, "{\"question\":\"发生什么？\"}".getBytes(StandardCharsets.UTF_8)))
            .code());
  }

  @Test
  void sourceOrdinalsAndAnswerQueryAreStrict() {
    var request = new MockHttpServletRequest();
    for (String ordinal : List.of("0", "33", "01", "+1", "-1", "2147483648")) {
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class, () -> controller.source(request, "trace", ordinal))
              .code());
      assertEquals(
          "invalid_request",
          assertThrows(
                  ApplicationException.class, () -> controller.content(request, "trace", ordinal))
              .code());
    }
    request.setQueryString("mode=JOINT");
    assertEquals(
        "invalid_request",
        assertThrows(ApplicationException.class, () -> controller.answer(request, new byte[0]))
            .code());
  }
}
