package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.model.domain.VisualImage;
import java.util.Optional;

/** OCR of one original image, including a valid, fully processed image without words. */
public interface ImageOcr {
  String revision();

  Optional<ParsedImage> read(VisualImage image);
}
