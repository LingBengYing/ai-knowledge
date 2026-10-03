package com.evidence.rag.client.model;

import java.util.List;

/** Separate complete text and audio queries in one explicitly pinned embedding space. */
public interface SoundEmbeddingModels {
  List<Double> embedAudio(byte[] canonicalWav);

  List<Double> embedText(String fullQuestion);

  String revision();

  int dimensions();
}
