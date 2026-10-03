package com.evidence.rag.model.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Complete canonical PCM segment on the original decoder's absolute sample axis. */
public record AudioWaveform(
    String sourceSha256, String decoderRevision, long startSample, long endSample, byte[] pcm) {
  public AudioWaveform {
    ModelValues.identifier(decoderRevision, 200);
    if (sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || startSample < 0
        || endSample <= startSample
        || endSample > 9600000
        || pcm == null
        || pcm.length > 960000
        || pcm.length != 2 * (endSample - startSample)) {
      throw ModelValues.invalid();
    }
    pcm = pcm.clone();
  }

  @Override
  public byte[] pcm() {
    return pcm.clone();
  }

  public String pcmSha256() {
    return ModelValues.sha256(pcm);
  }

  public byte[] wav() {
    var output = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
    output.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    output
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(16000)
        .putInt(32000)
        .putShort((short) 2)
        .putShort((short) 16);
    output.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
    return output.array();
  }

  @Override
  public String toString() {
    return "AudioWaveform[redacted]";
  }
}
