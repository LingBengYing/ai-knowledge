package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** Reads at most one byte past a mapper's limit, so oversize bodies never fill the heap. */
final class BoundedBody {
  private BoundedBody() {}

  static byte[] read(HttpServletRequest request, int maxBytes) {
    try {
      return request.getInputStream().readNBytes(maxBytes + 1);
    } catch (IOException failure) {
      throw ModelValues.invalid();
    }
  }
}
