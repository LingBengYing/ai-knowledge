package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.controller.VideoAvController;
import com.evidence.rag.exception.ApplicationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class VideoAvControllerTest {
  private final VideoAvController controller = new VideoAvController(null);

  @Test
  void indexReadsAndBuildsRejectAnyQueryBodyOrTransferBeforeUseCase() {
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
    }
  }

  @Test
  void indexReadsAndBuildsRequireTheCurrentAuthenticatedActor() {
    var request = new MockHttpServletRequest();
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.get(request, "doc")).code());
    assertEquals(
        "unauthenticated",
        assertThrows(ApplicationException.class, () -> controller.build(request, "doc")).code());
  }
}
