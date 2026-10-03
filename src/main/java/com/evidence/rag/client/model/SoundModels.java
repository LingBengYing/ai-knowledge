package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.AudioWaveform;
import java.util.List;

/** Independent complete-waveform analysis; descriptions only support retrieval. */
public interface SoundModels {
  Description describe(AudioWaveform waveform);

  Draft draft(String fullQuestion, AudioWaveform waveform);

  Verification verify(String fullQuestion, AudioWaveform waveform, List<String> claims);

  String revision();

  record Description(String recallText) {
    @Override
    public String toString() {
      return "Description[redacted]";
    }
  }

  record Draft(boolean complete, List<String> claims) {
    public Draft {
      claims = List.copyOf(claims);
    }

    @Override
    public String toString() {
      return "Draft[redacted]";
    }
  }

  record Verification(boolean complete, List<Boolean> supported) {
    public Verification {
      supported = List.copyOf(supported);
    }

    @Override
    public String toString() {
      return "Verification[redacted]";
    }
  }
}
