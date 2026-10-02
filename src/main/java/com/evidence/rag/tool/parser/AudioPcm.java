package com.evidence.rag.tool.parser;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Deterministic PCM-to-WAV framing for already decoded audio; no codec, file or model I/O. */
public final class AudioPcm {
  private AudioPcm() {}

  public static byte[] wav(byte[] pcm, int from, int to) {
    if (pcm == null
        || from < 0
        || to > pcm.length
        || to <= from
        || from % 2 != 0
        || to % 2 != 0
        || to - from > 960_000) {
      throw ModelValues.invalid();
    }
    int size = to - from;
    var result = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
    result.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + size);
    result.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    result.putShort((short) 1).putShort((short) 1).putInt(16_000).putInt(32_000);
    result.putShort((short) 2).putShort((short) 16);
    result.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size).put(pcm, from, size);
    return result.array();
  }
}
