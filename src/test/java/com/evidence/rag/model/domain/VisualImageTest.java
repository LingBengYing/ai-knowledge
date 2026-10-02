package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VisualImageTest {
  @Test
  void ownsOriginalBytesAndDerivesStableDigestWithoutLoggingContent() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    var image = new VisualImage("image/png", bytes);
    bytes[0] = 0;
    image.content()[1] = 0;

    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), image.content());
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", image.sha256());
    assertEquals("VisualImage[redacted]", image.toString());
  }

  @Test
  void onlyOriginalPngAndJpegEnvelopesWithinTheByteBudgetAreRepresentable() {
    assertEquals("image/jpeg", new VisualImage("image/jpeg", new byte[] {1}).mediaType());
    assertThrows(ApplicationException.class, () -> new VisualImage("image/gif", new byte[] {1}));
    assertThrows(ApplicationException.class, () -> new VisualImage(null, new byte[] {1}));
    assertThrows(ApplicationException.class, () -> new VisualImage("image/png", null));
    assertThrows(ApplicationException.class, () -> new VisualImage("image/png", new byte[0]));
    assertThrows(
        ApplicationException.class,
        () -> new VisualImage("image/png", new byte[10 * 1024 * 1024 + 1]));
  }
}
