package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Opt-in native decode acceptance; contains no ASR or cloud requests. */
class AudioDecoderNativeIT {
  @Test
  void actualFfprobeAndFfmpegPreserveSyntheticPcmAndDeriveTimeFromSamples() throws Exception {
    if (!"true".equals(System.getenv("RAG_AUDIO_DECODER_IT_ENABLED"))) {
      throw new IllegalArgumentException("Explicit native audio decoder IT opt-in is required");
    }
    Path ffmpeg = configuredPath("RAG_AUDIO_DECODER_IT_FFMPEG");
    Path ffprobe = configuredPath("RAG_AUDIO_DECODER_IT_FFPROBE");
    byte[] original = ProcessAudioDecoderTest.wav(16_321);
    try (var decoder = new ProcessAudioDecoder(ffmpeg, ffprobe, Duration.ofSeconds(10))) {
      var decoded = decoder.decode("synthetic.wav", "audio/wav", original);
      assertArrayEquals(Arrays.copyOfRange(original, 44, original.length), decoded.pcm());
      assertEquals(1021, decoded.durationMs());
      assertEquals(ProcessAudioDecoderTest.sha(original), decoded.sourceSha256());
      assertEquals(decoder.revision(), decoded.decoderRevision());
      assertTrue(decoder.revision().matches("java-audio-decoder-v1:[a-f0-9]{64}"));
    }
  }

  private static Path configuredPath(String variable) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Explicit native audio decoder paths are required");
    }
    return Path.of(value);
  }
}
