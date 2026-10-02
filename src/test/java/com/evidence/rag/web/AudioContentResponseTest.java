package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.evidence.rag.model.domain.SourceAudio;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

class AudioContentResponseTest {
  private static final byte[] ORIGINAL = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

  @Test
  void completeResponseReturnsOriginalBytesWithoutCaching() {
    var response = AudioContentResponse.create(new SourceAudio("audio/wav", ORIGINAL), List.of());
    assertEquals(200, response.getStatusCode().value());
    assertArrayEquals(ORIGINAL, response.getBody());
    assertEquals(10, response.getHeaders().getContentLength());
    assertEquals("audio/wav", response.getHeaders().getContentType().toString());
    assertNull(response.getHeaders().getFirst("Content-Range"));
    assertSafetyHeaders(response);
  }

  @ParameterizedTest
  @CsvSource({"bytes=2-5,2,5", "bytes=7-,7,9", "bytes=-3,7,9", "bytes=8-500,8,9", "bytes=-100,0,9"})
  void singleRangesReadExactOriginalBytes(String range, int start, int end) {
    var response =
        AudioContentResponse.create(new SourceAudio("audio/wav", ORIGINAL), List.of(range));
    assertEquals(206, response.getStatusCode().value());
    assertEquals(
        "bytes " + start + "-" + end + "/10", response.getHeaders().getFirst("Content-Range"));
    assertEquals(end - start + 1, response.getHeaders().getContentLength());
    assertArrayEquals(java.util.Arrays.copyOfRange(ORIGINAL, start, end + 1), response.getBody());
    assertSafetyHeaders(response);
  }

  @Test
  void unsatisfiableMalformedAndMultipleRangesDoNotReturnAudioBytes() {
    for (var ranges :
        List.of(
            List.of("bytes=10-"),
            List.of("bytes=9-1"),
            List.of("bytes=-0"),
            List.of("bytes=0-1,5-6"),
            List.of("items=0-1"),
            List.of("bytes=abc-def"),
            List.of(""),
            List.of("bytes=0-1", "bytes=5-6"),
            List.of("bytes=999999999999999999999999999-"))) {
      var response = AudioContentResponse.create(new SourceAudio("audio/wav", ORIGINAL), ranges);
      assertEquals(416, response.getStatusCode().value(), ranges.toString());
      assertEquals("bytes */10", response.getHeaders().getFirst("Content-Range"));
      assertEquals(0, response.getHeaders().getContentLength());
      assertNull(response.getBody());
      assertSafetyHeaders(response);
    }
  }

  private static void assertSafetyHeaders(ResponseEntity<byte[]> response) {
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    assertEquals("bytes", response.getHeaders().getFirst("Accept-Ranges"));
  }
}
