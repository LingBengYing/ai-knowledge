package com.evidence.rag.model.domain;

public record VideoAvAudioMetadata(
    String pcmSha256, String wavSha256, long startSample, long endSample, int sampleRate) {
  public VideoAvAudioMetadata {
    VideoAvProfile.hash(pcmSha256);
    VideoAvProfile.hash(wavSha256);
    if (startSample < 0
        || endSample <= startSample
        || endSample > 9600000
        || endSample - startSample > 480000
        || sampleRate != 16000) {
      throw ModelValues.invalid();
    }
  }

  public static VideoAvAudioMetadata from(AudioWaveform a) {
    return a == null
        ? null
        : new VideoAvAudioMetadata(
            a.pcmSha256(), ModelValues.sha256(a.wav()), a.startSample(), a.endSample(), 16000);
  }
}
