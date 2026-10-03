package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.VisualImage;
import java.util.List;

/** Original-image embedding seam; callers own authority, projection identity and total budget. */
public interface ImageEmbeddingModels {
  List<Double> embed(VisualImage image);

  String revision();

  int dimensions();
}
