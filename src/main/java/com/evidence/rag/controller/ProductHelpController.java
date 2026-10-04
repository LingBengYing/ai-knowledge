package com.evidence.rag.controller;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.ProductHelpResult;
import com.evidence.rag.security.web.AuthenticatedActor;
import com.evidence.rag.service.ProductHelpService;
import com.evidence.rag.web.converter.ProductHelpRequestMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Independent product-help search; it never publishes a generated answer or query trace. */
@RestController
@ConditionalOnProperty(prefix = "rag.model-configuration", name = "enabled", havingValue = "true")
public final class ProductHelpController {
  private final ProductHelpService service;

  public ProductHelpController(ProductHelpService service) {
    this.service = service;
  }

  @PostMapping(value = "/v1/product-help/search", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ProductHelpResult> search(HttpServletRequest request) {
    if (request.getQueryString() != null) {
      throw ModelValues.invalid();
    }
    var actor = AuthenticatedActor.require(request);
    try {
      var command = ProductHelpRequestMapper.command(
          request.getInputStream().readNBytes(ProductHelpRequestMapper.MAX_BYTES + 1));
      return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
          .body(service.search(actor, command));
    } catch (IOException failed) {
      throw ModelValues.invalid();
    }
  }
}
