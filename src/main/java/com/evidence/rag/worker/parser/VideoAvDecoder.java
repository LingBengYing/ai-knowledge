package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.VideoAvCompilation;

public interface VideoAvDecoder extends AutoCloseable {
  String revision();

  VideoAvCompilation decode(String filename, String mediaType, byte[] source);

  @Override
  void close();
}
