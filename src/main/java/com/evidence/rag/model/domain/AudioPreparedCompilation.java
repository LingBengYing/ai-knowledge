package com.evidence.rag.model.domain;

import java.util.List;

/** One complete decode/transcription and every corresponding original PCM slice. */
public record AudioPreparedCompilation(
    AudioCompilation compilation, List<AudioWaveform> waveforms) {
  public AudioPreparedCompilation {
    if (compilation == null
        || waveforms == null
        || waveforms.size() != compilation.spans().size()) {
      throw ModelValues.invalid();
    }
    long end = 0;
    for (int index = 0; index < waveforms.size(); index++) {
      var waveform = waveforms.get(index);
      var span = compilation.spans().get(index);
      if (waveform == null
          || !waveform.sourceSha256().equals(compilation.sourceSha256())
          || !waveform.decoderRevision().equals(compilation.decoderRevision())
          || waveform.startSample() != end
          || waveform.startSample() != span.startMs() * 16
          || (waveform.endSample() + 15) / 16 != span.endMs()
          || (index + 1 < waveforms.size() && waveform.endSample() != span.endMs() * 16)) {
        throw ModelValues.invalid();
      }
      end = waveform.endSample();
    }
    waveforms = List.copyOf(waveforms);
  }

  @Override
  public String toString() {
    return "AudioPreparedCompilation[redacted]";
  }
}
