package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class MediaContentResponseTest {
  private static final byte[] ORIGINAL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

  @Test
  void originalVideoAndSingleRangeUseItsOwnMimeAndExactBytes() {
    var complete = MediaContentResponse.create("video/mp4", ORIGINAL, List.of());
    assertEquals(200, complete.getStatusCode().value());
    assertArrayEquals(ORIGINAL, complete.getBody());
    assertEquals("video/mp4", complete.getHeaders().getContentType().toString());
    assertEquals("no-store", complete.getHeaders().getCacheControl());
    assertEquals("nosniff", complete.getHeaders().getFirst("X-Content-Type-Options"));
    assertEquals("bytes", complete.getHeaders().getFirst("Accept-Ranges"));
    var range = MediaContentResponse.create("video/mp4", ORIGINAL, List.of("bytes=2-5"));
    assertEquals(206, range.getStatusCode().value());
    assertArrayEquals(new byte[] {2, 3, 4, 5}, range.getBody());
    assertEquals("bytes 2-5/10", range.getHeaders().getFirst("Content-Range"));
    assertEquals(4, range.getHeaders().getContentLength());
  }

  @Test
  void videoUnsatisfiableAndMultipleRangesExposeNoContent() {
    for (var ranges :
        List.of(
            List.of("bytes=10-"), List.of("bytes=0-1,4-5"), List.of("bytes=0-1", "bytes=4-5"))) {
      var response = MediaContentResponse.create("video/webm", ORIGINAL, ranges);
      assertEquals(416, response.getStatusCode().value());
      assertNull(response.getBody());
      assertEquals("bytes */10", response.getHeaders().getFirst("Content-Range"));
      assertEquals("video/webm", response.getHeaders().getContentType().toString());
    }
  }
}
