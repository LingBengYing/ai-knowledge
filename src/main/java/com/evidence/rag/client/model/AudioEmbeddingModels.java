package com.evidence.rag.client.model;

import java.util.List;

/** Embeds one complete canonical audio waveform; transcription is not an embedding input. */
public interface AudioEmbeddingModels {
  List<Double> embed(byte[] canonicalWav);

  String revision();

  int dimensions();

  String decoderRevision();
}
