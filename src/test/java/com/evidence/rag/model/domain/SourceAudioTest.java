package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceAudioTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"audio/wav", "audio/mpeg", "audio/flac", "audio/ogg", "audio/mp4", "audio/webm"})
  void originalAudioHasAnExplicitSafeMediaTypeAndDefensiveBytes(String mime) {
    byte[] original = {4, 8, 15, 16, 23, 42};
    var source = new SourceAudio(mime, original);
    original[0] = 99;
    assertArrayEquals(new byte[] {4, 8, 15, 16, 23, 42}, source.content());
    byte[] copy = source.content();
    copy[1] = 99;
    assertArrayEquals(new byte[] {4, 8, 15, 16, 23, 42}, source.content());
    assertEquals(mime, source.mimeType());
    assertEquals("SourceAudio[redacted]", source.toString());
  }

  @Test
  void originalAudioRejectsMissingOversizedAndUnsafeValues() {
    for (String mime :
        new String[] {null, "text/html", "video/mp4", "audio/wav\r\nX-Unsafe: true", ""}) {
      assertThrows(ApplicationException.class, () -> new SourceAudio(mime, new byte[] {1}));
    }
    assertThrows(ApplicationException.class, () -> new SourceAudio("audio/wav", null));
    assertThrows(ApplicationException.class, () -> new SourceAudio("audio/wav", new byte[0]));
    assertThrows(
        ApplicationException.class,
        () -> new SourceAudio("audio/wav", new byte[20 * 1024 * 1024 + 1]));
  }
}
