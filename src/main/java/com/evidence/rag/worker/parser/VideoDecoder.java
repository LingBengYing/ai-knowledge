package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.DecodedVideo;

/**
 * Complete original-video decoding on one actual video-frame epoch, without inferred frame rate.
 */
public interface VideoDecoder extends AutoCloseable {
  String revision();

  DecodedVideo decode(String filename, String mime, byte[] source);

  @Override
  void close();
}
