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

class AudioInputTest {
  static Stream<Arguments> supported() {
    return Stream.of(
        Arguments.of("voice.wav", "audio/wav", "RIFF\u0004\u0000\u0000\u0000WAVE"),
        Arguments.of("voice.MP3", "audio/mpeg", "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000"),
        Arguments.of("frames.mp3", "audio/mpeg", "\u00ff\u00fb\u0090d\u0000\u0000\u0000\u0000"),
        Arguments.of("voice.flac", "audio/flac", "fLaC\u0000\u0000\u0000\u0000"),
        Arguments.of("voice.ogg", "audio/ogg", "OggS\u0000\u0000\u0000\u0000"),
        Arguments.of(
            "voice.m4a", "audio/mp4", "\u0000\u0000\u0000\u0010ftypM4A \u0000\u0000\u0000\u0000"),
        Arguments.of(
            "voice.mp4", "audio/mp4", "\u0000\u0000\u0000\u0010ftypisom\u0000\u0000\u0000\u0000"),
        Arguments.of("voice.webm", "audio/webm", "\u001aE\u00df\u00a3\u0000\u0000\u0000\u0000"));
  }

  @ParameterizedTest
  @MethodSource("supported")
  void supportedEnvelopeRetainsItsCanonicalMediaType(String filename, String mime, String bytes) {
    assertTrue(AudioInput.isAudioName(filename));
    assertEquals(mime, AudioInput.canonicalMime(filename));
    AudioInput.validateMetadata(filename, "application/octet-stream");
    AudioInput.validateEnvelope(filename, mime, bytes.getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void rejectsMismatchedMagicMimeUnsafeNameAndOversizedBytes() {
    byte[] wav = "RIFF\u0004\u0000\u0000\u0000WAVE".getBytes(StandardCharsets.ISO_8859_1);
    for (String filename : new String[] {"../voice.wav", "voice\n.wav", "voice.txt", ".wav"}) {
      assertEquals(
          "unsupported_document",
          assertThrows(
                  TextParser.Failure.class,
                  () -> AudioInput.validateEnvelope(filename, "audio/wav", wav))
              .code());
    }
    assertThrows(
        TextParser.Failure.class,
        () -> AudioInput.validateEnvelope("voice.mp3", "audio/mpeg", wav));
    assertThrows(
        TextParser.Failure.class,
        () -> AudioInput.validateEnvelope("voice.wav", "audio/mpeg", wav));
    assertThrows(
        TextParser.Failure.class,
        () ->
            AudioInput.validateEnvelope(
                "voice.wav", "audio/wav", new byte[AudioInput.MAX_BYTES + 1]));
    assertFalse(AudioInput.isAudioName(null));
    assertThrows(
        TextParser.Failure.class,
        () -> AudioInput.validateEnvelope("voice.wav", "audio/wav", null));
  }
}
