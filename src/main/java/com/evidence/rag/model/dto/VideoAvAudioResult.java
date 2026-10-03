package com.evidence.rag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Exact public video audiovisual metadata; all media absence and time units are explicit. */
public record VideoAvAudioResult(
    @JsonProperty("pcm_sha256") String pcmSha256,
    @JsonProperty("wav_sha256") String wavSha256,
    @JsonProperty("start_sample") String startSample,
    @JsonProperty("end_sample") String endSample,
    @JsonProperty("sample_rate") int sampleRate) {
  @Override
  public String toString() {
    return "VideoAvAudioResult[redacted]";
  }
}
