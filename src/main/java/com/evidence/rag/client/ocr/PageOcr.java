package com.evidence.rag.client.ocr;

import com.evidence.rag.model.domain.VisualImage;

/** Complete text of one rendered PDF page; no word boxes or physical layout are asserted. */
public interface PageOcr extends AutoCloseable {
  String read(VisualImage image);

  @Override
  default void close() {}

  /** Stable failure without provider response, page content, credentials, or endpoint details. */
  final class Failure extends RuntimeException {
    private final boolean retryable;

    public Failure() {
      this(false);
    }

    /** A retryable failure (busy, rate limited, transient upstream error) may succeed again. */
    public Failure(boolean retryable) {
      super("page_ocr_failed");
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
