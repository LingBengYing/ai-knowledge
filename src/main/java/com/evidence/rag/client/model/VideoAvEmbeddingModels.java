package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.VideoAvClip;
import java.util.List;

/** Three independent complete inputs in one explicitly pinned embedding space. */
public interface VideoAvEmbeddingModels {
  List<Double> embedText(String fullQuestion);

  List<Double> embedVideo(VideoAvClip clip);

  List<Double> embedAudio(AudioWaveform waveform);

  String revision();

  int dimensions();
}
