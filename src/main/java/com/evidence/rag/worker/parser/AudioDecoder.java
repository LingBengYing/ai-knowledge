package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.DecodedAudio;

/** Bounded original-audio decoding; returned sample positions define the server time axis. */
public interface AudioDecoder extends AutoCloseable {
  String revision();

  DecodedAudio decode(String filename, String mime, byte[] source);

  @Override
  void close();
}
