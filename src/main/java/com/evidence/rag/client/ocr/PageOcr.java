package com.evidence.rag.client.ocr;

import com.evidence.rag.model.domain.VisualImage;

/** Complete text of one rendered PDF page; no word boxes or physical layout are asserted. */
public interface PageOcr extends AutoCloseable {
  String read(VisualImage image);

  @Override
  default void close() {}

  /** Stable failure without provider response, page content, credentials, or endpoint details. */
  final class Failure extends RuntimeException {
    public Failure() {
      super("page_ocr_failed");
    }
  }
}
