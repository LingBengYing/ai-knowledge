package com.evidence.rag.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VideoInputTest {
  static Stream<Arguments> supported() {
    return Stream.of(
        Arguments.of(
            "scene.MP4", "video/mp4", "\u0000\u0000\u0000\u0010ftypisom\u0000\u0000\u0000\u0000"),
        Arguments.of(
            "scene.mov",
            "video/quicktime",
            "\u0000\u0000\u0000\u0010ftypqt  \u0000\u0000\u0000\u0000"),
        Arguments.of("scene.webm", "video/webm", "\u001aE\u00df\u00a3\u0000\u0000\u0000\u0000"),
        Arguments.of(
            "scene.mkv", "video/x-matroska", "\u001aE\u00df\u00a3\u0000\u0000\u0000\u0000"));
  }

  @ParameterizedTest
  @MethodSource("supported")
  void admitsSupportedContainerEnvelopesWithoutClaimingActualVideoStreams(
      String filename, String mime, String envelope) {
    assertTrue(VideoInput.isVideoName(filename));
    assertEquals(mime, VideoInput.canonicalMime(filename));
    VideoInput.validateMetadata(filename, "application/octet-stream");
    VideoInput.validateEnvelope(filename, mime, envelope.getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void rejectsUnsafeNamesMismatchedMimeMagicAndOversizedInput() {
    byte[] mp4 =
        "\u0000\u0000\u0000\u0010ftypisom\u0000\u0000\u0000\u0000"
            .getBytes(StandardCharsets.ISO_8859_1);
    for (String filename : new String[] {"../scene.mp4", "scene\n.mp4", ".mp4", "scene.txt"}) {
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> VideoInput.validateEnvelope(filename, "video/mp4", mp4))
              .code());
    }
    assertThrows(
        TextParser.Failure.class, () -> VideoInput.validateEnvelope("scene.mp4", "audio/mp4", mp4));
    assertThrows(
        TextParser.Failure.class,
        () -> VideoInput.validateEnvelope("scene.webm", "video/webm", mp4));
    assertThrows(
        TextParser.Failure.class,
        () -> VideoInput.validateEnvelope("scene.mp4", "video/mp4", null));
    assertThrows(
        TextParser.Failure.class,
        () -> VideoInput.validateEnvelope("scene.mp4", "video/mp4", new byte[7]));
    assertThrows(
        TextParser.Failure.class,
        () ->
            VideoInput.validateEnvelope(
                "scene.mp4", "video/mp4", new byte[VideoInput.MAX_BYTES + 1]));
    assertFalse(VideoInput.isVideoName(null));
    assertFalse(VideoInput.isVideoName("audio.wav"));
    assertThrows(TextParser.Failure.class, () -> VideoInput.canonicalMime("audio.wav"));
  }
}
