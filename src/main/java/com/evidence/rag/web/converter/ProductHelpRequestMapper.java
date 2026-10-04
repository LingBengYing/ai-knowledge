package com.evidence.rag.web.converter;

import com.evidence.rag.model.dto.ProductHelpCommand;

/** The existing strict question/selection JSON contract with a narrower per-category limit. */
public final class ProductHelpRequestMapper {
  public static final int MAX_BYTES = RetrievalTestRequestMapper.MAX_BYTES;

  private ProductHelpRequestMapper() {}

  public static ProductHelpCommand command(byte[] bytes) {
    var request = RetrievalTestRequestMapper.command(bytes);
    return new ProductHelpCommand(request.answer(), request.topK(), request.rerank());
  }
}
